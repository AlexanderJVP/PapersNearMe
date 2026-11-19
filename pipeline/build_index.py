"""Nightly job: fetch papers with Belgian authors from OpenAlex, embed them, write dist/.

The index is split per OpenAlex field (Social Sciences, Medicine, Computer Science, ...)
so the phone only downloads the fields its user picks.

Outputs (published to the GitHub release `index-latest`):
  manifest.json          - list of fields with paper count, size and checksum
  papers-f<ID>.jsonl.gz  - one JSON paper per line (same order as the vectors)
  vectors-f<ID>.bin      - N x 384 int8 values (normalised vector * 127)
  model.onnx             - the embedding model the phone must use
  vocab.txt              - its WordPiece vocabulary

Embeddings from the previous release (PREV_DIR) are reused, so each night only
new papers are embedded.
"""
import datetime as dt
import gzip
import hashlib
import io
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
MODEL_KEY = f"{MODEL_REPO}/{MODEL_FILE}"
EMBED_VERSION = 1  # bump when the embedded text changes, to invalidate the cache

OUT = pathlib.Path(os.environ.get("OUT_DIR", "dist"))
PREV = pathlib.Path(os.environ.get("PREV_DIR", "prev"))
MAILTO = os.environ.get("OPENALEX_MAILTO", "").strip()
# Optional free key (https://openalex.org/settings/api): own daily budget + cursor paging.
API_KEY = os.environ.get("OPENALEX_API_KEY", "").strip()
COUNTRY = os.environ.get("COUNTRY", "BE")
# Optional OpenAlex subfield filter, e.g. "1702|1707" (AI, Computer Vision). Empty = all fields.
SUBFIELDS = os.environ.get("SUBFIELDS", "").strip()
_five_years_ago = dt.date.today().replace(year=dt.date.today().year - 5)
SINCE = os.environ.get("SINCE", "").strip() or _five_years_ago.isoformat()
# Papers with huge author lists (physics/medical consortia) are skipped: they are
# heavy to download and rarely help you find one local researcher.
MAX_AUTHORS = int(os.environ.get("MAX_AUTHORS", "30"))
MAX_PAPERS = int(os.environ.get("MAX_PAPERS", "400000"))
ABSTRACT_CHARS = 400   # stored for display
EMBED_CHARS = 2000     # title + abstract fed to the model (~256 tokens)

session = requests.Session()
session.headers["User-Agent"] = f"BelgianPapers/0.2 (mailto:{MAILTO or 'unknown'})"


def get(path: str, params: dict) -> dict:
    params = dict(params)
    if MAILTO:
        params["mailto"] = MAILTO  # puts us in OpenAlex's faster "polite pool"
    if API_KEY:
        params["api_key"] = API_KEY
    for attempt in range(6):
        try:
            r = session.get(f"{API}{path}", params=params, timeout=120)
        except requests.RequestException as e:
            print(f"Request failed ({e}), retrying", file=sys.stderr)
            time.sleep(2 ** attempt)
            continue
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


