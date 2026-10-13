# Benchmark

The engine's rules and thresholds were first tuned on three scans
(`src/test/resources/quality/`). Measuring on those scans only shows how well the rules fit
them. This benchmark uses public documents with references transcribed by hand, in two sets:

| Set | Documents | Role |
| --- | --- | --- |
| `dev` | 20 (10 PL, 10 EN), 13,777 characters | Used to find and fix problems. Its numbers are **no longer independent**. |
| `test` | 27 (18 PL, 9 EN), 59,772 characters | Collected after the last fix and scored once. **Do not tune on it.** |

## Rules for using the sets

- **Find and fix on `dev`, report on `test`.** A rule or threshold changed because of a failure
  in `test` turns `test` into tuning data. The next release then needs new test documents, and
  the docs must say which documents were spent.
- **References come from the image only.**
  - `dev` references were written from the image alone, without any OCR tool.
  - Some `test` pages come from Wikisource and other proofread transcriptions. They were used as
    a draft and rebuilt line by line against the scan; where the print differs, the print wins.
  - Disputed spots are listed in the manifest notes.

## The documents

`bench/manifest.tsv` lists every document with its source URL, SHA-256, license, author and
set. The page images are stored in `bench/data/` (about 75 MB). Pages of PDF and DjVu documents
are stored as lossless PNG renders, next to their originals (about 90 MB). `bench/fetch.sh` checks
the digests and renders a page again when its image is missing. The references are in
`bench/ref/dev/` and `bench/ref/test/` and carry the same attribution.

| Kind | dev | test |
| --- | --- | --- |
| invoices, bills, receipts, tickets | 3 PL, 3 EN | 6 PL (a 2019 utility bill, a 1931 mint bill, a 2023 housing bill with its amounts blanked, a blank 1948 invoice form, two receipts), 2 EN (receipt, 1932 telephone bill) |
| letters, official documents | 2 PL, 2 EN | 2 PL (one born-digital PDF), 1 EN |
| forms, certificates | 1 PL, 1 EN | 1 EN (born-digital PDF) |
| two-column pages | 1 PL | 7 PL (encyclopedia, Bible, dictionary, two Dziennik Ustaw pages, a leaflet, a poster photo), 3 EN (magazine, journal, newspaper) |
| other pages and notices | 3 PL, 4 EN | 2 PL (price list, two single-column book pages), 2 EN |

### Transcription rules

- Exact characters as printed: case, diacritics, punctuation, and the document's own typos.
- **Reading order:**
  - natural order, top to bottom;
  - text on one visual row is joined left to right;
  - a two-column text block gives the whole left column, then the right one;
  - a title across both columns sits where it is printed;
  - tables go row by row.
- Handwriting, signatures, rubber stamps, logos, illustrations and bleed-through are left out. An
  engine that reads them pays for the extra characters. All engines pay alike, but the absolute
  numbers go up.

### Metrics

Both texts are normalized first:

- NFC normalization;
- typographic quotes, apostrophes and dashes become ASCII;
- every run of whitespace becomes one space.

Two numbers are reported:

- **CER**: Levenshtein edits divided by the reference length. It depends on reading order: a page
  read in the wrong column order costs most of its characters.
- **Word F1**: reference and output words matched as multisets. It does not depend on order, so
  it measures recognition separately from layout.

"Pooled" figures sum the edits of all documents, so long pages weigh more. "Median" figures take
the median over documents.

## Engines

| Engine | Setup |
| --- | --- |
| **ocr-pro** | `OcrConfig.defaults` (`Corrections.NONE`), 12 threads, Vector API on |
| **ocr-pro + polish** | the same with `--corrections polish` and the word lists from `models/` |
| **ocr-pro, earlier** | commit `e73a11d`: the reading order and corrections before the fixes described below |
| **RapidOCR** | rapidocr 3.10.0 on onnxruntime 1.31.0, running **the same ONNX files** with the same DB settings (limit 960, threshold 0.35), see `bench/run-rapidocr.py` |
| **Tesseract** | 5.5.3 with `pol` or `eng` matching the document, default page segmentation, see `bench/run-tesseract.sh` |

