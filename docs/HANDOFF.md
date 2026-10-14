# Handoff: how to keep improving ocr-pro

State on 2026-10-14, after the reading-order round and the column-gutter change. Read [README.md](../README.md),
[internals.md](internals.md) and [benchmark.md](benchmark.md) first; this file is the to-do list
and the working method.

## Ground rules from the owner

- Commits: author `Kamil Krzywanski <kamilkrzywanski01@gmail.com>` (pass `-c user.name=... -c
  user.email=...`; the global git config holds a work address). **No `Co-Authored-By` or any AI
  attribution.** Commit messages in Polish, imperative, like the existing history.
- Talk to the owner in Polish. Push to `origin master` only when asked.
- All benchmark data (page images, PDF/DjVu originals, references) is tracked in the repository on
  purpose. Keep every file under 50 MB.
- The system `java`, `javac` and Maven's JDK may be an older release (8 or 11 on this machine).
  `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` before `mvn` and `ocr.sh`; `ocr.sh` uses
  `$JAVA_HOME/bin/java` when it is set.
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

The full procedure and a prompt template are in [bench/COLLECTING.md](../bench/COLLECTING.md).
In short, done by sub-agents, each with its own share, in parallel. What worked:

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

1. **False column splits on receipts and memos.** Partly done: a side made mostly of numbers is
   never a column now (the Castorama totals on `test`, 0.061 to 0.020), and `Layout.ROWS`
   (`--layout rows`) turns the analysis off for one-column streams. Still open: the Smyk case
   itself is a `test2` page, so its fix cannot be confirmed there. At 2048 px the Smyk receipt (`test2`) had its
   item names pulled out as a "text column" and read before the header (CER 0.166; RapidOCR,
   which only sorts rows, 0.007). This is why RapidOCR scores higher on business documents in
   the `test2` chart: it has no layout analysis, and on single-column documents plain row order
   is right. There is no heuristic to borrow from it. Make the text-column path in
   `ReadingOrder.textColumns` more conservative on narrow pages and on sides whose lines end in
   prices or quantities; reproduce on `dev` receipts first (and collect more receipts for `dev`).
2. **Dense historical newspapers.** Mostly done. `InkGutters` finds column gutters in the page
   ink (blank strips inside boxes that line up next to column margins) and the engine cuts
   merged lines there, and `ReadingOrder.stacked` keeps lines of a curved photo out of one row.
   Built on `dev` (Wisła 1912 0.528 to 0.024, The Independent 1898 0.754 to 0.009, Bristol
   Mercury 0.290 to 0.075); `test2` then went from 0.122 to 0.079 pooled (Tesseract 0.070) with
   no page worse. Still open, all on spent `test2` pages, so reproduce on `dev` first: The Crisis
   1910 (0.207, Tesseract 0.011), the London Gazette 1836 (0.645, Tesseract 0.504), the NSAM 125
   memo (0.106, Tesseract 0.006), the Annales article (0.060, Tesseract 0.001). The thresholds
   of `InkGutters` (margin counts 10/10, 5/5 with four gaps, 10/2 with ten gaps) were the
   narrowest that kept the EU Official Journal and the receipts whole; a looser first version
   cut justified text and broke them, so score all of `dev` and `test` after any change there.
   Ink cuts run inside the engine: after changing them, re-dump the `ro.sh` cache.
3. **Detection size.** 2048 px is better on `dev` and `test`; on `test2` 960 px is better on
   Polish pages and worse on English ones. Try a two-pass rule: detect at 960, measure the
   median line height in detector pixels, re-detect larger only for small print. The numbers
   for each page are in `bench/out/*-fast.tsv` and `*-plain.tsv` after a benchmark run.
4. **Speed and memory.** Done so far: `Alphabet.LATIN` (15–33 % on text-heavy pages, same
   accuracy) and faster `ReduceMean` / `MaxPool` (a few %). The profile is in
   [internals.md](internals.md#performance-notes). Tried and dropped: 6×2 and 4×3 register
   tiles in `VectorKernels.gemm`. With 12 vector accumulators C2 on JDK 21 is 20–25 % slower
   than the 4×2 tile (6×2 only ties on JDK 25); the kernel reaches 70–80 GFLOPS on one P-core,
   about half the AVX2 peak. Batching lines does not raise GEMM speed either: the recognizer's
   1×1 convolutions already have 400–1,300 columns, and the kernel runs as fast at 160. What is
   left is to skip work: two-pass detection, and fewer second reads in the Polish profile.
   About 1.5 GB of heap at 2048 px; a two-pass detection would also save time on large print.
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
