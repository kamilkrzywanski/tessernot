# Internals

## Pipeline

```
BufferedImage
  → BGR, half-pixel resize, normalize (x/255 - 0.5) / 0.5
  → detection (DB): thresholds, minimum-area rectangle, unclip
  → crop (rotated 90° left when the line is vertical)
  → orientation classifier 80×160, rotate 180° when needed
  → recognition at height 48, greedy CTC
  → [Corrections.POLISH only: see corrections.md]
  → reading order
  → ruled grid → tables(), separate from the page text
```

## Runtime

`io.github.kamilkrzywanski.ocr.internal.runtime` is a small inference runtime. It runs the graphs these
models need and nothing more. It is not a general ONNX runtime.

- **ONNX parsing** uses its own protobuf reader, with no protobuf library and no Python. Weight
  and signature dimensions that are negative or overflow `int` are rejected. A symbolic
  `dim_param` stays dynamic.
- **Operators**: Identity, Add, Sub, Mul, Div, Pow, Sqrt, Erf, Conv (grouped, with and without
  bias), ConvTranspose 2D, Relu, Sigmoid, HardSigmoid, HardSwish, GlobalAveragePool,
  AveragePool, MaxPool (including `SAME_UPPER`), BatchNormalization (inference),
  Resize (nearest), Concat, MatMul, Softmax (per opset), ReduceMean, Squeeze, Unsqueeze,
  Transpose, Slice, Reshape and Shape. Constant, Cast, Flatten and Dropout pass through.
  GELU in PP-OCRv6 is built from Erf. An unknown operator fails with
  `Unsupported op: NAME` and the input shapes.
- **Graph optimization** removes Identity nodes and folds Conv+BatchNorm. It folds a bias Add
  into the convolution and fuses a ReLU, Sigmoid or HardSwish epilogue. A scalar multiply and
  add right after a convolution go into its weights. A second affine after the activation stays
  in the same epilogue.
- **JIR** (`JIR1` magic) is the optimized graph plus weights. It loads faster and is not
  optimized again. A truncated or inconsistent file fails with `OcrException`.
- **Memory**: an arena of power-of-two buckets for activations, with no zeroing on allocation.
  Weights are read-only and shared between sessions. `Session.fork()` gives a worker its own
  arena over the same graph.

### Kernels

The Vector API path (`jdk.incubator.vector`) needs `--add-modules jdk.incubator.vector`.
Without it, the engine falls back to scalar code that computes the same results.
`VectorKernels` is the only class that imports the incubator module. Everything else loads it
by reflection.

- **1×1 convolution** runs as GEMM. It processes four weight rows at a time and keeps a panel of
  two output vectors in registers for the whole K reduction. With one thread, the activation
  columns of that panel are packed into a contiguous buffer first.
- **3×3 stride 1** accumulates the whole window in one pass per output row.
- **3×3 stride 2** splits the row into even and odd pixels first.
- **5×5 and other kernels** are computed directly, without im2col.
- **Nearest resize** by an integer scale (2, 4, 8) copies blocks.
- **ConvTranspose 2×2 stride 2** writes both outputs of a pair in one pass.
- **Element-wise maps** of a million elements or more are split across threads.
- **Int8** is implemented but off: in pure Java it is usually not faster and it costs accuracy.

## Pre- and post-processing

- **Preprocessing** follows PP-OCR / OpenCV:
  - BGR channels, mean and std 0.5.
  - Half-pixel bilinear resize: `src = (dst + 0.5) * scale - 0.5`.
  - Recognition: height 48, minimum width 320, maximum about 4096. The padding is zero in
    normalized space, which is gray 127.5.
  - Deskew and Sauvola thresholding exist but are off. On a neural model they often hurt.
- **Detection**:
  - Thresholds: 0.35, box threshold 0.5, unclip 1.6, 2×2 dilation with the OpenCV `(0, 0)`
    anchor, up to 1000 candidates.
  - Size: the longer side is limited to 960, then rounded to a multiple of 32 with
    `Math.round` (half up). Python's half-to-even `round` can differ by 32 px.
  - The output gets a sigmoid when its values leave about `[0, 1.2]`.
- **Classifier**: input size comes from the model signature (80×160 for the default model).
- **CTC**: greedy, blank at 0, repeats collapsed. The score is the mean probability of the kept
  steps. A second softmax is not applied when the output already sums to 1.
- **Charset**: one token per line. Empty lines are skipped, and one trailing CR and a BOM are
  removed. `useSpaceChar` appends the space class. With the default model there are 18710
  classes: blank, 18708 characters and the space.

## Reading order

Two boxes join one row when their vertical overlap reaches half the smaller height. A vertical
line gets its own row. Two text columns split by a gutter are read top to bottom, left column
first. A price aligned to the right margin stays with its item. A grid of three or more columns
is read by rows.

## Tables

A grid of ruled lines with at least two columns and two rows becomes a `TextTable`. An empty cell
is `""`.

- A box crossed by a rule is read again piece by piece, so a cell can differ from its line.
- Two long parts of one rule separated by up to 18 white rows count as one rule. Short pieces,
  such as a digit stem, are not joined.
- A box that overlaps a rule by less than 28 px is not cut.
- An empty cell that still has ink is read once. The read is kept only when it is a short
  number or a short word.

A table without ruled lines stays plain text.

## Concurrency

One `OcrEngine` is not safe for concurrent `recognize` calls; the methods are synchronized.
Inside one call, recognition runs lines in parallel on the engine's pool, and detection splits
GEMM rows across it.

`OcrPool` lends each concurrent caller a separate engine with the same configuration. The text
is the same as with a single engine.

- `close` waits for running calls.
- Closing a borrowed engine removes that slot from the pool.

A multi-page PDF stays on one engine. Its pages run in parallel in a window as wide as the pool,
so at most that many bitmaps are in memory. Page workers run detection on their own thread, so
the pool never waits on itself.

## Performance notes

On a synthetic 1200×700 invoice (five lines, detection limit 960, Temurin 21, Vector API), with
the earlier latin PP-OCRv5 recognizer, the median was:

| Threads | Median |
| --- | --- |
| 24 | 198 ms |
| 8 | 218 ms |
| 4 | 238 ms |
| 1 | 403 ms |

On a dense 1240×1754 page (31 lines) with PP-OCRv6 small and 24 threads, the median was
658 ms: 159–185 ms detection and 468–508 ms recognition. The same page with latin PP-OCRv5 took
307 ms. Recognition cost grows with the number and length of lines.

For timings on real documents next to Tesseract and RapidOCR, see [benchmark.md](benchmark.md).

## Tests

- Operators against hand-computed values (about 1e-4).
- Official ONNX files: operator counts, CLS input 80×160, REC input height 48, REC output 18710.
- Raw and fused detection graphs agree within 1e-3.
- A synthetic invoice rendered with DejaVu: CER below 0.25, and `Zażółć gęślą jaźń` is present.
- Empty and 1×1 images, `null`, a vertical `FAKTURA`, EXIF orientations 1–8.
- PDFs: one page, several pages in order, empty, encrypted.
- Three real scans (`QualityGateTest`): an edit ceiling for the plain pipeline and one for
  `Corrections.POLISH`. These are the tuning scans, so they guard against regressions. They do
  not measure accuracy; [benchmark.md](benchmark.md) does.
- Tests that need `models/` are skipped when the directory is missing.
