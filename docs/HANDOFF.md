# Handoff: how to keep improving ocr-pro

State on 2026-10-14, after the reading-order round and the column-gutter change. Read [README.md](../README.md),
[internals.md](internals.md) and [benchmark.md](benchmark.md) first; this file is the to-do list
and the working method.

**Where things stand on the morning of 2026-11-15** (the dated paragraphs below have the detail):

- `dev` has 105 documents, 66 of them business documents; `test` 27. Default profile: `dev`
  0.049, `test` 0.041 (Tesseract 0.146 and 0.210). The tables of docs/benchmark.md are rebuilt
  for these two sets; `test2` and `test3` were not scored again.
- A page is set right before it is read: turned, levelled, laid flat when photographed from
  the side, enlarged when small, shrunk when too large for the heap.
- Blocks side by side are columns, rows of cells or neither by two rules built on the business
  documents; plain rows now beat the layout analysis by ten edits or more on three pages.
- Open, in the order it would pay: the 1859 note and other rows that would read better as
  blocks ("Blocks side by side"), small print lost to blur and low resolution ("Pages as a
  phone photographs them"), pages photographed from below (tried and dropped), and `test2` and
  `test3`, which wait for the owner's word before they are scored again.
- Tools: `bench/tools/ro.sh` (reading order on cached lines, now faithful to the engine),
  `bench/tools/probe.sh` (pages turned, askew, photographed), `ocr.sh score`.

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
    Partly done later that day, see "Size, turn and export" below.
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
  second read of the Polish profile changed has no word boxes. hOCR came later that day;
  ALTO is not written.
- **CI** now tests JDK 21 and 25 with the Vector API, JDK 21 on the scalar kernels (the eleven
  kernel comparisons of `RuntimeTest` skip themselves there) and, without failing the run,
  Windows; the JDK 21 leg also builds the release artifacts unsigned. The test JVM flags are
  the properties `test.jvm.modules` and `test.jvm.cpus`; CI passes an empty `test.jvm.cpus`, so
  a runner uses the processors it has. On its first run the three Linux legs passed and the
  Windows leg failed on one test of 243 in the core, a fixture read with CRLF line ends, fixed
  since; whether the other modules pass there is not known yet.

**New `dev` documents, 2026-11-14.** A collector round on another machine (five agents, the
prompt was a filled-in copy of the one in bench/COLLECTING.md) brought 34 business documents;
the second batch is below. Checked here before use:

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
  scaled to 2569x5000 and its manifest row says so. The CLI ended with a bare
  `OutOfMemoryError` on such a picture; since that evening it is read at a fraction of its size.
- Three pictures are under the 1,500 px the collecting rules ask for (the smallest 650x851); the
  readers found every letter legible, so they stay.
- The engine on the 31 that stay, default profile: pooled CER 0.082. The worst are what the
  round was for: three blocks side by side read by rows (the 1859 nota, 0.71), a ticket
  photographed 22 degrees askew (0.38; `PageSkew` stops at 20), an invoice half filled in by
  hand (0.51; the reference leaves handwriting out).
- Related to pages of other sets, by issuer or series, not the same document: a Biedronka
  receipt (`test3` has one), a National Security Action Memorandum (`test2` has number 125), a
  1948 bill from Słupsk (`test` has one from another firm there).

**Second batch of `dev` documents, 2026-11-14.** 24 more business documents from the same
collector round, checked the same way (six fresh readers, crops of the picture, no OCR; the
three forms were also checked without their PDFs). 20 stay, so `dev` has 93 pages.

- Dropped four: `pl-bilet-lodz-koluszki-1913` (half of the print is Russian in the old
  orthography, which the recognizer does not read), `en-invoice-gas-light-baltimore-1834` (a
  blotted copy of a published photograph of the bill, and half of its text is the caption),
  `en-receipt-uyc-1922` (a script typeface with pen writing across most printed words),
  `en-invoice-rubinstein-roth-1897` (more handwriting than print: an engine that reads the pen
  rows is charged for them whatever it does with the print).
- Twelve references changed. The larger ones: the reading order of the I-9 and W-9 forms, where
  the collector's draft from the PDF text layer split a block in the middle; district names
  written on every row of the Nysa schedule that are printed once; a routing grid that is a
  rubber stamp (the 1963 memo) and a stamped date (the 1936 bill) taken out; typed `RESTRICTED`
  lines put in (the 1945 memo).
- Left as it is and worth knowing: on the receipts the pen writing the references leave out is
  a quarter to a half of the ink (most on the 1871 Potts receipt), so their CER has a floor an
  engine cannot get under without telling pen from print. The 1976 sales slip names its
  customer and her town in pen; nothing else.
- In the Nysa schedule eighteen June dates are set with a lowercase `l` for the `I` of `VI`.
  The reference writes `VI`: a sort that stands in for its look-alike is written as the
  character it stands for, where the context leaves no doubt.

**Where the numbers stand at the end of 2026-11-14** (new defaults, all of the day's changes;
`ocr.sh score`, CER):

| | default profile | Polish profile |
| --- | --- | --- |
| `dev`, all 70 pages | 0.0364 | 0.0354 |
| `dev`, the first 39 pages (0.0230 and 0.0220 in the morning) | 0.0219 | 0.0209 |
| `dev`, the 31 new business documents | 0.0799 | 0.0784 |
| `test` (0.0461 and 0.0437 in the morning) | 0.0427 | 0.0403 |

By group on the 70 pages of `dev`, default profile: invoices, bills and receipts 0.155 (20
pages), letters, forms and notices 0.050 (27), books, newspapers and gazettes 0.019 (23).
Business documents are where the work is.

**Size, turn and export, evening of 2026-11-14.** Five things, measured with `Probe` on the 66
established pages of `dev` and `test` (the three newest `dev` documents were not read), default
profile, edits:

| The page | Before | With the classifier window | And with enlarging |
| --- | --- | --- | --- |
| as given | 4,975 | 4,607 | 4,607 |
| at half the resolution | 15,561 | 15,473 | 8,167 |

- **The line classifier sees the middle of a long line** (`PageTurn.window` in
  `OcrEngine.decode`), as the page vote already did. Squeezed whole into 80×160, a long upright
  line of small print was now and then called upside down and read turned over: one line of an
  Official Journal page came out as `-odo o nz zzd e s d e s z d qoso o od`. Five pages better
  as given, none worse (Gazeta Lwowska 189 to 68, OJ C10 2020 96 to 6, OJ C10 2021 91 to 6,
  Dziennik Ustaw 1952 135 to 99, Tygodnik Illustrowany 203 to 167). It is also what turns over
  one long line printed upside down on an upright page.
- **A small page with small print is enlarged** (`PageTurn.enlarge`), see "The size a page is
  read at" in internals.md. Six pages better at half the resolution, none worse, no page
  changed as given. Still lost at half: the Monthly Weather Review (51 as given, 1,862), the
  1981 notice (572, 955), the Białystok advance notice (37, 734), the Bristol Mercury (387,
  708; its lines are 22 px there, above the bound, so it is not enlarged). Not tried: a higher
  `detLimitSideLen` for such pages, or enlarging by more than two.
- **A picture too large for the heap is read at a fraction of its size** (`MemoryBudget.shrink`),
  and one the decoder cannot hold fails with an `OcrException` that names its size and the
  heap. The 100-megapixel receipt reads in a 1 GB heap; for 512 MB see the next round.
- **Pages askew up to 45 degrees are levelled** (it was 20), and upside down is judged after
  the levelling. The ticket photographed 22 degrees askew went from 44 edits to 24; pages
  turned by 25 or 40 degrees read as they do level.
- **hOCR** (`OcrHocr`, `--hocr`), with `OcrResult.width()` and `height()` for the page box.
  ALTO is not written: there was no schema validator at hand offline, and it is short to add
  next to hOCR.

The 4,975 is not the 4,902 of the table above: that run was made before `PageTurn`. With the
new defaults three pages read worse with it than without (the 1981 notice 510 to 572, the
Aparri News 71 to 80, the Arct dictionary 64 to 66), most likely through the levelling, which
with the old defaults gained 43 on the 1981 notice. Looked into in the next round.

**Levelling and small heaps, night of 2026-11-14.**

- **A line of a levelled page is cut from the page before the levelling** (`OcrEngine.Page`,
  `PageTurn.Level.before`), so the recognizer sees the picture resampled once. This is what was
  behind the three pages that read worse with `PageTurn` than without. On the 66 established
  pages: 4,607 to 4,570 as given (the 1981 notice 572 to 546, the Aparri News 80 to 69; no
  other page is levelled), 5,119 to 5,037 at +3 degrees, 5,699 to 5,608 at −3. The 1981 notice
  is still 36 edits behind its reading without `PageTurn` (510): what is left comes from the
  boxes of the second detection, not from the pixels.
- **A detection takes at most three quarters of the heap** (`MemoryBudget.detectionSize`): with
  less, the page is detected on a smaller copy. Heaps of 384 MB to 1 GB against limits of 960
  to 2048 on an A4 and a square page: a detection of 224 bytes a pixel read through with up to
  three quarters of the heap and ran out from 107 %. With the cap both pages read at the default
  limit in 512 MB, twice out of twice; before, the A4 page needed 768 MB and the square one
  1 GB. At 384 MB the engine runs out about every other time, whatever the detection takes.
- **A page that runs out of memory fails with an `OcrException`** that names the picture's size
  and the heap, from the main thread or from a line worker. The 100-megapixel receipt at 512 MB
  ends this way: its decoded picture alone is 300 MB.
- The test of the enlarged page failed on the Windows leg of CI (print drawn at 8 px depends on
  the platform's font hinting); it now draws the page four times as large and scales it down.

**Pages as a phone photographs them, night of 2026-11-14.** The 66 established pages again,
each changed the way a hand-held photo changes a page (`Probe`, variants `keyy12`, `keyx12`,
`blur15`, `shade55`; scratch, not in the repository). Edits, default profile, 4,607 as given:

| The page | Edits | Pages more than twice as bad | Where it goes |
| --- | --- | --- | --- |
| lit unevenly: 55 % of the light at one corner, a shadow edge across the lower third | 4,917 | 1 | nowhere in particular |
| photographed from below: top edge 12 % shorter, lines level, margins leaning in | 6,541 | 3 | two pages in columns: the 1923 Bible 27 to 1,058, Wisła 1912 62 to 778 |
| photographed from the side: one edge 12 % shorter, lines fanning out by about ±5 degrees | 12,870 | 17 | pages in columns first (Wisła 62 to 2,731, the Bible 1,434), then most pages |
| out of focus: a Gaussian of 1.5 px | 15,298 | 5 | small print: Monthly Weather Review 51 to 8,559, the Białystok notice 37 to 1,193 |

- Uneven light costs 7 %: nothing to do.
- **A page photographed from the side is the hole.** `PageTurn` levels a page by one angle; here
  the angle runs from +5 to −5 degrees down the page, so the levelling takes the middle and the
  top and bottom stay askew. What would close it: the point the lines run to, found from the
  angle of the long boxes against their height on the page, and the page turned back through
  the map that sends that point to infinity, before the levelling. The crop of a line would
  still be cut from the picture as given, through its quad, which a warp already handles.
- From below, the lines stay level and only gutters and margins lean, so single columns read as
  before and pages in columns lose their gutter. The same map would close it, but its point
  has to come from the lean of the margins, which lines of ragged text do not show well.
- Blur loses small print the way half the resolution does; nothing was tried.

**Where `dev` stands with all 93 pages, night of 2026-11-14** (`ocr.sh score`, CER; the 23
newest pages were read by the engine here for the first time):

| | default profile | Polish profile |
| --- | --- | --- |
| `dev`, all 93 pages | 0.0519 | 0.0505 |
| the first 39 pages (0.0219 and 0.0209 at the end of the day) | 0.0189 | 0.0179 |
| the 31 business documents of the first batch (0.0799 and 0.0784) | 0.0850 | 0.0835 |
| the 23 business documents added last | 0.1171 | 0.1143 |

- The first batch is worse than in the afternoon through one page: the letter
  `pl-pismo-mc-zsk-2022`, 336 edits to 548. It is a clean one-column letter that the layout
  analysis cuts down the middle, through words (`uprze` / `jmą`): the header blocks and the
  signature block on the right make a gutter at their left edge, and every full-width line is
  then taken for two lines merged across it. Three of its lines escaped the cut by chance
  before the line classifier was changed.
- **This is the largest known loss on business documents, and now it can be reproduced on
  `dev`.** The 54 business documents with `--layout rows` against the default: the layout
  analysis is worse on ten pages and loses 1,315 edits on seven of them, of 7,540 in all (the
  ZSK letter 548 against 55, PIT-11 446 against 33, the 1987 Ivey Steel memo 238 against 41,
  the 2025 letter of the President's office 148 against 32). It wins on seven (the W-4 form
  343 against 2,777, W-9 784 against 1,878, the 1920 Działdowo order 18 against 1,035). On the
  first 39 pages it wins on eighteen and loses on none. So item 1 of "What to improve next" has
  its material: a gutter that most lines of the block cross, with ink at the cut, is not a
  gutter.
- Hardest of the newest pages: the receipts whose pen writing the reference leaves out
  (Goodyear 0.53, Lafayette 0.47, Potts 0.36, Maritime Electric 0.31), the purple duplicator
  memo of 1945 (0.22), the 1818 account table (0.22).

**False gutters and photos from the side, 2026-11-15.**

- **A cut the boxes suggest needs the ink's consent** (`InkGutters.confirmed`), see "The ink
  has the last word on a geometric cut" in internals.md. The ZSK letter 548 edits to 55; no
  other page of `dev` (93) or `test` (27) changes. `dev` 0.0519 to 0.0493, default profile.
  This closes one of the three kinds of loss the layout analysis had on business documents.
  The other two are open, and they are orders, not cuts: rows of a form whose fields stand
  side by side, read block by block where the reference reads them row by row (PIT-11, 446
  against 33 with `--layout rows`; the name and title list of the Ivey Steel memo, 238 against
  41), and header blocks side by side (the 2025 letter of the President's office, 148 against
  32). The W-4 and W-9 forms need the opposite, so this is not a threshold to move.
- **A page photographed from the side is laid flat** (`PageTurn.fan`, `PageTurn.Fan`,
  `Images.flatten`), see "Seen from the side" in internals.md.
  The 66 established pages as photos from the side (`Probe` variant `persx12`: one edge 12 %
  shorter, in true perspective; 4,570 edits as given): 19,914 edits to 7,088, 38 pages better
  and 11 worse; the three-column Wisła weekly 2,765 to 55, The Independent 2,315 to 76, Volumina
  Legum 2,177 to 29. The earlier variant `keyx12`, which shortens one edge without narrowing
  the page, 12,870 to 6,191. As given, no page of `dev` (93) or `test` (27) is taken for a fan.
  - Still far from their reading as given: the 1916 Arct dictionary (29, 607), the Gazeta
    Lwowska supplement (68, 528), the Białystok notice (37, 343), the bill-of-sale form (12,
    317), Monthly Weather Review (51, 284). The far side of such a photo has three quarters
    of the resolution, so part of this is small print lost, as at half resolution.
  - Worse than before on a few pages that had read better askew than they do as given (the
    Mennica receipt 215 to 382 against 417 as given, the 1981 notice 645 to 775 against 546).
  - The flat page is cut to the text of the first detection and three line heights around
    it: a line that detection missed and that lies further out than that is lost. Not seen
    on these pages; a real photo with a small note far out in the margin could show it.
  - Not done: a page photographed from below (`keyy12`, 6,541 before and after). Its lines
    stay level; the point has to be found from the lean of margins and gutters. That was
    built the next night and taken out again, see "Tried and dropped".

**Third batch of `dev` documents, 2026-11-15.** Twelve Polish documents: five blank forms
(ZAW-FA, ZUS ZUA and ZCNA, the KOWR alcohol register, the Blue Card), two printed accounts of
1855, two letters of 2026, a typed letter of 1936 and two posters of 1939 and 1941. Checked as
before by four fresh readers from crops, without OCR and without the PDFs. All twelve stay,
so `dev` has 105 pages.

- Nine references changed, none by more than a few characters or line breaks: where a label
  stands against a two-line neighbour (ZAW-FA, KOWR, section VI of ZUS ZUA, the heads of the
  1855 account), two old spellings (`wszelkiéj`, `znaleść`), spaces a typewriter did not make.
- In the 1855 expenditure account a period stood where a dotted leader begins right after a
  word, on four of seven such rows; print cannot tell that dot from the leader, so it is left
  out with the leader on all of them.
- The two ZUS forms have rows where no order is the one right order: a label printed half a
  line above or below its neighbours. The references read those in the order of the field
  numbers. The rotated labels in their left margins repeat the headings beside them (4 % and
  9 % of the characters); an engine that does not read rotated text loses those.
- The Ombudsman's letter names the director of the foundation it is addressed to, in that
  role; the police letter names its signer inside the signature graphic, which the reference
  leaves out. Both files come from Wikimedia Commons. No address, phone number or identifier
  of a private person.
- The German half of the 1941 poster is in a `pl` document: the Polish profile will meet
  German words there.

**Where `dev` stands with all 105 pages, 2026-11-15** (`ocr.sh score`, CER, after the false
gutter fix and the flattening):

| | default profile | Polish profile |
| --- | --- | --- |
| `dev`, all 105 pages | 0.0556 | 0.0541 |
| the first 39 pages | 0.0189 | 0.0179 |
| the 54 business documents of batches one and two | 0.0945 | 0.0923 |
| the 12 Polish documents of batch three | 0.1215 | 0.1191 |

- Batch three, edits with the default profile and with `--layout rows`: ZUS ZCNA 509 and 500
  of 2,042 characters, the 1855 institution account 417 and 417 of 967, ZUS ZUA 374 and 366,
  the 1936 typed letter 226 and 158, ZAW-FA 180 and 35, the Ombudsman's letter 172 and 0, the
  police letter 111 and 111. The rest are under 60.
- **The layout analysis is still the largest single loss on business documents.** On the 66
  of them it reads worse than row by row on fourteen pages; the eight worst hold 1,179 edits of
  9,214: PIT-11 (446 against 33), the Ivey Steel memo (238, 41), the Ombudsman's letter (172,
  0: a clean letter read without one error row by row), ZAW-FA (180, 35), the President's
  office letter (148, 32), the 1936 letter (226, 158). None of these is a cut any more; they
  are orders: header blocks, field rows and footer columns that stand side by side and are
  read one block after the other. It still wins on seven pages, the W-4 and W-9 forms first.
- Not looked into: the two ZUS forms (pink print, rotated margin labels, boxes) and the 1855
  institution account, which lose as much row by row; the police letter (111 either way).

**Blocks side by side: columns, rows of cells, or neither, 2026-11-15.** See steps 7 and 8 of
"Reading order" in internals.md.

- A scratch tool (`Flip`, not in the repository; it needs a hook in `ColumnSection.of`) turned
  every columns-or-rows decision of the reading order over, one at a time, on the cached
  lines of all 132 pages of `dev` and `test`, and scored the page again. That gives, for every
  decision, whether it was right, and the numbers the sides had when it was made.
- **Columns must stand beside each other** (`ColumnTests.beside`). The three largest wrong
  decisions were columns whose lines never face each other: a letterhead, then a date and an
  addressee further down on the other side. Right column decisions have 0.77 or more of
  their lines facing a line across the gutter; these had none.
- **Small blocks on shared baselines are rows of cells** (`ColumnTests.rowsOfCells`). Of 32
  small blocks read as columns, 14 were wrong and 8 right, and whether the lines of the two
  sides stand on the same baselines told them apart but for three.
- **Fragments are on neither side** of those two tests (`Fragments`).
- Edits, default profile, with the engine: `dev` (105 pages) 11,305 to 10,039, CER 0.0556 to
  0.0494: PIT-11 446 to 33, the Ivey Steel memo 238 to 41, the Ombudsman's letter 172 to 0,
  ZAW-FA 180 to 35, OJ L91 176 to 60 (the page item 1 below gave up on), the President's
  office letter 148 to 32, five more pages by 10 to 38; ZUS ZCNA 509 to 526. `test` 2,479 to
  2,430: the 2010 embassy letter 156 to 118, the Mennica receipt 417 to 406.
- Plain rows now beat the analysis by ten edits or more on three pages only (69 edits in
  all, on the cached lines). What is left the other way, rows where blocks would read
  better: the 1859 note (three blocks side by side with centred lines, 444 edits), the
  Mennica letterhead beside its addressee with pen scribbles between them (196), part I of
  the W-9 (114), the QR box of the I-9 (94), the JFK telegram header (81), the two tables of
  the 1855 institution account (41). Each fails a different test; none was changed.
- The references do not agree on cells with rules around them: PIT-11 and ZAW-FA read them by
  visual rows, the W-9 and the head of the ZUS forms cell by cell. No rule satisfies both.
- **The workbench did not see fragments, and now does.** `ro.sh dump` cached the engine's
  result, which has dropped them, while the engine orders the lines with them; two `test` pages
  scored differently on the cache and in the engine for that reason, and ZUS ZUA 348 against
  374. Since that day `ro.sh dump` reads all pages with one engine and fragments kept
  (`RoDump`), the cache carries the score of every line (`<name>.score`), and `score` and
  `jitter` drop the fragments after ordering, as the engine does. A cache from before has no
  scores and is ordered as before; empty `bench/out/ro/json` and dump again. With the new
  cache the workbench gives the engine's edits on every page of `dev` and `test` to within
  rounding (0.0495 and 0.0406 pooled against 0.0493 and 0.0406), but for one: the Helios cinema
  ticket, 17 degrees askew, 64 against 24. The engine orders such a page on the levelled
  picture and after a second detection; the cache has its boxes as they lie on the picture
  as given. The same holds for any page the engine turns level or lays flat.

**The benchmark tables, rebuilt on 2026-11-15 for `dev` and `test`.** docs/benchmark.md and the
README have the engine of that morning in five variants on both sets, with
Tesseract and RapidOCR run again on all 105 and 27 documents, and a chart for each set
(`docs/benchmark-dev.png`, `docs/benchmark-test.png`).

| | `dev` (105) | `test` (27) |
| --- | --- | --- |
| ocr-pro | 0.049 | 0.041 |
| ocr-pro + polish | 0.048 | 0.038 |
| ocr-pro, `--limit 960` | 0.078 | 0.049 |
| ocr-pro, `--layout rows` | 0.374 | 0.508 |
| RapidOCR, same models | 0.389 | 0.512 |
| Tesseract 5 | 0.146 | 0.210 |

- **Not scored: `test2` and `test3`.** Their tables are from before 2026-11-14 and from
  2026-11-14. Scoring them again spends them once more; that is the owner's call. Tesseract
  was run over `test3` that night (`bench/out/test3-tesseract`, 97 outputs) and its output was
  not scored or looked at; RapidOCR was not run on `test3`.
- **No page times.** Other jobs ran on the machine all night (load 20 to 50 on twelve
  threads), so the times of these runs mean nothing and the tables of `dev` and `test` leave
  them out. The last times measured alone are those of 2026-10-15.
- The scores are in `bench/out/<set>-<variant>.tsv` (not in git), the earlier ones in the
  scratch directory of that session only.

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
| `dev` (105) | Look at it, debug on it, tune on it. 66 of them are the business documents of 2026-11-14 and 2026-11-15. |
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
- **`bench/tools/probe.sh`: the robustness probe** (in the repository since 2026-11-15; the
  rounds of 2026-11-14 ran a scratch copy). It reads every page of `dev` and `test` again after
  a change that should not matter (`rot90`, `skew3`, `half`, `jpeg30`) or as a camera changes a
  page (`persx12`, `keyy12`, `blur15`, `shade55`) and writes the edits of each page for each
  variant: `bench/tools/probe.sh bench/out/probe.tsv none skew3 persx12`. The variants are
  listed in `Probe.java`. One pass over the 132 pages takes ten to fifteen minutes a variant.
- **`bench/tools/ro.sh`: the reading-order workbench.** `dump` runs OCR once per page and caches
  the lines with their boxes and scores, fragments included; `score` recompiles only the reading-order sources and scores every cached
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

1. **False column splits on receipts and memos.** Largely done on 2026-11-15, with the business
   documents this item asked for: see "Blocks side by side" above; the rest of this item is
   the state before that day. Partly done: a side made mostly of numbers is
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

- **Laying flat a page photographed from below** (2026-11-15; the code is in commit `ac3809c`
  and was reverted the same morning). Such a page keeps its lines level, and its margins and
  gutters run to a point far above it. The point was found from the boxes: the left ends, and
  the right ends, of long lines that follow one another down the page on a straight line are a
  margin; the lean of each margin was fitted against where it stands across the page; the
  point went into the map that lays a fanned page flat (`Fan` got a second distance). The
  estimate was right: on pages made into such photos it gave 0.12 to 0.13 for a true 0.128,
  and in its last form it took no page of `dev` or `test` as given for one. Three forms, edits
  on the 66 established pages as photos from below, 6,484 before:
  - margins of six lines, three or more of them, half following the fit: 5,788, and Wisła 1912
    778 to 56; but it also took two pages as given for seen from below (Bristol Mercury and the
    Aparri News, which have a real lean of a twentieth, and read 18 and 5 edits better), and a
    review showed that one margin read from too few lines could carry the fit on a flat page;
  - margins of ten lines over fifteen line heights, three quarters following the fit: no page
    as given, and Wisła back at 778, because the first detection draws its lines across the
    gutters and only the two outer margins of the page are left;
  - the same, with two long opposite margins allowed: 6,769. Wisła 778 to 53 and the 1952
    Dziennik Ustaw 115 to 89, but the arXiv page 92 to 255 and the Bible page, which reads as
    rows either way, 1,058 to 1,996.
  The pages that lose are the two that already hang on a threshold: laid flat they are the
  page as given, a tenth smaller and resampled, and that is enough to tip them. A page in one
  column needs none of this. What it would take: those two pages made steady first (the
  marginal references of the Bible page, the arXiv page under `ro.sh jitter`).

- **Keeping the lines of a sideways label together** (2026-11-15). The section headings that
  the ZUS forms print up their left margin, in two or three lines, are vertical words to the
  reading order: each is placed by its top among the rows, so one label comes out apart and
  by length. A step that chained neighbouring lines whose words are rotated and put them at
  the place of the first, in reading order (left to right for text that runs up the page),
  got the labels right and moved the score little: ZUS ZUA 374 edits to 349, ZUS ZCNA 526 to
  529, W-9 unchanged. The labels repeat the headings beside them and where they go relative
  to those is a convention; 130 lines for 22 edits were not kept. One thing to know from it:
  the quad of a line read on its side is its box as the detector drew it, not turned with the
  text; only the quads of its words have their corners in the order of the text.

- **Leaving out stamps and pen writing by ink colour or by score** (2026-11-15; item 6 of
  "What to improve next" until then). Every line the engine read on `dev` and `test` (7,985
  lines, 256,426 characters) was matched against its page's reference, as the fewest edits that
  turn it into some stretch of the reference. Lines that are not in the reference at all (more
  than half of their characters wrong) are 196, with 2,339 characters: less than a fifth of
  the edits of those pages. They cannot be told from print. By score, 1,458 of those characters
  are in lines read at 0.9 or better. By colour, taken as the distance of a line's ink from
  the ink of the page's matched lines, a filter loses more print than it saves: at a distance
  of 60 it would drop 881 wrong characters and 4,648 right ones, because coloured print
  (letterheads, forms, red numbers) is commoner than coloured stamps. The best of sixteen
  pairs of colour and score thresholds nets 56 edits in 12,000 and loses 170 printed
  characters. Stamps and handwriting cost less than they look: on the receipts the errors are
  in the print the pen runs over and in ornamental type, not in extra lines.

- **Detecting a tall page in bands** (2026-11-14). A portrait page larger than the detector's
  limit is scaled down by its height; detected again in bands across its full width, each as
  high as the page is wide, it is scaled by its width, about one and a half times larger for an
  A4 page. The bands overlapped by twice the height of a tall line and a box was taken from the
  band whose core held its centre, so no line was cut or taken twice. On the 66 established
  pages, 48 of them tall enough: 4,570 edits to 9,078. Two pages gain much (Bristol Mercury 1832
  387 to 196, OJ L91 176 to 60), three fall apart (Volumina Legum 36 to 3,639, the 1923 Bible
  page 27 to 955, the Arct dictionary of 1916 29 to 170) and the rest move by a few edits either
  way. The stitching is not the cause: on Volumina Legum the detector, given the larger page,
  draws eight lines across the gutter as one box each, and the rows then end both columns. The
  height of the lines as the detector sees them does not tell the pages apart (Bristol Mercury
  27 px, Volumina Legum 31, OJ L91 30). A larger detector input is not better by itself; what
  Bristol Mercury needs is still open.

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
