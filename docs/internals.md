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
  `dim_param` stays dynamic. Packed `int64_data` is read as varints first and as fixed eight-byte
  values only when that fails; a shape larger than its data fails before an array is made.
- **Operators**: Identity, Add, Sub, Mul, Div, Pow, Sqrt, Erf, Conv (grouped, with and without
  bias), ConvTranspose 2D, Relu, Sigmoid, HardSigmoid, HardSwish, GlobalAveragePool,
  AveragePool, MaxPool (including `SAME_UPPER`), BatchNormalization (inference),
  Resize (nearest), Concat, MatMul, Softmax (per opset), ReduceMean, Squeeze, Unsqueeze,
  Transpose, Slice, Reshape and Shape. Constant, Cast, Flatten and Dropout pass through.
  GELU in PP-OCRv6 is built from Erf. An unknown operator fails with
  `Unsupported op: NAME` and the input shapes.
- **Graph optimization** removes Identity nodes and folds Conv+BatchNorm. It folds a bias Add
  into the convolution, when the constant is shaped `[M,1,1]` or `[1,M,1,1]`, and fuses a ReLU,
  Sigmoid or HardSwish epilogue. A convolution whose output the graph hands out is left alone. A scalar multiply and
  add right after a convolution go into its weights. A second affine after the activation stays
  in the same epilogue.
- **JIR** (`JIR1` magic) is the optimized graph plus weights. It loads faster and is not
  optimized again. A truncated or inconsistent file fails with `OcrException`.
- **Memory**: an arena of size-class buckets for activations (four sizes per doubling), with no
  zeroing on allocation. A request takes the smallest idle buffer that fits, up to twice its size,
  before a new array is made. After every run a session keeps at most 64 MB of idle buffers, the
  small ones first, and after every engine call 8 MB; an idle engine holds its weights and little
  else. One detection takes about 190 bytes of activations per pixel of the scaled page (500 MB at
  1440×2048). Weights are read-only and shared between sessions. `Session.fork()` gives a worker
  its own arena over the same graph.

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
- **Dense kernels on one thread** (the recognizer, the classifier, the detection of a page
  worker) run as im2col + GEMM: the windows of a band of output rows are laid out as columns, at
  most 2 MB of them at a time, and the weights multiply them through the same GEMM as a 1×1
  convolution. The four dense 2×2 and 3×3 convolutions at the start of the recognizer took a
  third of a line with per-tap loops and take half of that now.
- **Dense kernels with a thread pool** (the detection of a single page), **5×5 and depthwise
  kernels** are computed directly, with the output rows split over the threads.
- **Softmax** computes its exponentials with its own float `exp` (the Cephes reduction and
  polynomial, from multiplications and additions only), so the scalar and the vector path give
  the same bits for the output layer, whatever the vector width; the sum is taken in order.
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
  - Size: the longer side is limited to 2048 (`detLimitSideLen`), then rounded to a multiple of 32 with
    `Math.round` (half up). Python's half-to-even `round` can differ by 32 px.
  - The output gets a sigmoid when its values leave about `[0, 1.2]`.
- **Classifier**: input size comes from the model signature (80×160 for the default model).
- **CTC**: greedy, blank at 0, repeats collapsed. The score is the mean probability of the kept
  steps: for each character, the winner on the first timestep of its run. A second softmax is not applied when the output already sums to 1.
- **Fragments** (`dropFragments`, off by default): after the reading order is found, a line of
  at most 10 characters with mean character probability below 0.8 is left out. Removing it
  earlier changed column and row bounds on the test set, since the fragment is still ink on the
  page. The results barely moved between thresholds 0.7 and 0.9.
