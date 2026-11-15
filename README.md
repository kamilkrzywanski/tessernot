# ocr-pro

OCR for printed documents in pure Java, built on PaddleOCR's PP-OCR models. The engine is one JAR
with no runtime dependencies, and the models come in a second one. It needs no native libraries:
no JNI, no ONNX Runtime, no OpenCV and no Tesseract.

**Status: experimental.** It reads printed pages, invoices, receipts, typed letters and
multi-column pages. Its recognition matches the same models on ONNX Runtime and is ahead of
Tesseract, by a wide margin on invoices, receipts and photos. On the hardest historical pages
Tesseract still orders the text slightly better. See the [benchmark](#accuracy-and-speed).

## Why

- **It runs wherever a JVM runs.** There are no native libraries to ship per OS and CPU, no
  glibc/musl mismatch and no `java.library.path`.
- **It has its own inference runtime.** A small interpreter, written for these graphs, parses
  the official ONNX files, fuses the graph and runs it. It uses the Vector API when
  `jdk.incubator.vector` is available and falls back to scalar code that gives the same results
  when it is not.
- **It does more than return text.** You get line and word boxes with scores, reading order for
  multi-column pages, and tables as cells, ruled or set in columns. PDF input, in its own module, goes through Apache
  PDFBox. Output is plain text, JSON, hOCR, or a searchable PDF: the scan with its text under
  every word, to search, select and copy.

Expect 2–8 s per page on a laptop CPU, depending on how much text the page has; a dense
newspaper page with a hundred lines takes longest. Detection at 960 px instead of the default
2048 px (`--limit 960`) is about a quarter faster. It needs Java 21 and about 1 GB of heap at
the default, about 512 MB at 960 px; a multi-page PDF fits in the same heap, with fewer pages read
side by side when the heap is small. With less heap than the limit needs, the page is detected
at the largest size that fits, down to a heap of about 512 MB; below that the call fails with
a message that names the heap. Without the incubator module, the scalar path is slower.

## Quick start

You need Java 21 or newer. `./mvnw` downloads Maven 3.9; a Maven 3.9 of your own works as `mvn`.

```bash
./mvnw -DskipTests package
./ocr.sh ocr scan.jpg
./ocr.sh pdf document.pdf --dpi 150 --json
./ocr.sh pdf scan.pdf --searchable scan-with-text.pdf
./ocr.sh ocr scan.jpg --hocr scan.html
```

The default models are in `models/`. `./ocr.sh download models` fetches them again from
ModelScope and checks their SHA-256 digests.

| File | Role |
| --- | --- |
| `ch_PP-OCRv5_det_mobile.onnx` | text detection |
| `ch_PP-LCNet_x0_25_textline_ori_cls_mobile.onnx` | 0° / 180° line orientation |
| `PP-OCRv6_rec_small.onnx` + `ppocrv6_dict.txt` | recognition: Latin scripts including Polish, Chinese, Japanese kana, Greek |

`ocr.sh` runs `ocr-pro-cli/target/ocr-pro-cli.jar` with `--add-modules
jdk.incubator.vector`. That JAR bundles the models, so it also runs on its own, from any directory:
`java --add-modules jdk.incubator.vector -jar ocr-pro-cli.jar ocr scan.jpg`. Without the
flag, the engine uses the scalar path.

## Java API

The library is not on Maven Central yet. Run `./mvnw install` and depend on the artifacts you need,
all under `io.github.kamilkrzywanski` with version `0.1.0-SNAPSHOT`:

| Artifact | What it holds |
| --- | --- |
| `ocr-pro` | the engine, with no runtime dependencies |
| `ocr-pro-models` | the default models (27 MB), found on the class path by `OcrConfig.defaults()` |
| `ocr-pro-models-pl` | the word lists and Polish postal codes of `Corrections.POLISH` (12 MB) |
| `ocr-pro-pdf` | PDF input and searchable PDF output through Apache PDFBox |
| `ocr-pro-cli` | the command line: one runnable JAR with all of the above |

```java
try (OcrEngine engine = OcrEngine.load()) {             // models from ocr-pro-models
    OcrResult result = engine.recognize(OcrImages.read(Path.of("scan.jpg")));
    String text = result.text();                  // page text in reading order
    for (TextLine line : result.lines()) {
        // line.text(), line.score(), line.detScore(), line.quad(), line.bounds()
        // line.words(): every word with its own box
    }
    for (TextTable table : result.tables()) {     // table.ruled(): a grid of rules, or columns with none
        List<List<String>> rows = table.cells();  // empty cell = ""
    }
    List<OcrResult> pages = OcrPdf.recognize(engine, Path.of("scan.pdf"), 150f);  // ocr-pro-pdf
    // The same scan, or an image, as a PDF with the text under every line:
    OcrPdf.searchable(engine, Path.of("scan.pdf"), Path.of("scan-with-text.pdf"), 150f);
}
```

The bundled models are read into memory; nothing is written to disk. To keep them out of your
artifact, leave out `ocr-pro-models`, fetch the files once with `ModelFiles.ensure(dir)` and use
`OcrEngine.load(dir)` or `OcrConfig.defaults(dir)`. The Polish rules look for their word lists next
to the models: in the same directory, or in `ocr-pro-models-pl`; without them the rules still run,
without the dictionary steps.

An engine is not meant for concurrent calls. For parallel requests, use a pool, which gives
each caller its own engine. The engines share the weights and word lists, which are loaded once,
and divide the thread count of the configuration between them, so four engines on twelve
processors run three threads each:

```java
try (OcrPool pool = OcrPool.open(4)) {
    OcrResult result = pool.recognize(image);
}
```

To change models or thresholds, start from `OcrConfig.defaults().toBuilder()`. For another
language, swap the recognition model and its dictionary (`recModel(Path)`, `dict(Path)`). If every page has one column, as with
receipts, `.layout(Layout.ROWS)` (`--layout rows`) reads row by row and skips the column analysis.
For Latin-script documents, `.alphabet(Alphabet.LATIN)` (`--alphabet latin`) keeps 2,440 of the
recognizer's 18,710 output classes: the same accuracy on the benchmark, and 8–18 % less time over
a benchmark set, up to a quarter on pages with a lot of text.

### Word lists in any language

A word list makes the recognizer prefer real words where the image allows it. Each list is a
UTF-8 file with one word per line, plain or gzip, in any order and case:

```java
OcrConfig config = OcrConfig.defaults().toBuilder()
        .wordList(Path.of("german.txt.gz"))
        .wordList(Path.of("my-product-names.txt"))
        .build();
```

On the command line: `--words german.txt.gz,names.txt`. A recognized word that no list holds is
searched again on the recognizer's own output for that word, and a listed word with the same
number of letters replaces it when the image supports that spelling almost as well (at most
e⁴ ≈ 55 times less likely). The recognizer often misses a mark on a letter, so one added mark per
word (`a`→`ą`, `u`→`ü`) is allowed even when the image shows little of it. Words already on a list,
numbers and letters glued to digits stay as read. There are no rules for any one language: a list
is only its words.

With the Polish and English lists of `ocr-pro-models-pl` as `--words`, and no other rule, word F1
rose from 0.930 to 0.945 on `test` (Polish pages 0.915 to 0.933) and from 0.956 to 0.963 on `dev`;
pooled CER on `test` went from 0.046 to 0.044. Searching costs under 1 % of a page's time; a large
list that is already sorted, lowercase and NFC loads in about half a second, any other is sorted
on load.
`test2` and `test3` have not been scored with lists yet. `Corrections.POLISH` adds these two lists
on its own.

### Fragments and the box threshold

A line of at most 10 characters whose mean character confidence is below 0.8 is left out, after
the reading order is found. These are mostly stamps, ornaments read as CJK characters and stray
marks. It goes with the detector's box threshold, 0.4 here where PP-OCR has 0.5: the lower
threshold finds lines of clean print the detector is only a little unsure of, and lets in more
of those marks. Together they took 7.8 % of the edits on `dev` and `test`, where they were
chosen, and 4 % of the group mean on the held-out `test3` (invoices and receipts 0.195 to 0.180,
books and newspapers unchanged). Dropping fragments also drops real short text the recognizer is
unsure of, such as an `X` in a check box or a misread page number: `.dropFragments(false)`
(`--keep-fragments`) keeps every line, and `.boxThresh(0.5f)` is the PP-OCR value.

### Public API

The public API is in `io.github.kamilkrzywanski.ocr`:

| Class | Role |
| --- | --- |
| `OcrEngine`, `OcrPool` | run OCR on images or on the pages of a document, one caller at a time or several in parallel |
| `OcrConfig`, `Corrections`, `Layout`, `Alphabet`, `DetLimit` | models, thresholds, threads, the optional Polish rules, automatic or row-by-row reading order, the characters the recognizer may output, and which side of the page the detection size limits |
| `OcrResult`, `TextLine`, `TextTable` | page text, lines with boxes and scores, tables |
| `OcrImages`, `OcrJson`, `OcrHocr` | read an image file (EXIF-aware), write the JSON and hOCR forms |
| `ModelFiles`, `ModelSource`, `ModelBundle` | download and check the default models; where a model file comes from; the interface of a model JAR |
| `OcrException` | the single exception type |

PDF input is `io.github.kamilkrzywanski.ocr.pdf.OcrPdf` in `ocr-pro-pdf`, and the command line is
`io.github.kamilkrzywanski.ocr.cli.Cli` in `ocr-pro-cli`.

Everything under `io.github.kamilkrzywanski.ocr.internal` (runtime, kernels, pre- and
post-processing) is public only for technical reasons. It may change in any release.

## Accuracy and speed

Public documents with references transcribed by hand, in Polish and English: invoices,
receipts, letters, forms, and multi-column books, dictionaries, newspapers and official
gazettes. `test2` was collected for the latest round and scored at its end, then once more
after a column-gutter change built on `dev` only; nobody tuned on it. `test` was scored once in
the round before and then pointed at what to fix.

![Character accuracy on test2 by document kind](docs/benchmark-test2.png)

| Engine | `test2` CER pooled / median | `test2` word F1 | `test` CER pooled / median | `test` word F1 | Median time (`test2` / `test`) |
| --- | ---: | ---: | ---: | ---: | ---: |
| **ocr-pro** | 0.079 / **0.033** | 0.951 | **0.041 / 0.021** | **0.931** | 6.7 s / 3.7 s |
| ocr-pro, `--limit 960` | 0.205 / 0.037 | 0.935 | 0.049 / 0.022 | 0.918 | 5.7 s / 2.6 s |
| ocr-pro, `--layout rows` | 0.654 / 0.659 | 0.951 | 0.508 / 0.152 | 0.931 | about the same |
| RapidOCR, same models on ONNX Runtime | 0.656 / 0.662 | 0.951 | 0.512 / 0.237 | 0.929 | 5.2 s / 3.7 s |
| Tesseract 5 (`pol` / `eng`) | **0.070 / 0.033** | 0.934 | 0.210 / 0.149 | 0.854 | 3.5 s / 1.5 s |

CER counts reading order; word F1 does not. The `test` columns of ocr-pro are from 2026-11-15;
`test2` has not been scored since October 2026, so as to keep it unspent, and the times are
from 2026-10-15.
The test machine was an Intel i7-1365U laptop with
12 threads; all engines ran in one session with nothing else heavy running. `--layout rows` reads row by row like RapidOCR: it matches the automatic layout on
invoices and receipts and loses on everything with columns.

- **Invoices, bills and receipts:** 95 % character accuracy against 67 % for Tesseract on
  `test2` (90 % before a fix that one of its receipts prompted), and 88 % against 67 % on
  `test`. Tesseract returns nothing for some thermal and
  dot-matrix receipts.
- **Recognition:** word F1 is above Tesseract on every set and matches ONNX Runtime running the
  same models, so the pure-Java runtime gives up nothing.
- **Layout:** multi-column dictionaries, legal gazettes, the EU Official Journal, journal
  articles and newspapers with three or four narrow columns are read column by column, also
  where the detector merged lines across the gutters. RapidOCR reads the same models row by row
  and loses most of those pages.
- **Where Tesseract still wins:** a few complex pages, such as a 1910 magazine page, an 1836
  gazette and a typed memo, put it slightly ahead on `test2` as a whole (pooled CER 0.070
  against 0.079; the median is the same). It is also faster.

Method, the `dev` set, per-document results and how to reproduce them are in
[docs/benchmark.md](docs/benchmark.md).

## Polish print corrections

`Corrections.POLISH` (`--corrections polish`) is an optional layer for Polish documents. It
adds:

- character swaps in CTC decoding (`ł`, `ó`, `Ś`, ...)
- the Polish and English word lists for the word-list search above, and a list of Polish
  postal codes
- second reads of a line crop for invoice details

The second reads and CTC swaps were tuned on three scans and checked on the `dev` set. With the
word-list search the profile gives word F1 0.964 on `dev`, 0.946 on `test` and 0.960 on `test2`,
against 0.956, 0.930 and 0.951 for the plain pipeline. It is **off by default**. See
[docs/corrections.md](docs/corrections.md) for the rules and what they get wrong.

## Not in scope

- handwriting
- tables without ruled lines that have two columns, two rows, or cells across columns: only
  rows set in three or more aligned columns become a table; the rest stays plain text, read row by row
- the flow of articles that jump between columns or pages, and ad collages (the columns
  themselves are read in order)

Vertical lines are rotated and read. A page that was scanned on its side or upside down is
turned before it is read, and a page scanned askew is turned level; the lines still come back in
the pixels of the picture as given, and `OcrResult.rotation()` tells the quarter turns.

## Documentation

- [docs/cli.md](docs/cli.md): commands, flags, JSON output
- [docs/internals.md](docs/internals.md): pipeline, runtime, kernels, concurrency
- [docs/corrections.md](docs/corrections.md): the Polish rules
- [docs/benchmark.md](docs/benchmark.md): the `dev` and `test` sets and how to reproduce the numbers
- [docs/PLAN.md](docs/PLAN.md): the design decision log
- [docs/HANDOFF.md](docs/HANDOFF.md): what to improve next and how to work on it

## License

The code is under the [Apache License 2.0](LICENSE). The models, word lists, test images and
benchmark documents come from third parties under their own licenses. See [NOTICE](NOTICE),
[models/NOTICE](models/NOTICE) and [bench/README.md](bench/README.md#licenses); the model JARs
carry these notices in `META-INF/models/`. `ocr-pro-pdf` depends on Apache PDFBox, and the runnable
JAR of `ocr-pro-cli` bundles it; the license and notice files of the bundled libraries are in its
`META-INF/third-party/`.
