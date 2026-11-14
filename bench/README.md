# Benchmark

These are public documents with references transcribed by hand. Read
[docs/benchmark.md](../docs/benchmark.md) for the rules, the results and how to reproduce them.

- `manifest.tsv`: source URL, SHA-256, license and author of every document, and its set:
  - `dev` (70 documents): used to find and fix problems. Numbers on it are not independent.
  - `test` (27 documents): scored once in the previous round, then used to find the layout
    problems of this one. No longer independent.
  - `test2` (20 documents): scored at the end of this round and again after a change built on
    `dev` only. Do not tune on it.
  - `test3` (97 documents: 47 PL, 50 EN): held out. Not scored. Do not tune on it. Report it
    with `report.py --manifest`: the mean over document groups is its headline number.
- `ref/dev/`, `ref/test/`, `ref/test2/`, `ref/test3/`: reference transcriptions (`pl-*`, `en-*`)
- `data/`: the page images (about 350 MB) and the PDF and DjVu originals of rendered pages (about
  860 MB), kept in their own repository [ocr-pro-bench](https://github.com/kamilkrzywanski/ocr-pro-bench)
  and mounted here as a submodule: `git submodule update --init bench/data`. Licenses and authors
  are in `manifest.tsv`.
- `fetch.sh`: rebuilds a missing image from its source and checks SHA-256. Pages of PDF and DjVu
  sources are rendered to PNG with `pdftoppm` (poppler-utils) and `ddjvu` (djvulibre). A source
  that is missing is downloaded again.
- `COLLECTING.md`: how to find, license-check, render and transcribe new documents, with a prompt
  for collecting agents
- `run-rapidocr.py`, `run-tesseract.sh`: baselines that write outputs for `ocr score --hyp`
- `report.py`, `chart.py`: the tables and the chart of docs/benchmark.md from `ocr score` outputs;
  `kinds.py` holds the document groups both use

## Licenses

The documents in `data/` and their references in `ref/` are not under the project's Apache
License. Each keeps the license of its source, given with its author and URL in `manifest.tsv`:
public domain, [CC0](https://creativecommons.org/publicdomain/zero/1.0/),
[CC BY 3.0](https://creativecommons.org/licenses/by/3.0/),
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/),
[CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/) or
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). A reference transcription has
the license of its page. Images downloaded as images are unchanged (`fetch.sh` checks their
SHA-256 against the source); pages of PDF and DjVu sources are renders of one page, as the
`page` column says.
