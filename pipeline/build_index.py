"""Nightly job: fetch Belgian ML papers from OpenAlex, embed them, write dist/.

Outputs (published to the GitHub release `index-latest`):
  papers.json  - metadata for every paper (same order as the vectors)
  vectors.bin  - N x 384 int8 values (normalised vector * 127)
  model.onnx   - the embedding model the phone must use
  vocab.txt    - its WordPiece vocabulary
"""
import datetime as dt
import json
import os
import pathlib
import shutil
import sys
import time

import numpy as np
import requests
from huggingface_hub import hf_hub_download

from embedder import Embedder

API = "https://api.openalex.org"
MODEL_REPO = "sentence-transformers/all-MiniLM-L6-v2"
MODEL_FILE = "onnx/model_qint8_arm64.onnx"  # ~23 MB, runs on x86 too

OUT = pathlib.Path(os.environ.get("OUT_DIR", "dist"))
MAILTO = os.environ.get("OPENALEX_MAILTO", "").strip()
COUNTRY = os.environ.get("COUNTRY", "BE")
# OpenAlex subfields: 1702 Artificial Intelligence, 1707 Computer Vision & Pattern Recognition
SUBFIELDS = os.environ.get("SUBFIELDS", "1702|1707")
SINCE = os.environ.get("SINCE", "2024-01-01")
MAX_PAPERS = int(os.environ.get("MAX_PAPERS", "20000"))
ABSTRACT_CHARS = 400

session = requests.Session()
session.headers["User-Agent"] = f"PapersNearMe/0.1 (mailto:{MAILTO or 'unknown'})"


def get(path: str, params: dict) -> dict:
    params = dict(params)
    if MAILTO:
        params["mailto"] = MAILTO  # puts us in OpenAlex's faster "polite pool"
    for attempt in range(6):
        r = session.get(f"{API}{path}", params=params, timeout=60)
        if r.status_code == 200:
            return r.json()
        if r.status_code in (429, 500, 502, 503, 504):
            time.sleep(2 ** attempt)
            continue
        print(r.text[:500], file=sys.stderr)
        r.raise_for_status()
    r.raise_for_status()


def rebuild_abstract(inverted: dict | None) -> str:
    if not inverted:
        return ""
    words = [(i, w) for w, positions in inverted.items() for i in positions]
    return " ".join(w for _, w in sorted(words))


def fetch_works() -> list[dict]:
    filt = ",".join([
        f"authorships.institutions.country_code:{COUNTRY}",
        f"primary_topic.subfield.id:{SUBFIELDS}",
        f"from_publication_date:{SINCE}",
        "has_abstract:true",
    ])
    select = "id,doi,title,publication_year,publication_date,authorships,abstract_inverted_index"
    works, cursor = [], "*"
    while cursor and len(works) < MAX_PAPERS:
        data = get("/works", {"filter": filt, "select": select, "per-page": 200,
                              "cursor": cursor, "sort": "publication_date:desc"})
        if cursor == "*":
            print(f"OpenAlex reports {data['meta']['count']} matching works")
        works.extend(data["results"])
        cursor = data["meta"].get("next_cursor")
        if not data["results"]:
            break
    return works[:MAX_PAPERS]


def fetch_cities(institution_ids: list[str]) -> dict[str, str]:
    cities = {}
    for i in range(0, len(institution_ids), 50):
        chunk = institution_ids[i:i + 50]
        short = "|".join(x.rsplit("/", 1)[-1] for x in chunk)
        data = get("/institutions", {"filter": f"openalex:{short}",
                                     "select": "id,geo", "per-page": 50})
        for inst in data["results"]:
            cities[inst["id"]] = ((inst.get("geo") or {}).get("city")) or "Unknown"
    return cities


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    works = fetch_works()
    print(f"Fetched {len(works)} works")

    # Collect the local (in-country) institutions and their cities.
    inst_names: dict[str, str] = {}
    for w in works:
        for a in w.get("authorships") or []:
            for inst in a.get("institutions") or []:
                if inst.get("country_code") == COUNTRY and inst.get("id"):
                    inst_names[inst["id"]] = inst.get("display_name") or "Unknown"
    inst_ids = sorted(inst_names)
    cities = fetch_cities(inst_ids)
    inst_index = {iid: n for n, iid in enumerate(inst_ids)}
    institutions = [{"name": inst_names[i], "city": cities.get(i, "Unknown")} for i in inst_ids]

    papers, texts = [], []
    for w in works:
        title = (w.get("title") or "").strip()
        abstract = rebuild_abstract(w.get("abstract_inverted_index"))
        if not title or not abstract:
            continue
        authors, local = [], []
        for a in w.get("authorships") or []:
            name = (a.get("author") or {}).get("display_name")
            if not name:
                continue
            authors.append(name)
            for inst in a.get("institutions") or []:
                if inst.get("id") in inst_index:
                    local.append([name, inst_index[inst["id"]]])
                    break
        if not local:
            continue
        papers.append({
            "id": w["id"].rsplit("/", 1)[-1],
            "t": title,
            "y": w.get("publication_year") or 0,
            "u": w.get("doi") or w["id"],
            "a": authors[:6],
            "n": len(authors),
            "l": local,
            "ab": abstract[:ABSTRACT_CHARS],
        })
        texts.append(f"{title}. {abstract}")
    print(f"Keeping {len(papers)} papers with local authors and abstracts")

    model_path = hf_hub_download(MODEL_REPO, MODEL_FILE)
    tokenizer_path = hf_hub_download(MODEL_REPO, "tokenizer.json")
    vocab_path = hf_hub_download(MODEL_REPO, "vocab.txt")
    embedder = Embedder(model_path, tokenizer_path)
    vectors = embedder.embed(texts)
    quantised = np.clip(np.round(vectors * 127.0), -127, 127).astype(np.int8)

    quantised.tofile(OUT / "vectors.bin")
    meta = {
        "version": 1,
        "updated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "model": f"{MODEL_REPO}/{MODEL_FILE}",
        "dim": int(vectors.shape[1]),
        "count": len(papers),
        "institutions": institutions,
        "papers": papers,
    }
    (OUT / "papers.json").write_text(json.dumps(meta, ensure_ascii=False, separators=(",", ":")),
                                     encoding="utf-8")
    shutil.copy(model_path, OUT / "model.onnx")
    shutil.copy(vocab_path, OUT / "vocab.txt")
    print(f"Wrote {len(papers)} papers, {len(institutions)} institutions to {OUT}/")


if __name__ == "__main__":
    main()
