# Held-out benchmark

The rules in [corrections.md](corrections.md) and the thresholds in the engine were tuned on
three scans. Measuring on those scans would only show how well the rules fit them. This set is
made of **documents the engine was never tuned on**, with references transcribed by hand,
to answer a different question: how does the engine do on a document it has not seen?

## Rules for using this set

- **Do not tune on it.** Changing a rule or threshold because of a failure here turns these
  documents into tuning data. When a decision is based on this set, add new documents before
  the next measurement, and say which documents were already spent.
- The references were written by looking at the image only, without running any OCR tool.
  Disputed spots were checked against the image again, and are listed in the manifest notes.

## The set

There are 20 public documents from Wikimedia Commons: 10 Polish and 10 English. Together they
have 13,777 characters after normalization. `bench/manifest.tsv` lists each file with its URL,
SHA-256, license and author. The images are not stored in this repository.
`bench/fetch.sh` downloads them and checks the digests. The references in `bench/ref/` are
transcriptions of those documents and carry the same attribution.

| Kind | Polish | English |
| --- | --- | --- |
| receipts, tickets, invoices | thermal receipt (photo), thermal receipt (scan), dot-matrix train ticket | fuel receipt, parking ticket (photo), parcel invoice letter (phone photo) |
| letters | typewritten ministry circular, 1935; modern church letter (photo) | typewritten telegram 1946; Western Union telegram 1962 (stained) |
| forms, certificates | printed diploma | italic certificate on stained paper (photo) |
| books, newspapers | 1921 manual page; **two-column** 1944 newspaper (691×734 px) | *Alice in Wonderland* page around an illustration; degraded 1831 newspaper ad |
| notices, other | 1950 poster; outdoor sign (photo) | executive order 1942 (typewritten); outdoor sign (photo) |

### Transcription rules

- Exact characters as printed: case, diacritics, punctuation, and the document's own typos.
- Natural reading order. A two-column text gives the whole left column, then the right one.
  Text on one visual row is joined left to right.
- Handwriting, signatures, rubber stamps, logos and bleed-through are left out.
  An engine that reads them pays for the extra characters. This costs all engines alike, but it
  raises the absolute numbers.

### Metrics

Both texts are normalized first: NFC; typographic quotes, apostrophes and dashes become ASCII;
every run of whitespace becomes one space.

- **CER**: Levenshtein edits divided by the reference length. It depends on reading order, so a
  different column order costs a lot of CER.
- **Word F1**: the reference and output words, matched as multisets. It does not depend on
  order, so it measures recognition separately from layout.

The "pooled" figures add up the edits of all documents, so long documents weigh more. The
"median" figures take the median over documents.

## Engines

| Engine | Setup |
| --- | --- |
| **ocr-pro** | This repository, `OcrConfig.defaults` (`Corrections.NONE`), 12 threads, Vector API on. |
| **ocr-pro + polish** | The same with `--corrections polish` and the word lists from `models/`. |
| **RapidOCR** | rapidocr 3.10.0 on onnxruntime 1.31.0, running **the same ONNX files** with the same DB settings (limit 960, threshold 0.35). See `bench/run-rapidocr.py`. |
| **Tesseract** | 5.5.3 with `pol` or `eng` matching the document, default page segmentation. See `bench/run-tesseract.sh`. |

All four ran on one laptop: Intel Core i7-1365U (2 performance + 8 efficiency cores,
12 threads), Linux, OpenJDK 21.0.12, measured 2026-10-13.

## Results

| Engine | CER pooled (pl / en / all) | CER median | Word F1 pooled (pl / en / all) | Median page time |
| --- | --- | --- | --- | --- |
| ocr-pro | 0.208 / 0.059 / 0.133 | 0.062 | 0.895 / 0.958 / 0.930 | 2.05 s |
| ocr-pro + polish | 0.206 / 0.058 / 0.132 | 0.058 | **0.916** / 0.958 / **0.939** | 2.06 s |
| RapidOCR (same models) | 0.203 / 0.065 / 0.134 | **0.050** | 0.890 / **0.960** / 0.928 | 3.02 s |
| Tesseract 5 | **0.146** / 0.139 / 0.143 | 0.143 | 0.833 / 0.858 / 0.846 | 2.05 s |

Per document, as CER / word F1:

