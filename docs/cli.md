# Command line

`ocr.sh` runs the shaded JAR with `--add-modules jdk.incubator.vector`. It keeps the caller's working
directory, so relative paths work. It passes `--model <dir next to the script>/models` when you
leave `--model` out.

```text
ocr download [dir]
ocr ocr <image> [--model dir] [--limit N] [--no-cls] [--no-det] [--threads N] [--corrections none|polish] [--json]
ocr pdf <file> [--dpi N] [--model dir] [--limit N] [--no-cls] [--no-det] [--threads N] [--corrections none|polish] [--json]
ocr bench <image> [--model dir] [--limit N] [--no-cls] [--no-det] [--threads N] [--corrections none|polish]
ocr score <ref-dir> <image-dir> [--corrections none|polish] [--out dir] [--model dir] [--limit N] [--threads N]
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
| `ocr` | One image. Page text to stdout, then each ruled table as `--- table N ---` with tab-separated cells. Timings go to stderr. |
| `pdf` | Every page of a PDF, rendered with PDFBox at `--dpi` (default 150). With more than one page, each page starts with `--- page N ---` and is printed as soon as it is done. |
| `bench` | One warm-up and three timed runs on one image. Prints the minimum and the median. |
| `score` | Held-out benchmark. Compares page text with references, or scores ready outputs of another engine with `--hyp`. See [benchmark.md](benchmark.md). |
| `convert` | Writes the optimized graph as JIR, which loads faster than ONNX. |
| `ops` | Opset, node count and operator histogram of a model (`--raw` skips graph optimization). |
| `selfcheck` | A one-node graph through the runtime. Prints whether the vector backend is active. |
| `synth`, `eval` | A synthetic invoice image, and its CER. `eval` exits with 1 above CER 0.35. |

## Flags

- `--model dir`: the model directory. A path to a file, or an empty `--model=`, is an error.
- `--limit N`: longest side for detection (default 960). Use 736 for speed, 1280–1536 for small print.
- `--threads N`: worker threads (default: all cores).
- `--no-cls`, `--no-det`: skip the 180° classifier, or skip detection and read the image as one line.
- `--corrections none|polish`: see [corrections.md](corrections.md). The default is `none`.
- `--json`: one JSON document instead of text (see below).
- `--dpi N`: PDF render resolution.

Flags accept `--flag value` and `--flag=value`. A switch (`--no-cls`, `--no-det`, `--raw`,
`--json`) never consumes the next argument. `--json=false` turns it off.

## Input checks

Bad input fails with exit code 1 and a message, before any model or file is opened. This covers:

- an unknown flag, a repeated flag, a flag the command does not use, or a flag without a value
- a non-numeric, zero or negative number
- an extra positional argument

Before the image is read, missing or empty model files are reported with a pointer to
`ocr download`.

- JPEG input is turned upright from EXIF Orientation (1–8). PNG and `BufferedImage` input are not rotated.
- A multi-image TIFF or GIF is rejected with the image count.
- A file with `%PDF-` in its first kilobyte is sent to `ocr pdf`, unless it starts as PNG, JPEG, GIF, BMP or TIFF.
- A PDF with a user password is rejected before the model loads. A PDF with an empty user password and an owner password opens.
- An image whose packed BGR would not fit one Java array fails with `image is too large`.

## JSON

`--json` on `ocr` prints one page object. On `pdf` it prints `{"pages":[...]}` in page order.

```json
{"text":"...","lines":[{"text":"...","score":0.98,"detScore":0.91,
  "quad":[x0,y0,x1,y1,x2,y2,x3,y3],"bounds":{"x":0,"y":0,"width":0,"height":0}}],
 "tables":[{"bounds":{...},"cells":[["a","b"],["","c"]]}]}
```

- Keys always come in this order.
- `quad` is top-left, top-right, bottom-right, bottom-left in image pixels.
- An empty cell is `""`.
- A non-finite number is `null`.
- Timings stay on stderr.
- `OcrJson.page` and `OcrJson.document` produce the same bytes from Java.

Times, CER and `selfcheck` output always use a decimal point, whatever the locale.