- **Word-list search** (`CtcWords`, only with word lists): each greedy letter run that no list
  holds gets a prefix beam search (width 16) over its own timesteps, from the end of the previous
  emitted character to the start of the next. Per timestep it tries the six most probable
  letters above 1e-4, their marked variants, the blank and the prefix's last letter; a prefix
  must start a listed word, and the result must have as many letters as the greedy word. The
  best listed word wins when its CTC probability (forward algorithm, every alignment) is at most
  4 nats below the greedy word's on the same timesteps. A letter with marks is scored at least
  e⁻³ times its plain letter (`Charset.plain()`: NFD base, plus ł, ø, đ, ħ), so one unseen mark
  fits in the margin. A run glued to a digit is skipped; before a hyphen a listed prefix counts
  as a word. `-Docr.ctcwords.trace=true` prints every searched word.
- **Charset**: one token per line. Empty lines are skipped, and one trailing CR and a BOM are
  removed. `useSpaceChar` appends the space class. With the default model there are 18710
  classes: blank, 18708 characters and the space.

## Reading order

1. **Level.** The median angle of the long lines is the page's skew. Between 0.3° and 20°,
   every box is rotated back by it before anything else, so a gutter that runs at a slant on a
   photo is vertical again. The output keeps the original boxes.
2. **Rows.** Lines become rows: a line joins a row when it overlaps the row's first box
   vertically by at least half the smaller height, unless the two boxes share more than half of
   the narrower one's width: such boxes are lines of one column stacked on a curved or dense
   page, not neighbours in a row. A tall vertical word stays out of this, so it cannot glue the
   page into one row.
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
     (receipt prices), a side where 60 % of the boxes are numbers (at least three digits, more
     digits than letters: the totals of a receipt, even when the total is printed larger and the
     amounts do not share an edge), and a block whose median row holds three or more boxes (a
     grid). The number rule applies to text columns (step 5) too.

**`Layout.ROWS`** skips steps 3 to 7 and the merged-line cuts: after leveling, rows are read top
to bottom. It is meant for streams of one-column documents.

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

**Gutters in the ink.** Before that geometric check, `InkGutters` looks at the page itself. Inside
each box it finds blank strips at least 0.4 box heights wide (a thin vertical rule with blank on
both sides counts as part of the strip; a narrow letter between two word spaces does not). Strips
from different lines are grouped around the densest x (within 0.3 median lines). A group is a gutter
when column margins stand next to it: ten lines ending within half a median line of it and ten
starting there (then one merged line is enough); or four gaps with five and five; or, where one
column is mostly merged, ten gaps with ten margin lines on one side and two on the other. Each box
with a gap in a gutter is cut there, in as many places as it crosses gutters, and its pieces are
read again. On a three-column 1912 weekly whose lines the detector merged over all columns, CER fell
from 0.528 to 0.024, and on an 1898 weekly with two merged lines from 0.754 to 0.009. A justified
page whose wide word spaces line up has no margins at those strips and stays whole.

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

`OcrPool` lends each concurrent caller a separate engine. The text is the same as with a single
engine. The engines share one `ModelSet` (the optimized graphs with their weights, the charset,
the word lists and the postal codes: 32 MB, 113 MB with the Polish lists), which is loaded once;
each engine adds only its sessions and worker threads. The thread count of the configuration is
the budget of the pool: every engine gets `threads / size` of it, at least one.

- `close` waits for running calls.
- Closing a borrowed engine removes that slot from the pool.

A multi-page document (`recognizePages`, which the PDF input of `ocr-pro-pdf` uses) stays on one
engine. Its pages run in parallel in a window as wide as the pool,
so at most that many bitmaps are in memory. Page workers run detection on their own thread, so
the pool never waits on itself.

**Heap budgets** (`MemoryBudget`) keep that parallel work inside the heap, whatever the number
of threads:

- Detection, in every engine of the JVM, takes 224 bytes per pixel of the scaled page from a
  budget of four tenths of the heap and waits when that is used up. Twelve threads on a 3 GB heap
  run one or two detections at a time, not twelve.
- The pages of one document take 8 bytes per pixel of the bitmap plus 96 MB for a recognizer from
  a budget of three tenths of the heap; the next page waits, already rendered, until an earlier
  one is done.