def iter_works():
    filters = [
        f"authorships.institutions.country_code:{COUNTRY}",
        f"from_publication_date:{SINCE}",
        "has_abstract:true",
        f"authors_count:<{MAX_AUTHORS + 1}",
    ]
    if SUBFIELDS:
        filters.append(f"primary_topic.subfield.id:{SUBFIELDS}")
    base = {
        "filter": ",".join(filters),
        "select": "id,doi,title,publication_year,authorships,abstract_inverted_index,primary_topic",
        "per-page": 200,
        "sort": "publication_date:desc",
    }
    seen = 0
    if API_KEY:
        # Cursor paging (needs a key) has no result limit.
        cursor = "*"
        while cursor and seen < MAX_PAPERS:
            data = get("/works", {**base, "cursor": cursor})
            if cursor == "*":
                print(f"OpenAlex reports {data['meta']['count']} matching works since {SINCE}")
            yield from data["results"]
            seen += len(data["results"])
            cursor = data["meta"].get("next_cursor")
            if not data["results"]:
                break
            if (seen // 200) % 50 == 0:
                print(f"  fetched {seen}")
    else:
        # Without a key only basic paging is allowed: max 10,000 results (50 pages of 200).
        page = 1
        while seen < min(MAX_PAPERS, 10_000):
            data = get("/works", {**base, "page": page})
            if page == 1:
                count = data["meta"]["count"]
                print(f"OpenAlex reports {count} matching works since {SINCE}")
                if count > 10_000:
                    print("WARNING: no OPENALEX_API_KEY, only the newest 10,000 are fetched")
            yield from data["results"]
            seen += len(data["results"])
            if len(data["results"]) < 200:
                break
            page += 1


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


def load_cache() -> dict[str, bytes]:
    """id -> int8 vector bytes from the previous release, if it used the same model."""
    manifest_path = PREV / "manifest.json"
    if not manifest_path.exists():
        print("No previous index, embedding everything")
        return {}
    m = json.loads(manifest_path.read_text(encoding="utf-8"))
    if m.get("model") != MODEL_KEY or m.get("embed") != EMBED_VERSION:
        print("Previous index used another model, embedding everything")
        return {}
    dim, cache = m["dim"], {}
    for f in m["fields"]:
        papers_path, vectors_path = PREV / f["papers"], PREV / f["vectors"]
        if not papers_path.exists() or not vectors_path.exists():
            continue
        vectors = vectors_path.read_bytes()
        with gzip.open(papers_path, "rt", encoding="utf-8") as fh:
            for row, line in enumerate(fh):
                if (row + 1) * dim <= len(vectors):
                    cache[json.loads(line)["id"]] = vectors[row * dim:(row + 1) * dim]
    print(f"Reusing {len(cache)} cached embeddings")
    return cache


def gzip_bytes(data: bytes) -> bytes:
    buf = io.BytesIO()
    # mtime=0 and no filename: unchanged fields produce identical files (and checksums).
    with gzip.GzipFile(filename="", fileobj=buf, mode="wb", compresslevel=9, mtime=0) as gz:
        gz.write(data)
    return buf.getvalue()


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    cache = load_cache()

    # Stream works into compact records so 200k raw OpenAlex works never sit in memory.
    records, texts = [], {}          # texts: record index -> text, only for uncached papers
    fields: dict[str, str] = {}      # field id -> name
    inst_names: dict[str, str] = {}  # local institution id -> name
    for w in iter_works():
        topic = w.get("primary_topic") or {}
        field = topic.get("field") or {}
        fid = (field.get("id") or "").rsplit("/", 1)[-1]
        title = (w.get("title") or "").strip()
        abstract = rebuild_abstract(w.get("abstract_inverted_index"))
        if not fid or not title or not abstract:
            continue
        authors, local = [], []
        for a in w.get("authorships") or []:
            name = (a.get("author") or {}).get("display_name")
            if not name:
                continue
            authors.append(name)
            for inst in a.get("institutions") or []:
                if inst.get("country_code") == COUNTRY and inst.get("id"):
                    inst_names[inst["id"]] = inst.get("display_name") or "Unknown"
                    local.append((name, inst["id"]))
                    break
        if not local:
            continue
        pid = w["id"].rsplit("/", 1)[-1]
        fields[fid] = field.get("display_name") or fid
        if pid not in cache:
            texts[len(records)] = f"{title}. {abstract}"[:EMBED_CHARS]
        records.append({
            "f": fid,
            "id": pid,
            "t": title,
            "y": w.get("publication_year") or 0,
            "u": w.get("doi") or w["id"],
            "s": (topic.get("subfield") or {}).get("display_name") or "",
            "a": authors[:6],
            "n": len(authors),
            "l": local,
            "ab": abstract[:ABSTRACT_CHARS],
        })
    print(f"Keeping {len(records)} papers with local authors and abstracts "
          f"({len(texts)} need a new embedding)")

    cities = fetch_cities(sorted(inst_names))

    model_path = hf_hub_download(MODEL_REPO, MODEL_FILE)
    tokenizer_path = hf_hub_download(MODEL_REPO, "tokenizer.json")
    vocab_path = hf_hub_download(MODEL_REPO, "vocab.txt")
    dim = 384
    vectors: list[bytes | None] = [cache.get(r["id"]) for r in records]
    if texts:
        embedder = Embedder(model_path, tokenizer_path)
        # Similar lengths per batch = less padding = much faster.
        order = sorted(texts, key=lambda i: len(texts[i]))
        start = time.time()
        for b in range(0, len(order), 2048):
            chunk = order[b:b + 2048]
            emb = embedder.embed([texts[i] for i in chunk])
            dim = emb.shape[1]
            q = np.clip(np.round(emb * 127.0), -127, 127).astype(np.int8)
            for i, row in zip(chunk, q):
                vectors[i] = row.tobytes()
            print(f"  embedded {b + len(chunk)}/{len(order)} ({time.time() - start:.0f}s)")
    elif records:
        dim = len(vectors[0])

    # One shard per field, newest papers first (the fetch order).
    by_field: dict[str, list[int]] = {}
    for i, r in enumerate(records):
        by_field.setdefault(r["f"], []).append(i)

    manifest_fields = []
    for fid, rows in sorted(by_field.items(), key=lambda kv: -len(kv[1])):
        lines = []
        for i in rows:
            r = dict(records[i])
            del r["f"]
            r["l"] = [[name, inst_names[iid], cities.get(iid, "Unknown")] for name, iid in r["l"]]
            lines.append(json.dumps(r, ensure_ascii=False, separators=(",", ":")))
        papers_gz = gzip_bytes(("\n".join(lines) + "\n").encode("utf-8"))
        vec_bytes = b"".join(vectors[i] for i in rows)
        papers_name, vectors_name = f"papers-f{fid}.jsonl.gz", f"vectors-f{fid}.bin"
        (OUT / papers_name).write_bytes(papers_gz)
        (OUT / vectors_name).write_bytes(vec_bytes)
        manifest_fields.append({
            "id": fid,
            "name": fields[fid],
            "count": len(rows),
            "bytes": len(papers_gz) + len(vec_bytes),
            "papers": papers_name,
            "vectors": vectors_name,
            "sha256": hashlib.sha256(papers_gz + vec_bytes).hexdigest(),
        })

    manifest = {
        "version": 2,
        "updated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "since": SINCE,
        "country": COUNTRY,
        "model": MODEL_KEY,
        "embed": EMBED_VERSION,
        "dim": dim,
        "count": len(records),
        "fields": manifest_fields,
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1),
                                       encoding="utf-8")
    shutil.copy(model_path, OUT / "model.onnx")
    shutil.copy(vocab_path, OUT / "vocab.txt")
    total_mb = sum(f["bytes"] for f in manifest_fields) / 1e6
    print(f"Wrote {len(records)} papers in {len(manifest_fields)} fields ({total_mb:.0f} MB) to {OUT}/")


if __name__ == "__main__":
    main()
