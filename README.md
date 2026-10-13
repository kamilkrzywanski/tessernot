# ocr-pro

OCR for printed documents in pure Java, built on PaddleOCR's PP-OCR models. You get one JAR and the
model files. It needs no JNI, no ONNX Runtime, no OpenCV and no Tesseract.

**Status: experimental.** It reads printed pages, invoices, receipts, typed letters and
two-column pages. Its recognition matches the same models on ONNX Runtime and is ahead of
Tesseract on photos and receipts. Some multi-column layouts still come out in the wrong order.
See the [benchmark](#accuracy-and-speed).

## Why

- **It runs wherever a JVM runs.** There are no native libraries to ship per OS and CPU, no
  glibc/musl mismatch and no `java.library.path`.
- **It has its own inference runtime.** A small interpreter, written for these graphs, parses
  the official ONNX files, fuses the graph and runs it. It uses the Vector API when
  `jdk.incubator.vector` is available and falls back to scalar code that gives the same results
  when it is not.
- **It does more than return text.** You get line boxes and scores, reading order for
  two-column pages, and ruled tables as cells. PDF input goes through Apache PDFBox. Output is
  plain text or JSON.

Expect 1.5–4 s per page on a laptop CPU, depending on how much text the page has. That is the
same range as RapidOCR on ONNX Runtime and as Tesseract in the benchmark below. It needs Java 21.
Without the incubator module, the scalar path is slower.

## Quick start

You need Java 21 or newer and Maven 3.9.

```bash
mvn -DskipTests package
./ocr.sh ocr scan.jpg
./ocr.sh pdf document.pdf --dpi 150 --json
```

The default models are in `models/`. `./ocr.sh download models` fetches them again from
ModelScope and checks their SHA-256 digests.

| File | Role |
| --- | --- |
| `ch_PP-OCRv5_det_mobile.onnx` | text detection |
| `ch_PP-LCNet_x0_25_textline_ori_cls_mobile.onnx` | 0° / 180° line orientation |
| `PP-OCRv6_rec_small.onnx` + `ppocrv6_dict.txt` | recognition: Latin scripts including Polish, Chinese, Japanese kana, Greek |

`ocr.sh` passes `--add-modules jdk.incubator.vector`. If you run the JAR yourself without that
flag, the engine uses the scalar path.

## Java API

The library is not on Maven Central yet. Run `mvn install` and depend on
`io.github.kamilkrzywanski:ocr-pro:1.0-SNAPSHOT`.

```java
try (OcrEngine engine = OcrEngine.load(Path.of("models"))) {
    OcrResult result = engine.recognize(OcrImages.read(Path.of("scan.jpg")));
    String text = result.text();                  // page text in reading order
    for (TextLine line : result.lines()) {
        // line.text(), line.score(), line.detScore(), line.quad(), line.bounds()
    }
    for (TextTable table : result.tables()) {     // ruled grids only
        List<List<String>> rows = table.cells();  // empty cell = ""
    }
    List<OcrResult> pages = engine.recognizePdf(Path.of("scan.pdf"), 150f);
}
```

An engine is not meant for concurrent calls. For parallel requests, use a pool, which gives
each caller its own engine:

```java
try (OcrPool pool = OcrPool.open(Path.of("models"), 4)) {
    OcrResult result = pool.recognize(image);
}
```

To change models or thresholds, start from `OcrConfig.defaults(dir).toBuilder()`. For another
language, swap the recognition model and its dictionary.

### Public API

The public API is in `io.github.kamilkrzywanski.ocr`:

| Class | Role |
| --- | --- |
| `OcrEngine`, `OcrPool` | run OCR, one caller at a time or several in parallel |
| `OcrConfig`, `Corrections` | models, thresholds, threads and the optional Polish rules |
| `OcrResult`, `TextLine`, `TextTable` | page text, lines with boxes and scores, ruled tables |
| `OcrImages`, `OcrJson` | read an image file (EXIF-aware), write the JSON form |
| `ModelFiles` | download and check the default models |
| `OcrException`, `Cli` | the single exception type, and the command-line entry point |

Everything under `io.github.kamilkrzywanski.ocr.internal` (runtime, kernels, pre- and
post-processing) is public only for technical reasons. It may change in any release.

## Accuracy and speed

These numbers come from 27 public documents that were collected after the last fix and never used
for tuning: invoices, bills, receipts, letters, forms, two-column newspapers, dictionaries and
official gazettes (18 Polish, 9 English). The references were transcribed by hand. Method, a
second `dev` set, per-document results and how to reproduce them are in
[docs/benchmark.md](docs/benchmark.md).

| Engine | Word F1 | CER (median per page) | CER (pooled) | Median time per page |
| --- | ---: | ---: | ---: | ---: |
| ocr-pro | 0.915 | 0.087 | 0.214 | 3.6 s |
| ocr-pro + Polish corrections | 0.920 | 0.087 | 0.214 | 3.5 s |
| RapidOCR, same models on ONNX Runtime | 0.911 | 0.221 | 0.514 | 3.3 s |
| Tesseract 5 (`pol` / `eng`) | 0.854 | 0.149 | 0.210 | 2.3 s |

The test machine was an Intel i7-1365U laptop with 12 threads. Word F1 ignores reading order;
CER does not.

- The pure-Java runtime reproduces ONNX Runtime's recognition.
- It reads photos, receipts and stained paper clearly better than Tesseract.
- Its reading order handles two-column pages and forms, which RapidOCR reads row by row.
- A few two-column pages still come out in the wrong order: a dictionary, a poster photo, a
  noisy 1952 scan, and a page split by a figure. Tesseract's layout analysis wins on two of those.

## Polish print corrections

`Corrections.POLISH` (`--corrections polish`) is an optional layer for Polish documents. It
adds:

- character swaps in CTC decoding (`ł`, `ó`, `Ś`, ...)
- Polish and English word lists, and a list of Polish postal codes
- second reads of a line crop for invoice details

These rules were tuned on three scans and checked on the `dev` set. On the `test` set they add
0.8 points of word F1 on Polish documents. They are **off by default**. See
[docs/corrections.md](docs/corrections.md) for the rules and what they get wrong.

## Not in scope

- handwriting
- tables without ruled lines
- newspapers with three or more columns
- the quality of commercial engines on hard scans

Vertical lines are rotated and read.

## Documentation

- [docs/cli.md](docs/cli.md): commands, flags, JSON output
- [docs/internals.md](docs/internals.md): pipeline, runtime, kernels, concurrency
- [docs/corrections.md](docs/corrections.md): the Polish rules
- [docs/benchmark.md](docs/benchmark.md): the `dev` and `test` sets and how to reproduce the numbers
- [docs/PLAN.md](docs/PLAN.md): the design decision log, in Polish

## License

The code is under the [Apache License 2.0](LICENSE). The models, word lists and test images
come from third parties under their own licenses. See [NOTICE](NOTICE) and
[models/NOTICE](models/NOTICE).
