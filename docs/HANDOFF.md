# Handoff: how to keep improving ocr-pro

State on 2026-10-14, after the reading-order round and the column-gutter change. Read [README.md](../README.md),
[internals.md](internals.md) and [benchmark.md](benchmark.md) first; this file is the to-do list
and the working method.

## Ground rules from the owner

- Commits: author `Kamil Krzywański <kamilkrzywanski01@gmail.com>` (pass `-c user.name=... -c
  user.email=...`; the global git config holds a work address). **No `Co-Authored-By` or any AI
  attribution.** Commit messages in English, imperative, like the existing history. The code,
  the docs and the commit history are all in English.
- Talk to the owner in Polish. Push to `origin master` only when asked.
- All benchmark data is tracked on purpose. Page images and PDF/DjVu originals live in the
  submodule `bench/data` (its own repository, `ocr-pro-bench`); references and the manifest live
  here. A new image is committed in the submodule first (on its `master`, not a detached HEAD),
  then the submodule pointer here. `bench/data/manifest.tsv` is a copy of `bench/manifest.tsv`
  for readers of the bench repository: copy it over after every manifest change. Keep every file
  under 50 MB.
- The system `java`, `javac` and Maven's JDK may be an older release (8 or 11 on this machine).
  `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` before `mvn` and `ocr.sh`; `ocr.sh` uses
  `$JAVA_HOME/bin/java` when it is set.
- After every change: `mvn -q test` (about 3 minutes, all tests must pass). Surefire caps the test
  JVM at 12 CPUs (`-XX:ActiveProcessorCount=12`): the owner's machine has 24 threads and froze
  under a full run. Run benchmark JVMs with the same flag or `--threads 12`.
- The owner wants a universal tool: no thresholds fitted to the benchmark sets. Prefer one
  mechanism with a parameter that means something (nats, a probability) set before measuring. `QualityGateTest`
  guards the three tuning scans; its Polish-rules gate is pinned to 960 px detection on purpose.
- Modules: `ocr-pro` (the engine, no runtime dependencies), `ocr-pro-pdf` (PDFBox),
  `ocr-pro-models` and `ocr-pro-models-pl` (the files of `models/` as JARs, found through
  `ModelBundle`), `ocr-pro-cli` (the runnable JAR that `ocr.sh` uses). Tests run from the
  repository root, read `models/` there and skip when it is missing: check that the count of
  skipped tests stays 0.

## The benchmark discipline (most important)

Sets and their status, see [benchmark.md](benchmark.md):

| Set | Use |
| --- | --- |
| `dev` (39) | Look at it, debug on it, tune on it. |
| `test` (27) | Spent: its failures drove the last round. Use it as a regression check, like `dev`. |
| `test2` (20) | Scored once. These pages were looked at when reporting and are spent for tuning: Smyk receipt, Scientific American 1850, The Crisis 1910, kalendarz Ungra 1878, London Gazette 1836, NSAM 125 memo. Do not tune on any `test2` page. |
| `test3` (97) | Held out, never scored, checked twice against the images (2026-10-15). Score it once at the end of the next round and report it next to `test2`, with `bench/report.py --manifest`: its headline number is the mean of the three document groups, because 77 of its pages are books, newspapers and gazettes and only 15 are invoices and receipts. |

Business documents are thin in every set. More invoices, receipts, letters and forms can still go
into `test3` before it is scored for the first time, never after.

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
- `bench/report.py`: Markdown tables from score files; `--manifest bench/manifest.tsv` adds the
  CER per document group and their mean. `bench/chart.py`: the README chart. Both take the
  groups from `bench/kinds.py`.
