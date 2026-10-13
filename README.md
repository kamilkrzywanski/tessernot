# ocr-pro

OCR for printed documents in pure Java, built on PaddleOCR's PP-OCR models. You get one JAR and the
model files. It needs no JNI, no ONNX Runtime, no OpenCV and no Tesseract.

**Status: experimental.** It reads printed pages, receipts, tickets and typed letters. Its
recognition matches the same models on ONNX Runtime and is ahead of Tesseract on photos and
receipts. Page layout, such as forms and multi-column pages, is the weak spot. See the
[benchmark](#accuracy-and-speed).

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

Expect about 2 s per page on a laptop CPU. That is the same range as RapidOCR on ONNX Runtime
and as Tesseract in the benchmark below. It needs Java 21. Without the incubator module, the
scalar path is slower.

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
    OcrResult result = engine.recognize(Images.read(Path.of("scan.jpg")));
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

## Accuracy and speed

These numbers come from 20 public documents the engine was never tuned on, 10 Polish and 10
English: receipts, tickets, letters, forms, book and newspaper pages, and signs. The references
were transcribed by hand. Method, per-document results and how to reproduce them are in
[docs/benchmark.md](docs/benchmark.md).

| Engine | Word F1 | CER (median per page) | Median time per page |
| --- | ---: | ---: | ---: |
| ocr-pro | 0.930 | 0.062 | 2.1 s |
| ocr-pro + Polish corrections | 0.939 | 0.058 | 2.1 s |
| RapidOCR, same models on ONNX Runtime | 0.928 | 0.050 | 3.0 s |
| Tesseract 5 (`pol` / `eng`) | 0.846 | 0.143 | 2.1 s |

The measurements were taken on an Intel i7-1365U laptop with 12 threads. Word F1 ignores
reading order; CER does not.

- The pure-Java runtime reproduces ONNX Runtime's recognition.
- On photos, thermal receipts and stained paper, it is clearly ahead of Tesseract.
- It falls behind on reading order. It splits forms into columns that are not there, and it
  misses the columns of a low-resolution newspaper page. In both cases Tesseract's layout
  analysis wins.

## Polish print corrections

`Corrections.POLISH` (`--corrections polish`) is an optional layer for Polish documents. It
adds:

- character swaps in CTC decoding (`ł`, `ó`, `Ś`, ...)
- Polish and English word lists, and a list of Polish postal codes
- second reads of a line crop for invoice details

These rules were tuned on three scans. They are **off by default**. See
[docs/corrections.md](docs/corrections.md) for the rules and the benchmark for their effect on
documents they were not tuned on.

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
- [docs/benchmark.md](docs/benchmark.md): the held-out set and how to reproduce the numbers
- [docs/PLAN.md](docs/PLAN.md): the design decision log, in Polish

## License

The code is under the [Apache License 2.0](LICENSE). The models, word lists and test images
come from third parties under their own licenses. See [NOTICE](NOTICE) and
[models/NOTICE](models/NOTICE).
