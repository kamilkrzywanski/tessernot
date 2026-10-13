# Held-out benchmark

These are documents the engine was not tuned on, with hand-made references. Read
[docs/benchmark.md](../docs/benchmark.md) for the rules, the results and how to reproduce them.

- `manifest.tsv`: source URL, SHA-256, license and author of every image
- `ref/`: reference transcriptions (`pl-*`, `en-*`)
- `fetch.sh`: downloads the images into `data/` (git-ignored)
- `run-rapidocr.py`, `run-tesseract.sh`: baselines that write outputs for `ocr score --hyp`
