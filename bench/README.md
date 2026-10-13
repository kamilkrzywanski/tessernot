# Benchmark

These are public documents with references transcribed by hand. Read
[docs/benchmark.md](../docs/benchmark.md) for the rules, the results and how to reproduce them.

- `manifest.tsv`: source URL, SHA-256, license and author of every document, and its set:
  - `dev` (20 documents): used to find and fix problems. Numbers on it are no longer independent.
  - `test` (27 documents): scored once per release. Do not tune on it.
- `ref/dev/`, `ref/test/`: reference transcriptions (`pl-*`, `en-*`)
- `data/`: the page images (about 75 MB). Licenses and authors are in `manifest.tsv`.
- `fetch.sh`: rebuilds a missing image from its source and checks SHA-256. Pages of PDF and DjVu
  sources are rendered to PNG with `pdftoppm` (poppler-utils) and `ddjvu` (djvulibre). The
  originals it downloads stay git-ignored.
- `run-rapidocr.py`, `run-tesseract.sh`: baselines that write outputs for `ocr score --hyp`
