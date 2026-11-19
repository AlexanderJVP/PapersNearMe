# Papers Near Me

Find machine-learning papers written by researchers near you (Antwerp, Ghent, Leuven…)
by describing what *you* are working on. Matching runs on the phone, so your description
never leaves it.

## How it works

```
GitHub Actions (every night, free)          Android app
──────────────────────────────────          ─────────────────────────────────
OpenAlex API → Belgian AI/CV papers          downloads papers.json + vectors.bin
  → embed title+abstract (MiniLM, ONNX)      embeds your description on-device
  → publish to release "index-latest":       (same model.onnx + vocab.txt)
     papers.json, vectors.bin,               → cosine similarity → closest papers
     model.onnx, vocab.txt                   → filter by city, see local authors
```

No server, no database, no cost.

## Setup (one time)

1. **Create a public GitHub repo** called `PapersNearMe` and push this folder to it
   (Android Studio: *VCS → Share Project on GitHub*).
2. **Add your email for OpenAlex** (optional, but gives faster API access):
   repo *Settings → Secrets and variables → Actions → Variables* → new variable
   `OPENALEX_MAILTO` = your email.
3. **Run the pipeline once by hand**: *Actions → Nightly paper index → Run workflow*.
   After a few minutes a release called `index-latest` appears with 4 files.
4. **Point the app at it**: in `gradle.properties`, replace `YOUR_GITHUB_USERNAME`.
5. **Run the app** from Android Studio. First launch downloads the ~23 MB model once.

## Tuning

Environment variables for `pipeline/build_index.py` (set them in the workflow's `env:`):

| Variable     | Default      | Meaning                                              |
|--------------|--------------|------------------------------------------------------|
| `COUNTRY`    | `BE`         | Country of the local authors                         |
| `SUBFIELDS`  | `1702\|1707` | OpenAlex subfields: AI, Computer Vision              |
| `SINCE`      | `2024-01-01` | Oldest publication date                              |
| `MAX_PAPERS` | `20000`      | Safety cap                                           |

## Notes

- Only metadata, a short abstract excerpt and links are stored; full papers stay with the publisher.
- GitHub pauses scheduled workflows after 60 days without repo activity. Push a commit
  or click *Run workflow* now and then to keep it alive.
- Python and phone must use the **same** model file; the pipeline publishes it, so they always match.

## Next ideas

- Let researchers claim their profile and describe their current work
- Only download papers added since the last sync
- Sort by distance using the phone's location