- `bench/tools/tessline.py`: reads the `ro.sh` line cache with Tesseract, for comparing
  recognizers on identical lines (see "Tried and dropped").
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
   Checked on 2026-10-22 with `ro.sh` (auto against `-Drows=true` on every cached page): on `dev`
   and `test` a false column costs three pages and about 60 edits in all (the MSZ 2010 letter,
   36, is the stamp block moving; the PGE invoice 17; the Mennica receipt 8), and column analysis
   wins on 29 pages. There is nothing left to reproduce the memo or Smyk case on, so no rule
   was changed: it would be fitted to `test2`. Collect receipts and memos for `dev` first. The
   remaining order loss on `dev` is single pages: the JFK telegram header (three blocks of small
   print beside a logo, 180 edits) and OJ L91 (176 edits), where the signature block on the right
   sits at the height of the footnotes on the left, so the bottom of the page reads as two columns
   with the footnotes split between them. Two rules were considered for OJ L91 and left out: the
   short footnote rule in the ink as a section boundary (it would also split a newspaper column
   at every ornament between articles, interleaving the columns), and a blank band across the
   whole block as a zone boundary before columns are tried (that is exactly the figure-across-
   the-columns case the transcription rules keep whole).
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
3. **Detection size.** Checked on 2026-10-17 and dropped as a line-height rule: per page, the
   pages where 2048 px beats 960 px are not the ones with small print (thermal receipts with
   25-37 px lines at 960 still need 2048), and a height threshold that keeps the accuracy sends
   two thirds of the pages to a second pass, so the time does not go down. On a few pages 960 px
   is much better (OJ C10 2020 0.028 to 0.002, OJ L91 0.079 to 0.027, about 350 edits on `dev` and
   `test` together). Looked at line by line, these are not one layout problem: on OJ C10 2020 one
   line is read as noise and on Nature 1896 and the Arct dictionary a line is missing (detection),
   on the Unia-Proch notice the detector at 2048 cuts every line into word boxes (121 boxes
   against 31), so a lone `w` reads as `W` and `o` as `0`; only OJ L91 is reading order (its
   two-column footnotes are joined into rows). Word splitting is strong on 4 of 66 pages only,
   and on two of those 960 px is worse anyway. Earlier notes: 2048 px is better on `dev` and `test`; on `test2` the two tie on Polish
   pages (0.031) and 960 px is much worse on English ones (0.310 against 0.107). The numbers
   for each page are in `bench/out/*-fast.tsv` and `*-plain.tsv` after a benchmark run.
   **Two passes merged (2026-10-22, branch `coarse-detection`, not merged):** with `DetMerge`,
   `--coarse 960` and `OcrConfig.detCoarseLimit`,
   the 960 px boxes are folded into the 2048 px ones. What survived measuring on `dev` and
   `test`: a coarse box replaces the fine boxes inside it only when they are three or more word
   pieces, each as high as the coarse box and at most four heights long, together covering half
   its width. Two rules were dropped after a first run: adding a coarse box that overlaps no fine
   box (it brought in margin marks read as CJK on Wisła 1912, 0.024 to 0.81, and seal text on the
   Kuria letter), and merging two pieces (label and value on the Fanzz and Shell receipts; the
   rows then held two boxes instead of four and read as two columns, 0.014 to 0.138). With the
   final rules no page of `dev` or `test` is worse; Unia-Proch 0.031 to 0.018 (960 alone 0.003:
   its two-word lines stay split) and the Mennica receipt 0.470 to 0.238 (a stamp box changed
   and the order fell right, not the mechanism). On `test2`, scored once with it, no page
   changed (0.0786 both ways). Median page time +8 % in the first run; total time +17 % on
   `dev` and +4 % on `test` in a second. Not merged: one page gains by the mechanism, and the
   option would be public API that a user has no way to choose for. The missed lines of OJ C10
   2020, Nature 1896 and the Arct dictionary are not recovered, because the fine pass has a box
   over them that reads wrong, not no box.
   **Box threshold (2026-10-23):** the 1981 notice on `test` loses a whole block of 13 lines
   whose boxes score 0.43–0.49 under `boxThresh` 0.5 (the print is clean; the detector is just
   unsure there). Scored with 0.4 (`bench/out/r2/*-box0.4.tsv`): `test` 0.0461 to 0.0423, the
   notice 0.095 to 0.068 and Nature 1896 0.053 to 0.025, two pages worse (the 1932 bill +16, the
   1954 memo +13); `dev` 0.0230 to 0.0227, three better (Bristol Mercury −69) and five worse
   (the 2017 diploma +17). 0.3 with `detThresh` 0.3 is worse on `dev`. Not adopted: the gain is
   two pages and the losses are noise lines that extra low boxes bring in. Worth trying together
   with `--drop-fragments`, which is what those extra lines mostly are.
