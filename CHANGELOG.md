# Changelog

The release workflow takes the section of the tagged version as the notes of its GitHub release.

## 0.1.1

Fixes in the command line, found by running 0.1.0 on Windows.

- Output redirected to a file or a pipe is UTF-8 on every platform. On Windows it used the ANSI
  code page, so `--json` was not UTF-8 and letters outside that page were lost: on a Windows with
  an English system locale, `Ł` came out as `?`. On an interactive console the output keeps the
  console's encoding.
- Started without `--add-modules jdk.incubator.vector`, the commands that read pages say on stderr
  that they run on the scalar kernels, which give the same text about nine times slower.

## 0.1.0

The first release: OCR for printed documents in pure Java, on PaddleOCR's PP-OCR models, with
no native libraries. Experimental.

- **Engine** (`tessernot`): text detection (PP-OCRv5), line orientation and recognition
  (PP-OCRv6 small: Latin scripts including Polish, Chinese, Japanese kana, Greek), run by its
  own interpreter of the ONNX graphs, on the Vector API when `jdk.incubator.vector` is present
  and on scalar code with the same results when it is not. No dependencies at run time.
- **Layout:** line and word boxes with scores, reading order across columns, and tables as
  cells, ruled or set in columns. Pages are turned, levelled and laid flat before they are read.
- **Output:** plain text, JSON, hOCR, and searchable PDF.
- **Models** (`tessernot-models`): the default models on the class path, found by
  `OcrConfig.defaults()` and checked by SHA-256.
- **Polish corrections** (`tessernot-models-pl`): word lists and postal codes for
  `Corrections.POLISH`.
- **PDF** (`tessernot-pdf`): PDF input and searchable PDF output through Apache PDFBox.
- **Command line** (`tessernot-cli.jar` on the GitHub release): one runnable JAR with the
  engine, PDF input and the models. On Maven Central `tessernot-cli` is a plain JAR with the
  other modules as dependencies, so JBang runs it from its coordinates.
- **Benchmark:** 350 documents in four sets, against Tesseract 5.5.3 and RapidOCR on the same
  models, in [docs/benchmark.md](docs/benchmark.md). On the held-out `test3` the pooled CER is
  0.123 against 0.161 for Tesseract, and 0.195 against 0.387 on invoices, bills and receipts.