All engines ran on one laptop: Intel Core i7-1365U (2 performance + 8 efficiency cores,
12 threads), Linux, OpenJDK 21.0.12. The measurement date was 2026-10-13.

## Results on `test`

| Engine | CER pooled (pl / en / all) | CER median | Word F1 pooled (pl / en / all) | Median page time |
| --- | --- | --- | --- | --- |
| ocr-pro | 0.269 / **0.083** / 0.214 | **0.087** | 0.893 / **0.960** / 0.915 | 3.6 s |
| ocr-pro + polish | 0.269 / **0.083** / 0.214 | **0.087** | **0.901** / 0.959 / **0.920** | 3.5 s |
| ocr-pro, earlier | 0.520 / 0.524 / 0.521 | 0.205 | 0.893 / 0.960 / 0.915 | |
| RapidOCR (same models) | 0.514 / 0.514 / 0.514 | 0.221 | 0.890 / 0.951 / 0.911 | 3.3 s |
| Tesseract 5 | **0.237** / 0.148 / **0.210** | 0.149 | 0.840 / 0.883 / 0.854 | **2.3 s** |

The `test` pages are longer than the `dev` pages, about 2,200 characters against 700, so the page
times are higher.

Per document, as CER / word F1:

| Document | ocr-pro | + polish | earlier | RapidOCR | Tesseract |
| --- | --- | --- | --- | --- | --- |
| en-bill-nb-telephone-1932 | 0.205 / 0.876 | 0.208 / 0.876 | 0.205 / 0.876 | 0.309 / 0.867 | 0.464 / 0.642 |
| en-form-bill-of-sale-reg135 | 0.029 / 0.962 | 0.029 / 0.962 | 0.029 / 0.962 | 0.032 / 0.964 | 0.039 / 0.924 |
| en-journal-nature-1896-p419 | 0.380 / 0.995 | 0.380 / 0.995 | 0.660 / 0.995 | 0.613 / 0.956 | 0.429 / 0.949 |
| en-magazine-outing-v45-developers | 0.002 / 0.993 | 0.002 / 0.993 | 0.711 / 0.993 | 0.711 / 0.995 | 0.003 / 0.991 |
| en-memo-conference-president-1954 | 0.076 / 0.960 | 0.076 / 0.960 | 0.076 / 0.960 | 0.095 / 0.947 | 0.051 / 0.939 |
| en-newspaper-aparri-news-1900 | 0.029 / 0.935 | 0.029 / 0.931 | 0.752 / 0.935 | 0.742 / 0.925 | 0.197 / 0.774 |
| en-poster-coalbrookdale-auction-1910 | 0.217 / 0.944 | 0.217 / 0.944 | 0.217 / 0.944 | 0.141 / 0.931 | 0.075 / 0.861 |
| en-programme-toledo-symphony-1921 | 0.087 / 0.853 | 0.087 / 0.853 | 0.087 / 0.853 | 0.131 / 0.844 | 0.136 / 0.778 |
| en-receipt-fanzz-2002 | 0.138 / 0.939 | 0.136 / 0.948 | 0.138 / 0.939 | 0.014 / 0.922 | 1.000 / 0.000 |
| pl-biblia-wujek-1923-s296 | 0.433 / 0.962 | 0.433 / 0.965 | 0.753 / 0.962 | 0.753 / 0.941 | 0.434 / 0.972 |
| pl-cennik-nr7-poznan-1937-s67 | 0.155 / 0.635 | 0.154 / 0.643 | 0.155 / 0.635 | 0.221 / 0.604 | 0.313 / 0.429 |
| pl-dziennik-ustaw-1952-s374 | 0.453 / 0.877 | 0.454 / 0.883 | 0.748 / 0.877 | 0.750 / 0.875 | 0.057 / 0.845 |
| pl-dziennik-ustaw-1994-s302 | 0.002 / 0.995 | 0.003 / 0.986 | 0.726 / 0.995 | 0.726 / 0.980 | 0.001 / 0.991 |
| pl-encyklopedja-staropolska-1900-s42 | 0.008 / 0.950 | 0.008 / 0.950 | 0.716 / 0.950 | 0.713 / 0.957 | 0.408 / 0.981 |
| pl-faktura-pge-specyfikacja-2019 | 0.139 / 0.776 | 0.138 / 0.792 | 0.135 / 0.776 | 0.159 / 0.795 | 0.273 / 0.594 |
| pl-obwieszczenie-stan-wojenny-1981 | 0.669 / 0.824 | 0.668 / 0.853 | 0.669 / 0.824 | 0.620 / 0.831 | 0.493 / 0.849 |
| pl-odezwa-zhp-lodz-1935 | 0.034 / 0.957 | 0.032 / 0.971 | 0.777 / 0.957 | 0.767 / 0.965 | 0.009 / 0.967 |
| pl-paragon-castorama-2021 | 0.038 / 0.794 | 0.038 / 0.794 | 0.072 / 0.794 | 0.048 / 0.762 | 0.137 / 0.748 |
| pl-paragon-inmedio-2024 | 0.035 / 0.821 | 0.033 / 0.821 | 0.035 / 0.821 | 0.046 / 0.810 | 0.149 / 0.495 |
| pl-pismo-msz-ambasada-palestyny-2010 | 0.147 / 0.906 | 0.146 / 0.914 | 0.147 / 0.906 | 0.125 / 0.921 | 0.105 / 0.819 |
| pl-pismo-umwp-2026 | 0.028 / 0.962 | 0.026 / 0.979 | 0.010 / 0.962 | 0.011 / 0.962 | 0.013 / 0.975 |
| pl-rachunek-hurtownia-slupsk-1948 | 0.317 / 0.750 | 0.317 / 0.758 | 0.317 / 0.750 | 0.246 / 0.755 | 0.683 / 0.612 |
| pl-rachunek-mennica-1931 | 0.464 / 0.747 | 0.466 / 0.744 | 0.464 / 0.747 | 0.507 / 0.708 | 0.683 / 0.489 |
| pl-slownik-staropolski-arct-s479 | 0.735 / 0.909 | 0.735 / 0.918 | 0.740 / 0.909 | 0.740 / 0.939 | 0.726 / 0.571 |
| pl-slownik-uczonych-zydow-1905-s11 | 0.007 / 0.973 | 0.007 / 0.971 | 0.007 / 0.973 | 0.012 / 0.959 | 0.010 / 0.980 |
| pl-statut-zhp-1936-s8 | 0.011 / 0.914 | 0.012 / 0.915 | 0.011 / 0.914 | 0.015 / 0.930 | 0.024 / 0.910 |
| pl-zaliczka-wspolnota-bialystok-2023 | 0.018 / 0.902 | 0.016 / 0.908 | 0.047 / 0.902 | 0.054 / 0.902 | 0.159 / 0.701 |