4. **Speed and memory.** Done so far: `Alphabet.LATIN` (8–18 % per set, up to 25 % on text-heavy
   pages, same accuracy) and faster `ReduceMean` / `MaxPool` (a few %). The profile is in
   [internals.md](internals.md#performance-notes). Tried and dropped: 6×2 and 4×3 register
   tiles in `VectorKernels.gemm`. With 12 vector accumulators C2 on JDK 21 is 20–25 % slower
   than the 4×2 tile (6×2 only ties on JDK 25); the kernel reaches 70–80 GFLOPS on one P-core,
   about half the AVX2 peak. Batching lines does not raise GEMM speed either: the recognizer's
   1×1 convolutions already have 400–1,300 columns, and the kernel runs as fast at 160. What is
   left is to skip work: two-pass detection, and fewer second reads in the Polish profile.
   About 1 GB of heap at 2048 px (2026-11-14: arena size classes, sessions trimmed after every
   run and call, heap budgets for detection and for pages in flight; see internals.md); a two-pass
   detection would also save time on large print.
5. **Word lists (2026-10-17).** `CtcWords` (see internals.md) replaced the fixed lexicon swaps
   and takes lists in any language. What it cannot do: tell two listed words apart (Spółka /
   Spotka; it has no context), split glued words (`imagazyn`), or keep a historical spelling the
   lists lack (Volumina Legum prints `Krola`, the search writes `Króla`: give such documents a
   list of their own). Next steps: a word-frequency or bigram prior for ties, letting the search
   insert a space, and scoring `test2` with lists and the polish profile once.
   **2026-10-23:** scored on all three sets, see corrections.md and benchmark.md: Polish CER
   falls on each (`test2` 0.031 to 0.028, word F1 0.912 to 0.932) and no page loses more than
   the Volumina Legum spelling case. `Images.mainInkBand` now splits the crop's luma into ink
   and paper with Otsu instead of a fixed 150: the parking ticket keeps its faint digits (29
   edits to 19) and the 1935 MSZ letter loses 3; nothing else on `dev` or `test` moved. Leaving
   the letter swaps of rule 1 to the search when the Polish lexicon is loaded was tried and
   dropped: five pages worse by one or two edits, none better, word F1 0.964 to 0.963 on `dev`.
   The search traced with `-Docr.ctcwords.trace=true` shows the floor at work:
   a swap that costs exactly 3 nats had no evidence in the image; on the 1914 gazette those are
   `osiagnięty` (right to fix) and `zdala` (a period spelling, wrong to fix) alike.
6. **Stamps and handwriting.** References leave them out, but every engine reads them (the 1932
   telephone bill loses most of its CER to a purple stamp). A color filter for stamp ink could
   drop such lines.
7. **Release.** The build is ready: `mvn -Prelease deploy` makes the sources and javadoc JARs,
   signs with GPG and uploads to the Maven Central Portal (server id `central` in settings.xml,
   a portal user token); the deployment then waits in the portal to be published by hand.
   `ocr-pro-cli` is not uploaded: attach `ocr-pro-cli/target/ocr-pro-cli.jar` to a GitHub
   release. CI (`.github/workflows/build.yml`) runs `mvn verify` and fails on a skipped test.
   Left to the owner: the version (`mvn versions:set -DnewVersion=0.1.0`), making the
   repository public, the `io.github.kamilkrzywanski` namespace on central.sonatype.com, the
   portal token, and a GPG key on a public key server. The models ship as `ocr-pro-models`
   (27 MB) and `ocr-pro-models-pl` (12 MB), so no separate hosting is needed;
   `ModelFiles.ensure` still downloads from ModelScope for those who leave the JARs out. Release
   all five modules together with one version: a bundled default model must match the SHA-256
   in `ModelFiles`, so a bundle from another release fails at load time.

## Tried and dropped

- **A margin around the line crop** (2026-10-17). The crop grown by a share of the box height
  above and below, taken from the page: `dev` CER 0.0230 with none, 0.0254 at 0.05, 0.0316 at
  0.1, 0.0871 at 0.2 (at 0.05 seven pages better, eighteen worse). The recognizer wants the tight
  detector box; the old note from the three scans holds in general.
- **Fusing several reads of a line** (2026-10-17). Each line read as it is, with a band of
  background above and below, and stretched 1.2 times; the greedy text of each variant was a
  candidate and the one with the highest summed CTC log probability over the three won (the
  variants have different timestep counts, so steps cannot be averaged). `dev`: word F1 +0.16
  points at three times the recognition cost, 92 lines fixed and 59 broken, mostly spaces and
  punctuation. `test`: CER 0.046 to 0.049, worse on 11 pages. Errors are only moderately
  concentrated in unsure lines (the least sure 20 % of lines hold 56 % of wrong words), so
  reading only unsure lines again would not change that.

- **Tesseract's LSTM as the line recognizer** (2026-10-15). `bench/tools/tessline.py` crops every
  line box from the `ro.sh` cache the way the engine does, reads it with `tesseract --psm 7`
  (`pol` or `eng`) and keeps ocr-pro's reading order, so only recognition differs. Word F1 on
  `test` / `dev`: ocr-pro 0.930 / 0.956, Tesseract `tessdata_best` 0.870 / 0.884,
  `tessdata_fast` (the Debian packages) 0.866 / 0.881. ocr-pro wins 26 of 27 English pages
  (one tie) and 24 of 36 Polish ones; Tesseract wins 8 Polish pages, mostly on Polish marks in
  capitals and typescript (ŁOWISKA, mogą, kąpieli), and partly by returning nothing for stamps
  that the references leave out. Turning its dictionaries off changed no output, so the edge on
  Polish marks comes from its network. `tessdata_best` costs about 11 s of CPU per page for the
  lines alone. Porting the LSTM (own file format, LSTM layers, int8 weights, Tesseract's line
  normalization) is not worth it; Polish marks are the job of `Corrections.POLISH`.
- **int8 GEMM** (2026-10-15). A one-core microbenchmark on the i7-1365U (AVX2 and AVX-VNNI),
  same shapes as the recognizer's 1×1 convolutions, against `VectorKernels.gemmPacked` at 15–18
  GMAC/s: the Vector API with int8 or int16 operands reaches 4–6, because it has no widening
  multiply-add (`vpmaddwd`, `vpdpbusd`), so each pair of products needs a multiply, three shifts
  and two adds where floats need one FMA. C2 does emit `vpmaddwd` for the scalar pattern
  `out[j] = a[2j]*b[2j] + a[2j+1]*b[2j+1]` (11 GMAC/s on a streaming loop), but only when both
  operands vary with `j`; a GEMM needs one of them broadcast, or a dot-product reduction, and
  neither is vectorized (0.5–2.7 GMAC/s). JDK 25 and 26 give the same numbers. Without native
  code, int8 is slower than float here; revisit only if the Vector API gains a dot-product
  operation.

## Things that cost time in this round

- Threshold tweaks for one page broke another page each time. Score the whole cache after every
  change and look at the per-document diff.
- Line-number based `sed` edits on `ReadingOrder.java` hit the wrong lines; use exact-text edits.
- `pkill -f <pattern>` kills the shell that runs it when the pattern is in its own command line.
