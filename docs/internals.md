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

1. **Level.** The median angle of the long lines is the page's skew. Between 0.3° and 20°,
   every box is rotated back by it before anything else, so a gutter that runs at a slant on a
   photo is vertical again. The output keeps the original boxes.
2. **Rows.** Lines become rows: a line joins a row when it overlaps the row's first box
   vertically by at least half the smaller height. A tall vertical word stays out of this, so it
   cannot glue the page into one row.
3. **Gutter.** Each box first loses a quarter of its height on both sides, which removes the
   unclip padding. The gutter is then the emptiest vertical strip of the block that has at least
   two boxes on each side. At most one box (or a tenth of the block) may still cross it.
4. **Sections.** A row with a box over the gutter, such as a title across both columns, is read
   whole. The rows between such titles form sections. A long line that reaches into the gutter
   by less than a tenth of its width does not cross: it is a column line that picked up a
   letter of the other column.
5. **Text columns first.** The whole page is checked for text columns before blank rows split
   it. Both sides of a section must be text columns of similar width: five or more lines a side,
   80 % starting on the left margin or ending on the right one (a justified column has both, so
   indents, short last lines and right-set signatures count), 80 % of rows holding one line on
   that side (pieces closer than the gutter are one line), and a median line spanning 60 % of
   the column.
   A side that splits again into such columns also counts, so three or more columns are read one
   after another. Each side is then ordered again from step 3. Headings that sit at the same
   height in both columns leave a blank across the page; finding the columns first keeps that
   blank from interleaving them.
6. **Blocks.** Without text columns, rows split into blocks at vertical gaps larger than 0.35 of
   the median line height, and each block is ordered on its own (from step 3).
7. **Split check in a block.** A section of a block is read left column first, then right
   column, only when both sides look like columns:
   - **A wide gutter** (at least 0.8 of the median line) is enough when neither side looks like a
     form. A side with four or more lines needs 60 % of its left edges on one margin and 65 % of
     its rows holding one box on that side. The fare fields of a train ticket fail this; the
     seller and buyer blocks of an invoice pass.
   - **A narrower gutter** needs two full text columns, as in step 5.
   - **Never a column:** a side whose lines are less than a quarter as wide as the lines beside
     it (list numbers, bullets), a rail of short right-aligned values next to longer names
     (receipt prices), and a block whose median row holds three or more boxes (a grid).

**Merged lines.** Where the gutter is narrow, the detector sometimes draws one box over a line of
each column. `ReadingOrder.cuts` finds such boxes among the crossing rows of a text-column
section: a line no taller than 1.5 median lines, starting on the left column's margin, with at
least two median lines of text on each side of the gutter, and columns going on right above or
below it. When so many lines are merged that the gutter does not show at all, it is looked for
among the boxes narrower than three quarters of the page. If those form two text columns, the
wide boxes over that gutter are checked the same way, and a run of them counts when one of the
run touches a column line. The engine cuts the box on the gutter, reads both halves again and
orders the page once more. A title across the columns, a full-width paragraph and a running
head stay whole.

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
