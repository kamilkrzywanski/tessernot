# Handoff: how to keep improving ocr-pro

State on 2026-10-13, after the reading-order round. Read [README.md](../README.md),
[internals.md](internals.md) and [benchmark.md](benchmark.md) first; this file is the to-do list
and the working method.

## Ground rules from the owner

- Commits: author `Kamil Krzywanski <kamilkrzywanski01@gmail.com>` (pass `-c user.name=... -c
  user.email=...`; the global git config holds a work address). **No `Co-Authored-By` or any AI
  attribution.** Commit messages in Polish, imperative, like the existing history.
- Talk to the owner in Polish. Push to `origin master` only when asked.
- All benchmark data (page images, PDF/DjVu originals, references) is tracked in the repository on
  purpose. Keep every file under 50 MB.
- `javac` on PATH is Java 8. Use `/usr/lib/jvm/java-21-openjdk-amd64/bin/javac` for ad-hoc
  compiles; Maven picks the right JDK.
- After every change: `mvn -q test` (about 3 minutes, all tests must pass). `QualityGateTest`
  guards the three tuning scans; its Polish-rules gate is pinned to 960 px detection on purpose.

## The benchmark discipline (most important)

Sets and their status, see [benchmark.md](benchmark.md):

| Set | Use |
| --- | --- |
| `dev` (36) | Look at it, debug on it, tune on it. |
| `test` (27) | Spent: its failures drove the last round. Use it as a regression check, like `dev`. |
| `test2` (20) | Scored once. These pages were looked at when reporting and are spent for tuning: Smyk receipt, Scientific American 1850, The Crisis 1910, kalendarz Ungra 1878, London Gazette 1836, NSAM 125 memo. Do not tune on any `test2` page. |

**Before the next release, collect `test3`** (about 20 new documents, half business documents,
half multi-column pages) the same way, score it once at the end, and report it next to `test2`.
Without a fresh set, any number you publish is optimistic.

### Collecting documents

Done by sub-agents, each with its own share, in parallel. What worked:

- Sources: pl/en Wikisource proofread pages (validated or proofread status; the scan is a DjVu
  or PDF on Commons), Wikimedia Commons, EUR-Lex / Publications Office (CC BY 4.0), arXiv papers
  under CC BY or CC0 only. Licenses: public domain, CC0, CC BY, CC BY-SA.
- **No OCR tool may be run** by the collector. Wikisource text and PDF text layers are drafts;
  every line is rebuilt against the image (line breaks and end-of-line hyphenation as printed).
- Reading-order rule for references: see "Transcription rules" in [benchmark.md](benchmark.md).
  Spell out the figure rule (a figure across the columns does not end a column band).
- A shared claims file so two agents never take the same publication; one page per publication.
- Each agent writes its manifest rows as it finishes each document, not at the end. In this round
  two agents were stopped before writing their manifests and the rows had to be rebuilt.
- File names `pl-*` / `en-*`; PDF pages rendered with `pdftoppm -r 200`, DjVu pages with
  `ddjvu -format=pnm` and converted to PNG losslessly; the manifest has 12 tab-separated
  columns, `-` for empty (see `bench/fetch.sh`).

Unfinished checks: the second verification pass was not done for `en-newspaper-sciam-1850-p171`,
`pl-paragon-empik-2025` and `pl-tygodnik-illustrowany-1900-s174` (marked in the manifest notes).
The English dev agent listed spots worth a look in `en-newspaper-bristol-mercury-1832`,
`en-journal-monthly-weather-review-1896-p112` and `en-dict-bryan-painters-v1-p302`.

## Tools

- `ocr.sh score <ref-dir> <image-dir> [--corrections polish] [--limit N] --out dir > x.tsv`:
  per-document CER and word F1.
- `bench/run-tesseract.sh`, `bench/run-rapidocr.py`: baselines (RapidOCR needs
  `pip install rapidocr==3.10.0 onnxruntime==1.31.0`).
- `bench/report.py`: Markdown tables from score files. `bench/chart.py`: the README chart.
- **`bench/tools/ro.sh`: the reading-order workbench.** `dump` runs OCR once per page and caches
  the lines with their boxes; `score` recompiles only `ReadingOrder.java` and scores every cached
  page in seconds; `cuts` lists merged lines it would split; `show <name>` draws a page's boxes
  in reading order. Workflow for any layout change:
  1. `ro.sh dump bench/ref/dev bench/ref/test` (once, about 10 minutes; re-dump after changing
     detection or the engine's cutting);
  2. edit `ReadingOrder.java`, then `ro.sh score bench/ref/dev bench/ref/test` and compare every
     document with the previous run, not just the pooled number;
  3. `ro.sh cuts` must not list new lines that are not merged pairs;
  4. confirm with real OCR (`ocr.sh score`) and `mvn -q test`.

To see why a page goes wrong, add `System.err` prints to a scratch copy of `ReadingOrder.java`
(gutter, sections, the column tests with their shares) and run it through `ro.sh`; most
failures come down to one share just under its threshold.

## What to improve next, in order

1. **False column splits on receipts and memos.** At 2048 px the Smyk receipt (`test2`) had its
   item names pulled out as a "text column" and read before the header (CER 0.166; RapidOCR,
   which only sorts rows, 0.007). This is why RapidOCR scores higher on business documents in
   the `test2` chart: it has no layout analysis, and on single-column documents plain row order
   is right. There is no heuristic to borrow from it. Make the text-column path in
   `ReadingOrder.textColumns` more conservative on narrow pages and on sides whose lines end in
   prices or quantities; reproduce on `dev` receipts first (and collect more receipts for `dev`).
2. **Dense historical newspapers.** Tesseract wins on four narrow columns and complex gazettes
   (`test2`: Scientific American, The Crisis, the 1878 calendar, London Gazette; `dev`: The
   Independent 1898, Bristol Mercury 1832). On The Independent a long date line reaches 49 px
   into a gutter where the limit is 44 px, so the split fails; such per-page thresholds are a
   trap. A more robust direction is a proper column model: find all gutters of a band at once
   from left-edge alignment (tab stops) instead of one emptiest strip at a time.
3. **Detection size.** 2048 px is better on `dev` and `test`; on `test2` 960 px is better on
   Polish pages and worse on English ones. Try a two-pass rule: detect at 960, measure the
   median line height in detector pixels, re-detect larger only for small print. The numbers
   for each page are in `bench/out/*-fast.tsv` and `*-plain.tsv` after a benchmark run.
4. **Speed and memory.** 7 s median on dense pages against Tesseract's 4.6 s, and about 1.5 GB of
   heap at 2048 px. Profile the detector at 2048 px (tiling the page, or float16 activations)
   and recognition on pages with 100+ lines.
5. **Polish corrections at 2048 px.** The rules were measured on 960 px crops; at 2048 px the
   Biedronka receipt has three edits instead of one. Re-check `Corrections.POLISH` on `dev`.
6. **Stamps and handwriting.** References leave them out, but every engine reads them (the 1932
   telephone bill loses most of its CER to a purple stamp). A color filter for stamp ink could
   drop such lines.
7. **Release.** Version is still `1.0-SNAPSHOT`: set 0.1.0, add CI (GitHub Actions running
   `mvn test` with the models), and decide on Maven Central and on hosting the models.

## Things that cost time in this round

- Threshold tweaks for one page broke another page each time. Score the whole cache after every
  change and look at the per-document diff.
- Line-number based `sed` edits on `ReadingOrder.java` hit the wrong lines; use exact-text edits.
- `pkill -f <pattern>` kills the shell that runs it when the pattern is in its own command line.