| Document | ocr-pro | + polish | RapidOCR | Tesseract |
| --- | --- | --- | --- | --- |
| en-book-alice-p88 | 0.007 / 0.974 | 0.007 / 0.974 | 0.007 / 0.974 | 0.003 / 0.985 |
| en-certificate-tedder-1945 | 0.082 / 0.943 | 0.063 / 0.952 | 0.145 / 0.952 | 0.123 / 0.887 |
| en-executive-order-9066-1942 | 0.012 / 0.983 | 0.012 / 0.983 | 0.013 / 0.978 | 0.016 / 0.958 |
| en-invoice-parcelforce-2020 | 0.009 / 0.995 | 0.009 / 0.995 | 0.029 / 0.998 | 0.071 / 0.950 |
| en-newspaper-british-packet-1831 | 0.172 / 0.844 | 0.174 / 0.828 | 0.174 / 0.828 | 0.042 / 0.800 |
| en-notice-occupiers-liability-ireland | 0.005 / 0.960 | 0.005 / 0.960 | 0.016 / 0.960 | 0.234 / 0.764 |
| en-receipt-shell-2006 | 0.053 / 0.988 | 0.053 / 0.988 | 0.015 / 0.977 | 0.164 / 0.779 |
| en-telegram-ho-chi-minh-1946 | 0.077 / 0.931 | 0.077 / 0.931 | 0.077 / 0.939 | 0.132 / 0.835 |
| en-telegram-jfk-1962 | 0.177 / 0.922 | 0.177 / 0.922 | 0.183 / 0.931 | 0.511 / 0.564 |
| en-ticket-parking-albert-park-2008 | 0.085 / 0.959 | 0.085 / 0.959 | 0.021 / 0.959 | 0.058 / 0.794 |
| pl-bilet-pkp-ic-2009 | 0.423 / 0.742 | 0.419 / 0.781 | 0.106 / 0.747 | 0.194 / 0.659 |
| pl-dyplom-zps-2017 | 0.205 / 0.725 | 0.202 / 0.747 | 0.207 / 0.652 | 0.154 / 0.792 |
| pl-instrukcja-titan-1921-s78 | 0.005 / 0.966 | 0.001 / 0.992 | 0.043 / 0.958 | 0.235 / 0.955 |
| pl-kurier-stoleczny-nekrolog-1944 | 0.718 / 0.947 | 0.719 / 0.971 | 0.719 / 0.957 | 0.158 / 0.900 |
| pl-paragon-bar-szalas-zory | 0.027 / 0.826 | 0.022 / 0.867 | 0.032 / 0.790 | 0.466 / 0.533 |
| pl-paragon-tpn-2021 | 0.010 / 0.917 | 0.010 / 0.917 | 0.010 / 0.917 | 0.184 / 0.691 |
| pl-pismo-kuria-czestochowa | 0.070 / 0.941 | 0.072 / 0.929 | 0.058 / 0.952 | 0.022 / 0.988 |
| pl-pismo-msz-1935 | 0.073 / 0.847 | 0.069 / 0.873 | 0.088 / 0.859 | 0.064 / 0.824 |
| pl-regulamin-lowiska | 0.032 / 0.795 | 0.019 / 0.886 | 0.183 / 0.795 | 0.222 / 0.667 |
| pl-zawiadomienie-unia-proch-1950 | 0.003 / 0.975 | 0.004 / 0.967 | 0.034 / 0.949 | 0.024 / 0.885 |

## What the numbers say

1. **The Java runtime matches ONNX Runtime.** ocr-pro and RapidOCR run the same models and reach
   the same word F1: 0.930 and 0.928. Where their CER differs, the cause is reading order, not
   recognition.
2. **Reading order is the weakest part.**
   - **Over-splitting:** on the train ticket, the two-column rule in `ReadingOrder` splits a
     form into a left block and a right block. CER is 0.42, against 0.11 for RapidOCR's plain
     top-to-bottom order.
   - **Missed columns:** the low-resolution two-column newspaper is not recognized as two
     columns at all. Every PP-OCR variant reaches CER 0.72 there, while Tesseract's layout
     analysis reaches 0.16, with similar word F1.
   - **Effect on totals:** that one page is most of the pooled Polish CER.
3. **Recognition is clearly ahead of Tesseract.** The gap shows on photos, thermal receipts,
   stained paper and signs, with word F1 0.93 against 0.85. Tesseract is better at multi-column
   layout (both newspaper pages).
4. **The Polish corrections help a little and hurt in places.** Polish word F1 goes from 0.895 to
   0.916. CER barely moves, and English text hardly changes.
   - **What helps:** almost all of the gain comes from the word lists putting back missing
     diacritics: *połączenia*, *węglowy*, *związku*, *mogą*, *kąpieli*, *Kołobrzeg*,
     *pełnią*.
   - **What hurts:** the all-capitals rule writes ZWIĄZEK as **ZAWIĄZEK** and PŁATNOŚCI as
     **PŁATNOŚCIĄ**. The dictionary turns the abbreviation *Przewod.* into **Przewód.**, and the
     place name *Maluszyn* into **Małuszyn**. The period→comma runner-up changes a sentence end
     in English text.
   - **Takeaway:** the diacritic restoration generalizes, and the all-capitals and period→comma
     rules do not.

   These observations come from this set. A fix based on them spends these documents (see the
   rules above).
5. **Speed is similar.** On this power-limited laptop, a page took about 2 s with ocr-pro.
   RapidOCR on the same models was not faster (median 3.0 s; on two pages checked, about three
   quarters of its time was recognition, which batches and pads lines). Tesseract took 2.0 s per process, including its
   start-up. Repeated runs of one page varied by ±20 %. Treat this as "the same order of
   magnitude", not as a ranking, and measure on your own hardware.

## Reproduce

```bash
mvn -DskipTests package
bench/fetch.sh                                   # 20 images, about 34 MB, SHA-256 checked

./ocr.sh score bench/ref bench/data --out bench/out/plain
./ocr.sh score bench/ref bench/data --corrections polish --out bench/out/polish

pip install rapidocr==3.10.0 onnxruntime==1.31.0
python bench/run-rapidocr.py bench/ref bench/data bench/out/rapidocr models
./ocr.sh score bench/ref --hyp bench/out/rapidocr

bench/run-tesseract.sh bench/ref bench/data bench/out/tesseract   # needs tesseract with pol and eng
./ocr.sh score bench/ref --hyp bench/out/tesseract
```

`score` prints one row per document (characters, CER, word F1, ms) and totals per name prefix
(`pl`, `en`) and overall. Times from `score` exclude image decoding and include one warm-up page
before the first measurement. The RapidOCR script times its call after a warm-up. The Tesseract
script times the whole process.