## Results on `dev`

These documents were used for the fixes below, so the gain on them is expected. They are shown
for completeness.

| Engine | CER pooled (pl / en / all) | CER median | Word F1 pooled (pl / en / all) | Median page time |
| --- | --- | --- | --- | --- |
| ocr-pro | 0.052 / 0.059 / 0.056 | 0.058 | 0.895 / 0.958 / 0.930 | 1.6 s |
| ocr-pro + polish | 0.049 / 0.058 / 0.053 | 0.056 | 0.919 / 0.959 / 0.941 | 1.4 s |
| ocr-pro, earlier | 0.208 / 0.059 / 0.133 | 0.062 | 0.895 / 0.958 / 0.930 | |
| RapidOCR (same models) | 0.203 / 0.065 / 0.134 | 0.050 | 0.890 / 0.960 / 0.928 | 2.1 s |
| Tesseract 5 | 0.146 / 0.139 / 0.142 | 0.143 | 0.833 / 0.858 / 0.846 | 1.4 s |

## What changed, and whether it held up on `test`

Three problems were found on `dev` and fixed there. `test` was collected afterwards.

1. **Reading order.** On the low-resolution newspaper, the padded detection boxes of the two
   columns touched, and a title over both columns hid the gutter. On the train ticket, a form
   was split into two columns that do not exist. The gutter is now searched on boxes without
   their padding, a title ends a column section, and a split needs both sides to look like text
   columns (see [internals.md](internals.md#reading-order)).
   - **On `test`:** the pooled CER fell from 0.521 to 0.214, and the median from 0.205 to 0.087.
     For example, the Outing magazine went from 0.711 to 0.002, Dziennik Ustaw 1994 from 0.726
     to 0.002, the encyclopedia from 0.716 to 0.008, and the Aparri newspaper from 0.752
     to 0.029.
   - **A small loss:** a two-line letter footer with two blocks side by side is now read block by
     block (0.010 to 0.028). This is the same geometry as the seller and buyer blocks of an
     invoice, which should be read block by block.
2. **Polish corrections.** Capital words got an inserted letter (ZWIĄZEK became ZAWIĄZEK), the
   dictionary rewrote abbreviations (*Przewod.* became *Przewód.*), and a sentence-final period in
   English became a comma. All three are fixed (see [corrections.md](corrections.md)).
   - **On `test`:** the corrections give +0.8 points of Polish word F1 (0.893 to 0.901), with CER
     unchanged.
   - **A small cost:** they lose 0.4 points on one English newspaper.
3. **No Polish invoice in the set.** `test` now has four Polish invoices or bills and two
   receipts. Only two of the invoices carry filled-in amounts: the 2019 utility bill and the
   1931 mint bill.

## What the numbers say

- **Recognition matches ONNX Runtime.** ocr-pro and RapidOCR run the same models, and their word
  F1 is within one point on both sets.
- **ocr-pro now has the lowest median CER** of the four engines on `test`, 0.087 against 0.149
  for Tesseract. On pooled CER, Tesseract is level (0.210 against 0.214). Most of that comes from
  two long pages it orders better: the noisy 1952 Dziennik Ustaw and the 1981 poster.
- **Two-column pages that still fail.** On these pages the gutter is not found or not accepted:
  - Arct's dictionary (0.735),
  - the 1981 poster photo (0.669),
  - the noisy 1952 Dziennik Ustaw (0.453; Tesseract 0.057),
  - the Bible page with chapter summaries (0.433),
  - the Nature page split by a figure (0.380).

  They are the obvious next step, but finding them used `test`. A fix based on them needs new
  test documents.
- **Thermal receipts and photos** favor ocr-pro and RapidOCR over Tesseract by a wide margin.
  Tesseract returns nothing for the crumpled blue dot-matrix receipt.
- **Speed is similar to RapidOCR and Tesseract** on this laptop. Repeated runs vary by about
  ±20 %, so treat the times as an order of magnitude, not a ranking.

## Reproduce

```bash
mvn -DskipTests package
# The images are in bench/data. bench/fetch.sh rebuilds a missing one from its source
# (curl; pdftoppm for PDF; ddjvu and pnmtopng, ImageMagick or Pillow for DjVu).

./ocr.sh score bench/ref/test bench/data --out bench/out/test-plain
./ocr.sh score bench/ref/test bench/data --corrections polish --out bench/out/test-polish

pip install rapidocr==3.10.0 onnxruntime==1.31.0
python bench/run-rapidocr.py bench/ref/test bench/data bench/out/test-rapidocr models
./ocr.sh score bench/ref/test --hyp bench/out/test-rapidocr

bench/run-tesseract.sh bench/ref/test bench/data bench/out/test-tesseract   # tesseract with pol and eng
./ocr.sh score bench/ref/test --hyp bench/out/test-tesseract
```

Replace `test` with `dev` for the other set.

- `score` prints one row per document (characters, CER, word F1, ms), then totals per name prefix
  (`pl`, `en`) and overall.
- Times from `score` exclude image decoding and include one warm-up page.
- The RapidOCR script times its call after a warm-up.
- The Tesseract script times the whole process.
