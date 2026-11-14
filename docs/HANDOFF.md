# Handoff: how to keep improving ocr-pro

State on 2026-10-14, after the reading-order round and the column-gutter change. Read [README.md](../README.md),
[internals.md](internals.md) and [benchmark.md](benchmark.md) first; this file is the to-do list
and the working method.

## Review round, 2026-11-14

A code review of the whole project and the fixes that followed. None of it changes what a page
reads as: the text of every `dev` and `test` page is byte for byte what it was, in both profiles
(`ocr.sh score ... --out`, compared with `diff -r`).

- **Memory.** A multi-page PDF on many threads ran out of memory (twelve A4 pages, twelve
  threads, 10 GB heap), and an idle engine kept 1.5 to 1.9 GB. Now: arena size classes, sessions
  trimmed after every run and call, and `MemoryBudget` shares of the heap for detection and for
  the pages in flight. The same PDF reads with a 1 GB heap; an idle engine keeps about 180 MB on
  twelve threads. See internals.md, "Memory" and "Heap budgets".
- **`OcrPool`** shares one `ModelSet` between its engines (114 MB instead of 453 MB for four
  engines with the Polish lists) and divides the configured thread count between them.
- **Fixed:** `ocr.sh download <dir>` and `ocr.sh score --hyp`; a JPEG with data behind the
  picture rejected as two images; image reading that needed a writable temp directory; the CTC
  runner-up decided per timestep (a letter on two timesteps could read twice) and scored with
  the runner-up's probability (a correction could drop its line); the postal city spelling
  changing the case; a bias folded over the wrong axis; packed `int64_data` of n×8 bytes read as
  fixed values; an unsorted lexicon file finding nothing without a word.
- **Tests.** `OperatorFuzzTest` compares random operators with plain references on an arena that
  hands out NaN. It found the AveragePool, Transpose, Slice, ReduceMean and ConvTranspose edges
  fixed in the same commit. `classifierSmokeOnZeros` had no `@Test` and never ran.
- **Speed.** Dense convolutions on one thread run as im2col + GEMM (a 640 px line: 341 to 302 ms
  median on one core), softmax has its own float `exp` with the same bits on both paths, gray and
  ABGR images are read from the raster, and an EXIF turn moves bytes (12 MP: 1860 to 164 ms).
  Median page times of `ocr.sh score` before and after: `dev` 9.4 to 6.3 s plain and 9.1 to 7.7 s
  with the Polish profile, `test` 7.5 to 6.7 s with it and 7.9 to 8.0 s plain. The machine was
  busy with other work on the day (load 25 on 12 threads), so take the direction, not the size.
