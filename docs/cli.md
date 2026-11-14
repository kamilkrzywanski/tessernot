# Command line

`ocr.sh` runs the runnable JAR of `ocr-pro-cli` with `--add-modules jdk.incubator.vector`, using
`$JAVA_HOME/bin/java` when `JAVA_HOME` is set and `java` from `PATH` otherwise. It keeps the
caller's working directory, so relative paths work. It passes `--model <dir next to the script>/models`
when you leave `--model` out. The JAR itself bundles the default models and the Polish word lists:
run on its own without `--model`, it uses those, and `download` writes to `./models`.

```text
ocr download [dir]
ocr ocr <image> [--model dir] [--limit N] [--no-cls] [--no-det] [--threads N] [--corrections none|polish] [--layout auto|rows] [--alphabet all|latin] [--words file,...] [--json] [--searchable out.pdf [--dpi N]] [--hocr out.html]
ocr pdf <file> [--dpi N] [--model dir] [--limit N] [--no-cls] [--no-det] [--threads N] [--corrections none|polish] [--layout auto|rows] [--alphabet all|latin] [--words file,...] [--json] [--searchable out.pdf] [--hocr out.html]
ocr bench <image> [--model dir] [--limit N] [--no-cls] [--no-det] [--threads N] [--corrections none|polish] [--layout auto|rows] [--alphabet all|latin] [--words file,...]
ocr score <ref-dir> <image-dir> [--corrections none|polish] [--layout auto|rows] [--alphabet all|latin] [--words file,...] [--out dir] [--model dir] [--limit N] [--threads N]
ocr score <ref-dir> --hyp <dir>
ocr convert <in.onnx> <out.jir>
ocr ops <model> [--raw]
ocr selfcheck
ocr synth <out.png>
ocr eval [--model dir]
```

| Command | What it does |
| --- | --- |
| `download` | Fetches the default ONNX files and dictionary from ModelScope and checks SHA-256. A file that already has the right digest is not fetched again. |
| `ocr` | One image. Page text to stdout, then each table (a ruled grid, or rows set in three or more aligned columns) as `--- table N ---` with tab-separated cells. Timings go to stderr. |
| `pdf` | Every page of a PDF, rendered with PDFBox at `--dpi` (default 150). With more than one page, each page starts with `--- page N ---` and is printed as soon as it is done. |
| `bench` | One warm-up and three timed runs on one image. Prints the minimum and the median. |
| `score` | Held-out benchmark. Compares page text with references, or scores ready outputs of another engine with `--hyp`. See [benchmark.md](benchmark.md). |
| `convert` | Writes the optimized graph as JIR, which loads faster than ONNX. |
| `ops` | Opset, node count and operator histogram of a model (`--raw` skips graph optimization). |
| `selfcheck` | A one-node graph through the runtime. Prints whether the vector backend is active. |
| `synth`, `eval` | A synthetic invoice image, and its CER. `eval` exits with 1 above CER 0.35. |

## Flags

- `--model dir`: the model directory. A path to a file, or an empty `--model=`, is an error.
- `--limit N`: longest side for detection (default 2048). 960 is about twice as fast on a large page and needs less heap, at a cost in accuracy on small print, dense columns and receipts.
- `--threads N`: worker threads (default: all cores).
- `--no-cls`, `--no-det`: skip the 180° classifier, or skip detection and read the image as one line.
- `--corrections none|polish`: see [corrections.md](corrections.md). The default is `none`.
- `--layout auto|rows`: `auto` (the default) finds columns, side-by-side blocks and titles across
  columns. `rows` reads every page row by row with no layout analysis, for streams of
  one-column documents such as receipts, where a column can never be found by mistake.
- `--alphabet all|latin`: `latin` cuts the recognizer's last layer to Latin and Greek letters,
  digits, punctuation and common symbols (2,440 of 18,710 classes) when the model is loaded.
  Same accuracy on Latin-script documents, faster on pages with many lines. The default is `all`.
- `--words a.txt,b.txt.gz`: word lists in any language, UTF-8, one word per line, plain or gzip,
  in any order and case. An unlisted word is read as a listed one with the same number of letters
  when the image supports that spelling almost as well. See the README, "Word lists in any
  language". Works with `--corrections none` too.