- A piece larger than its whole budget takes all of it, so the largest page still runs, alone.
- Sessions are lent last in, first out, so with few pages in flight only that many recognizers
  hold buffers.

Twelve A4 pages at 300 dpi (2472×3508) on twelve threads are read with a 1 GB heap; before the
budgets the same document ran out of memory with 9 GB.

## Modules and model files

- `ocr-pro` is the engine and has no runtime dependencies. A model file is a `ModelSource`: a path,
  or a name that a `ModelBundle` holds. Bundles are found with `ServiceLoader`, through the
  thread's context class loader and then the library's own. A bundled file is read with
  `getResourceAsStream` into memory, so it works from fat and nested JARs and nothing is extracted
  to disk.
- The four default files of `ocr-pro-models` must match the SHA-256 in `ModelFiles`. The check
  runs once per JVM and catches a bundle from another release before thresholds tuned for other
  weights read with it.
- The word lists of `Corrections.POLISH` are looked up next to the dict: in its directory, or in
  the bundles (`ocr-pro-models-pl`).
- `ocr-pro-pdf` renders pages with PDFBox and hands them to `OcrEngine.recognizePages`. The
  document is released right after its last page is rendered.

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

Where the time goes on a dense page (Congressional Record, 245 lines, 2048 px detection,
12 threads, `-Docr.profile=true` summed over all runs): recognition takes about 85 % of the wall
time. Of the CPU time, 1×1 convolutions of the recognizer take 41 %, its other dense convolutions
19 %, and the last layer over 18,710 classes (MatMul, Add, Softmax) 12 %. More threads keep
helping on this 2P + 8E laptop (4 threads 30 s, 8 threads 22 s, 12 threads 18 s), so the work is
CPU-bound; virtual threads would not add anything. GC pauses total about 0.1 s.

- `Alphabet.LATIN` cuts that last layer to 2,440 classes when the model is loaded: 8–18 % less
  time over a benchmark set, up to a quarter on pages with many lines, the same accuracy.
- `ReduceMean` over a contiguous range of axes and the 2×2 stride-1 `MaxPool` have their own loops,
  10× and 3× faster than the general ones, with identical results.
- With more than one thread, the scores of recognized lines can differ in the last bit from run to
  run; the text does not. One thread gives the same output every time.

For timings on real documents next to Tesseract and RapidOCR, see [benchmark.md](benchmark.md).

## Tests

- Operators against hand-computed values (about 1e-4).
- `OperatorFuzzTest`: random Conv, ConvTranspose, pooling, Slice, broadcast arithmetic, MatMul,
  Transpose, ReduceMean, Softmax and nearest Resize against plain references in double precision,
  on the scalar and the vector path, alone and with a thread pool; and random convolution chains
  (BatchNorm, bias, activations, scalar affine) raw against optimized. Every buffer of the arena
  is handed out full of NaN (`Session.poisoned`), so an element a kernel leaves unwritten fails
  the comparison. Fixed seeds, about ten seconds.
- Official ONNX files: operator counts, CLS input 80×160, REC input height 48, REC output 18710.
- Raw and fused detection graphs agree within 1e-3.
- A synthetic invoice rendered with DejaVu: CER below 0.25, and `Zażółć gęślą jaźń` is present.
- Empty and 1×1 images, `null`, a vertical `FAKTURA`, EXIF orientations 1–8.
- PDFs: one page, several pages in order, empty, encrypted (`ocr-pro-pdf`).
- The bundled models, with and without the Polish word lists, read the same as `models/`.
- Three real scans (`QualityGateTest`): an edit ceiling for the plain pipeline and one for
  `Corrections.POLISH`. These are the tuning scans, so they guard against regressions. They do
  not measure accuracy; [benchmark.md](benchmark.md) does.
- Tests that need `models/` are skipped when the directory is missing.