- **Structure.** `ReadingOrder` is split into the classes of its steps (internals.md, "Reading
  order"), the second reads of the Polish profile are `PolishRules`, the runner-up table is
  `RunnerUps`, and model loading is `ModelSet`. `ro.sh` compiles the split sources.
- **API.** `DetLimit` is an enum, `TextLine.quad()` and `bounds()` hand out copies (and
  `left()`, `top()`, `right()`, `bottom()` read the extent), `opDebug` is gone (use
  `-Docr.trace=true`) and `quantize` is package-private.

Done after the review, on the same day. Unlike the list above, the first three change what some
pages read as:

- **`CtcWords` mark factor** is paid once per letter, where the letter starts. It was paid on
  every timestep, so a letter on two timesteps cost 6 nats and fell outside the 4-nat margin.
  With the Polish profile two words changed in the 66 pages of `dev` and `test`: `lądu` became
  `ładu` (right) and `rybacze` became `rybaczę` (wrong, the old adjective is in no list). The
  pooled CER did not move.
- **`CtcWords` lowercase** is letter by letter. `String.toLowerCase` turned the prefix `ΟΣ` of
  `ΟΣΟΙ` into `ος`, which starts no word, and `İ` into two characters. A capital I is also tried
  as the dotless ı. No bundled list has such words; the tests use small lists.
- **Gray files** are read with their samples as stored, see "Reading an image" in internals.md
  for the measurement that decided it. The four gray pages of `dev` and `test` moved by five
  edits in all (`test` 0.0461 to 0.0462 plain, 0.0437 to 0.0438 with the Polish profile).
  A contrast step for faded newsprint was tried after it and dropped, see "Tried and dropped".
- **`OcrPoolTest`** holds the borrowed engine until `close()` is parked on the pool's condition;
  with the wait in `close()` removed the test fails.
- **Before the first release:** the version is `0.1.0-SNAPSHOT`; every public class, method and
  record component has Javadoc (`javadoc -Xdoclint:all` is silent on the public packages of all
  five modules); `TextTable.bounds()` hands out a copy, as `TextLine.bounds()` does; `./mvnw`
  brings Maven 3.9.15 and the enforcer plugin stops a build on a JDK below 21 or a Maven below
  3.9 with a sentence. Run on this day by hand, not in CI: the whole suite on JDK 25 (265 tests
  pass) and on JDK 21 without `--add-modules jdk.incubator.vector` (all pass, 11 of `RuntimeTest`
  skip themselves), and `mvn -Prelease -Dgpg.skip package`.

- **A box that runs into the next box of its row** is cut there before `ColumnGutter` asks
  whether it crosses the gutter. Found on a gray copy of the 1923 Bible page (`dev`): the same
  boxes to a few pixels, CER 0.007 in color and 0.286 in gray, because a short reference at the
  end of the left column was drawn 24 px longer, lay over the 4 px gutter and ended both
  columns like a title. Now 28 edits against 27. The order of the 63 cached pages did not move.
  The other page that fell apart in gray, the 2017 diploma (16 to 85 edits), is not a bug: the
  blue seal and the signature stand out in color and read as text in gray.
- **Searchable PDF** (`OcrPdf.searchable`, `--searchable out.pdf` on `ocr` and `pdf`): the
  source with the text of every line under it, see "Modules and model files" in internals.md.
  Checked with PDFBox and `pdftotext`; not opened in Acrobat, Preview or a browser.
- **Tables without rules** (`UnruledTables`, `TextTable.ruled()`): rows set in three or more
  columns whose cells share an edge, see "Tables" in internals.md. Four tables on three of the
  63 cached pages, all real. There is no reference for tables in the benchmark: the check was
  to look at every table it found on `dev` and `test`. Two columns, two rows and cells across
  columns are not found.

**Robustness round, same day.** No new documents: the 66 pages of `dev` and `test` read again
after a change that should not matter, with a scratch program (`Probe`, not in the repository;
`ro.sh jitter` is). Edits with the default profile, 5,315 as given before the round and 5,279 after:

| The page | Edits before the round | Now |
| --- | --- | --- |
| turned 90, 180 or 270 degrees | unreadable (four pages: 94 edits upright, over 2,200 turned) | 5,315 each way |
| 3 degrees askew, one way and the other | 14,501 and 20,842 | 5,618 and 6,476 |
| JPEG at quality 0.3 | 6,582: one page falls apart, the rest +4 % | Bible page still falls apart |
| at half the resolution | 16,163: six pages of small print fall apart | unchanged, see below |

- **A page on its side or upside down is turned, and a page askew is levelled** (`PageTurn`,
  `OcrConfig.pageOrientation`, `OcrResult.rotation()`, JSON key `rotation`), see "Turning the
  page" in internals.md. All 66 pages read the same in the four orientations. As given, two
  pages change through the levelling: the 1981 notice −43 edits, the Aparri News +7.
- **Reading order, two more fixes** found this way: a line over the gutter is left out when a
  side is tested for columns of its own (The Independent 1898, three columns, askew: CER 0.75 to
  0.089 on its boxes), and a box stays in its column when a line of the other column begins
  right behind the gutter in its row. The cached pages keep their order.
- **`ro.sh jitter`** orders every cached page 40 times with its box edges moved as two reads of
  one page differ (90 % of the edges within 0.04 of the box height, one end in fifty up to half
  a height). Edits: `dev` 2,349 as cached, 2,414 mean, 3,325 worst; `test` 2,755, 2,753, 2,886.
  Pages that still hang on a threshold: the arXiv page (13 of 40 trials change, up to 289
  edits against 91), the bill-of-sale form (33 of 40, small), the 1948 wholesale bill (29 of
  40, small), the 1935 appeal, and once in 40 the Arct dictionary (29 to 781).
- **Still open from this round:**
  - The 1923 Bible page as a strong JPEG, or 3 degrees askew one way: the detector draws the
    marginal reference and the line of the right column as one box, and the cut rules leave it
    whole because it starts in the middle of the left column. The row then ends both columns
    (27 to 1,060 edits).
  - Half resolution: small print at about 100 dpi is lost (Monthly Weather Review 51 to 7,035).
    Not looked into; `DetLimit.MIN` scales a small page up for detection, recognition would
    need the same.
  - The Arct dictionary 3 degrees askew one way (29 to 185) and the arXiv page under jitter.
- **Box threshold 0.4 with `--drop-fragments`**, measured for the first time together:

  | | `dev` | `test` | both |
  | --- | --- | --- | --- |
  | defaults (0.5, fragments kept) | 2,555 | 2,760 | 5,315 |
  | box threshold 0.4 | 2,516 | 2,522 | 5,038 |
  | box threshold 0.4, fragments dropped | 2,423 | 2,479 | 4,902 |

  Better on 20 pages and worse on 7; the largest gain is the 1981 notice (−186), the largest
  loss the 2017 diploma (+15). Both sets gain, unlike the level stretch. `test3` was then scored
  once with both settings: the mean of its three groups falls from 0.1329 to 0.1276 (invoices
  and receipts 0.1945 to 0.1798, the other two groups level), and with the Polish profile from
  0.1294 to 0.1241. **Both are the defaults now** (`boxThresh` 0.4, `dropFragments` on;
  `--keep-fragments` turns the second off). Dropping fragments now applies only to lines that
  were read: with recognition off it would have dropped every box.
  Not rebuilt: the tables and charts of `dev`, `test` and `test2` in benchmark.md and the README
  are from before this day. A full refresh is dev (70 pages now), `test`, `test2` and `test3`
  in the five engine variants, and Tesseract and RapidOCR on `test3` and the 31 new `dev` pages.
- **Word boxes** (`TextLine.words()`, `TextWord`, JSON key `words`), see "Word boxes" in
  internals.md. On 115 words of rendered lines (five texts, three sizes) both ends of a word are
  right to 0.055 crop heights in the median, 0.2 to 0.25 at worst. The PDF layer sets every word
  over its own box; `pdftotext -raw` still returns the page text unchanged. A line whose text a
  second read of the Polish profile changed has no word boxes. Not done: an hOCR or ALTO export,
  which is now short to write.
- **CI** now tests JDK 21 and 25 with the Vector API, JDK 21 on the scalar kernels (the eleven
  kernel comparisons of `RuntimeTest` skip themselves there) and, without failing the run,
  Windows; the JDK 21 leg also builds the release artifacts unsigned. The test JVM flags are
  the properties `test.jvm.modules` and `test.jvm.cpus`; CI passes an empty `test.jvm.cpus`, so
  a runner uses the processors it has. On its first run the three Linux legs passed and the
  Windows leg failed on one test of 243 in the core, a fixture read with CRLF line ends, fixed
  since; whether the other modules pass there is not known yet.

**New `dev` documents, 2026-11-14.** A collector round on another machine (five agents, the
prompt was a filled-in copy of the one in bench/COLLECTING.md) brought 34 business documents;
a second batch is still to come. Checked here before use:

- Files: every manifest row has twelve columns, `set` dev, an allowed license, and the SHA-256
  of the stored original matches.
- References: four fresh readers compared every line with zoomed crops of the image, without
  OCR. Fifteen references changed, most by one to five characters; the collector had tidied
  some typewritten spellings (`Środki` for `Srodki`) and joined or split a few rows.
- Dropped three: `en-receipt-bep-currency-1915` (pen-lettered script), `en-form-usfs-visitor-
  permit-1991` (a grid of eighty numbered boxes: no reading order to hold an engine to),
  `pl-formularz-cybercomplex-zsk-2025` (a named person's phone number and an address that may be
  a home). The last one is still in the history of the `ocr-pro-bench` repository.
- `en-receipt-target-ala-moana-2018` came at 7176x13968 and ran out of a 1 GB heap; it is stored
  scaled to 2569x5000 and its manifest row says so. The CLI ends with a bare
  `OutOfMemoryError` on such a picture: it should be an `OcrException` that names the size.
- Three pictures are under the 1,500 px the collecting rules ask for (the smallest 650x851); the
  readers found every letter legible, so they stay.
- The engine on the 31 that stay, default profile: pooled CER 0.082. The worst are what the
  round was for: three blocks side by side read by rows (the 1859 nota, 0.71), a ticket
  photographed 22 degrees askew (0.38; `PageSkew` stops at 20), an invoice half filled in by
  hand (0.51; the reference leaves handwriting out).
- Related to pages of other sets, by issuer or series, not the same document: a Biedronka
  receipt (`test3` has one), a National Security Action Memorandum (`test2` has number 125), a
  1948 bill from Słupsk (`test` has one from another firm there).

**A breach of the benchmark rules, 2026-11-14.** The first gray comparison in the review ran on
every gray file of `bench/data`, with the default profile. That scored three `test3` pages
(`en-invoice-idlewild-dairy`, `en-invoice-sells-floto-1913`, `en-invoice-terry-hay-1913`) and
three `test2` pages (`en-memo-nsam-125-1962`, `en-receipt-tesco-finchley-1994`,
`pl-przeglad-tygodniowy-1903-nr42`): their CER was printed, their text was not looked at. The
gray decision was then made again on `dev` and `test` only, and nothing was tuned on those six
pages. The three `test3` pages are no longer unseen; the owner decides whether they stay in
`test3`.

Found in the review and still open:

- Nothing but what "Still open from this round" lists above, and the release steps in "What to
  improve next".

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
| `dev` (70) | Look at it, debug on it, tune on it. 31 of them are the business documents of 2026-11-14; the published tables are still those of the first 39. |
| `test` (27) | Spent: its failures drove the last round. Use it as a regression check, like `dev`. |
| `test2` (20) | Scored once. These pages were looked at when reporting and are spent for tuning: Smyk receipt, Scientific American 1850, The Crisis 1910, kalendarz Ungra 1878, London Gazette 1836, NSAM 125 memo. Do not tune on any `test2` page. |
| `test3` (97) | Scored once, on 2026-11-14, for the box threshold and the dropping of fragments (benchmark.md, "Results on `test3`"). Spent for tuning, like `test2`: its pages have not been looked at and must not be. Tesseract and RapidOCR have not been run on it. |

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
  the lines with their boxes; `score` recompiles only the reading-order sources and scores every cached
  page in seconds; `cuts` lists merged lines it would split; `show <name>` draws a page's boxes
  in reading order. Workflow for any layout change:
  1. `ro.sh dump bench/ref/dev bench/ref/test` (once, about 10 minutes; re-dump after changing
     detection or the engine's cutting);
  2. edit the reading-order sources (`ReadingOrder.java` and the classes it is split into, see
     internals.md), then `ro.sh score bench/ref/dev bench/ref/test` and compare every
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
   merged lines there, and `LineRows.stacked` keeps lines of a curved photo out of one row.
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
   Left to the owner: the version (now `0.1.0-SNAPSHOT`; `./mvnw versions:set -DnewVersion=0.1.0`), making the
   repository public, the `io.github.kamilkrzywanski` namespace on central.sonatype.com, the
   portal token, and a GPG key on a public key server. The models ship as `ocr-pro-models`
   (27 MB) and `ocr-pro-models-pl` (12 MB), so no separate hosting is needed;
   `ModelFiles.ensure` still downloads from ModelScope for those who leave the JARs out. Release
   all five modules together with one version: a bundled default model must match the SHA-256
   in `ModelFiles`, so a bundle from another release fails at load time.

## Tried and dropped

- **Stretching the levels of a page** (2026-11-14). The median luma of the ink (the dark class
  of Otsu's split) to black and the median luma of the paper to white, the same line for the
  three channels. Edits with the default profile:

  | Stretch applied to | `dev` (2,555) | `test` (2,760) | both (5,315) |
  | --- | --- | --- | --- |
  | the page, for detection and reading | 2,373 | 2,825 | 5,198 |
  | the page after detection, for reading only | 2,643 | 2,783 | 5,426 |
  | the detector's input only | 2,246 | 2,884 | 5,130 |

  The gain is in detection, not in reading: faded newsprint finds its lines (Bristol Mercury
  1832 −122, Gazeta Lwowska 1914, Nature 1896 −47). It does not carry from `dev` to `test`,
  where the detector's variant is worse on 12 pages and better on 9 (the PGE invoice +85, the
  1935 appeal +36, the 1981 notice +34). The paper level does not tell which page gains: pages
  with paper at 163 and 175 lose, pages at 172 and 223 gain. Dropped, not left behind a flag: a
  user has no way to know which of their pages it helps.

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
