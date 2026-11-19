# Belgian Papers

Find papers written by researchers near you (Antwerp, Ghent, Leuven…), in any field,
by describing what *you* are working on. Matching runs on the phone, so your description
never leaves it.

## How it works

```
GitHub Actions (every night, free)            Android app
──────────────────────────────────            ─────────────────────────────────
OpenAlex API → Belgian papers, last 5 years,   you pick your field(s) of work
  all fields (~200k)                           → downloads only those fields
  → embed title+abstract (MiniLM, ONNX),       embeds your description on-device
    reusing last night's embeddings            (same model.onnx + vocab.txt)
  → one file set per field, published to       → cosine similarity → closest papers
    release "index-latest": manifest.json,     → filter by city, see local authors
    papers-f<ID>.jsonl.gz, vectors-f<ID>.bin,
    model.onnx, vocab.txt
```

No server, no database, no cost.

## Setup (one time)

1. **Create a public GitHub repo** called `PapersNearMe` and push this folder to it
   (Android Studio: *VCS → Share Project on GitHub*).
2. **Add your email for OpenAlex** (optional, but gives faster API access):
   repo *Settings → Secrets and variables → Actions → Variables* → new variable
   `OPENALEX_MAILTO` = your email.
   Optionally also add a free OpenAlex API key (https://openalex.org/settings/api) as a
   repository *secret* `OPENALEX_API_KEY`: it gives the job its own daily budget
   (without it, results are limited to 10,000 papers).
3. **Run the pipeline once by hand**: *Actions → Nightly paper index → Run workflow*.
   After a few minutes a release called `index-latest` appears with 4 files.
4. **Point the app at it**: in `gradle.properties`, replace `YOUR_GITHUB_USERNAME`.
5. **Run the app** from Android Studio. First launch downloads the ~23 MB model once.

## Tuning

Environment variables for `pipeline/build_index.py` (set them in the workflow's `env:`):

| Variable      | Default        | Meaning                                                  |
|---------------|----------------|----------------------------------------------------------|
| `COUNTRY`     | `BE`           | Country of the local authors                             |
| `SINCE`       | 5 years ago    | Oldest publication date (`YYYY-MM-DD`)                   |
| `SUBFIELDS`   | *(all)*        | Optional OpenAlex subfield filter, e.g. `1702\|1707`     |
| `MAX_AUTHORS` | `30`           | Skip papers with more authors (large consortia)          |
| `MAX_PAPERS`  | `400000`       | Safety cap                                               |

## Notes

- Only metadata, a short abstract excerpt and links are stored; full papers stay with the publisher.
- GitHub pauses scheduled workflows after 60 days without repo activity. Push a commit
  or click *Run workflow* now and then to keep it alive.
- Python and phone must use the **same** model file; the pipeline publishes it, so they always match.
- The app refreshes downloaded fields automatically only on Wi-Fi; the *Refresh* button always works.
- The first pipeline run embeds everything (~1–2 h); later runs reuse the previous release's
  embeddings and only embed new papers.
- The embedding model is English-only: Dutch/French abstracts match less well.

## Next ideas

- Let researchers claim their profile and describe their current work
- Only download papers added since the last sync (instead of whole fields)
- Sort by distance using the phone's location