- `--keep-fragments`: keeps lines of at most 10 characters whose mean character confidence is
  below 0.8. By default they are left out, after the reading order is found: those are mostly
  stamps, ornaments read as CJK characters, and stray marks, but also real short text the
  recognizer is unsure of, such as an `X` in a check box or a misread page number.
  `--drop-fragments` is still accepted and says what the default does.
- `--json`: one JSON document instead of text (see below).
- `--dpi N`: PDF render resolution (150 when not given). On `ocr` it goes with `--searchable`
  only: the resolution of the image, which sets the size of the page (300 when not given).
- `--hocr out.html`: besides the usual output, writes the page (`ocr`) or the document (`pdf`,
  one `ocr_page` per page) as hOCR: XHTML with a `bbox` on every line and word, in pixels of the
  picture, and `textangle` on a line that does not run level. Tables and scores are not in it.
- `--searchable out.pdf`: besides the usual output, writes a PDF that looks like the input and
  can be searched. The text of every line lies under the line, invisible, so a viewer can find,
  select and copy it. A PDF keeps its pages and everything on them; a PDF that already has text
  gets a second layer. An image becomes one page. `out.pdf` may be the input PDF, which is
  replaced when the new file is complete. The text is exact at the ends of a line and
  approximate inside it; a signature on the input PDF does not stay valid.

Flags accept `--flag value` and `--flag=value`. A switch (`--no-cls`, `--no-det`, `--raw`,
`--json`, `--keep-fragments`, `--drop-fragments`) never consumes the next argument. `--json=false` turns it off.

## Input checks

Bad input fails with exit code 1 and a message, before any model or file is opened. This covers:

- an unknown flag, a repeated flag, a flag the command does not use, or a flag without a value
- `--searchable` or `--hocr` with a directory, or a file in a directory that does not exist
- a non-numeric, zero or negative number
- an extra positional argument

Before the image is read, missing or empty model files are reported with a pointer to
`ocr download`.

- JPEG input is turned upright from EXIF Orientation (1–8). PNG and `BufferedImage` input are not rotated.
- A page that lies on its side or upside down, with or without an EXIF tag, is found from its
  lines and read turned. A page scanned askew is turned level first.
- A multi-image TIFF or GIF is rejected with the image count. A JPEG with more data behind the
  picture (a gain map, a motion clip) is read as that one picture.
- Images are decoded in memory; no temporary file is written.
- A file with `%PDF-` in its first kilobyte is sent to `ocr pdf`, unless it starts as PNG, JPEG, GIF, BMP or TIFF.
- A PDF with a user password is rejected before the model loads. A PDF with an empty user password and an owner password opens.
- A picture that would take more than three tenths of the heap on its own is read at half its
  size, or a third, until it fits; a small page with small print is enlarged before it is read.
  Quads are in the pixels of the picture as given either way. A picture the decoder cannot hold
  fails with a message that names its size and the heap.
- An image whose packed BGR would not fit one Java array fails with `image is too large`.

## JSON

`--json` on `ocr` prints one page object. On `pdf` it prints `{"pages":[...]}` in page order.

```json
{"text":"...","lines":[{"text":"...","score":0.98,"detScore":0.91,
  "quad":[x0,y0,x1,y1,x2,y2,x3,y3],"bounds":{"x":0,"y":0,"width":0,"height":0},
  "words":[{"text":"...","quad":[x0,y0,x1,y1,x2,y2,x3,y3]}]}],
  "tables":[{"bounds":{...},"ruled":true,"cells":[["a","b"],["","c"]]}],
  "rotation":0,"width":2480,"height":3508}
```

- Keys always come in this order.
- `quad` is top-left, top-right, bottom-right, bottom-left in image pixels.
- `words` are the words of the line, each with the strip of the line's box where it was read;
  the corners of a word's quad are in the order of its text. The list is empty when the places
  are not known: the line's text was changed after it was read (`--corrections polish` does that
  to some lines).
- An empty cell is `""`.
- `ruled` is true for a grid of printed rules and false for a table found from columns with no rules.
- `width` and `height` are the size of the picture in pixels: what the quads are measured in.
- `rotation` is 0, or 90, 180 or 270 when the page lay on its side or upside down and was read
  turned: the degrees clockwise that set the picture upright. Quads and bounds are in the pixels
  of the picture as given either way, with the corners of a quad in the order of the text.
- A non-finite number is `null`.
- Timings stay on stderr.
- `OcrJson.page` and `OcrJson.document` produce the same bytes from Java.

Times, CER and `selfcheck` output always use a decimal point, whatever the locale.
