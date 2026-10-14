# Collecting benchmark documents

How to add documents to `bench/`, written for a model (or person) that collects them. It is the
method that worked for `dev`, `test` and `test2`, with the mistakes of those rounds fixed.

## Before you start

- **Decide the set first.** New documents go to `dev` (you may debug and tune on them) or to a new
  held-out set (`test3`, ...), scored once at the end of a round. Never mix: an agent that
  collects for a held-out set must not see engine output for those pages, and nobody tunes on
  them afterwards. See the rules in [docs/benchmark.md](../docs/benchmark.md).
- **Do not reuse a publication** already in `bench/manifest.tsv`, not even another page of it. A
  newspaper title, an encyclopedia (all volumes), an arXiv paper or an Official Journal issue
  counts as one publication.
- **Split the work between agents** by language and kind, about 8–10 documents each, and run them
  in parallel. A held-out set needs a share of business documents (invoices, receipts, letters)
  as well as multi-column pages, so it catches regressions on both.
- **Keep the collectors cheap:** tell them to stop at their share and to write each manifest row
  as soon as a document is finished. In one round two agents were stopped before they wrote any
  manifest, and the rows had to be rebuilt by hand.

## Rules every collector gets

- **No OCR tool may be run** on the documents: not this engine, not Tesseract, nothing. Do not
  read the project's source code or `bench/out/`. The reference is a transcription made by
  looking at the image.
- Drafts are allowed only from human-proofread text: Wikisource pages with status *validated* /
  *uwierzytelniona* or *proofread* / *skorygowana*, and the text layer of a born-digital PDF
  (`pdftotext`, with and without `-layout`). Machine OCR (Internet Archive, Chronicling America)
  is never a draft. Every line of a draft is checked against the image.
- **Licenses:** public domain, CC0, CC BY, CC BY-SA (Wikisource text is CC BY-SA), EU reuse
  (CC BY 4.0, Commission Decision 2011/833/EU), arXiv only under CC BY or CC0 (check the license
  link on the abstract page). Record license and author for every document.
- No personal data of private individuals (home addresses, ID numbers, private bank accounts).
  Company, institution, historical and public documents are fine.
- Only touch `bench/data/`, `bench/ref/<set>/`, the agent's own manifest file
  `bench/manifest-new-<agent>.tsv` and the shared claims file `bench/claims.tsv`.

## Where documents come from

| Source | Good for | How |
| --- | --- | --- |
| pl.wikisource.org, en.wikisource.org (`Strona:` / `Page:` pages) | multi-column books, dictionaries, gazettes, newspapers, journals | The scan is a DjVu or PDF on Commons; the page text comes from the MediaWiki API: `action=query&prop=revisions&rvprop=content&titles=Strona:...` |
| Wikimedia Commons | invoices, receipts, tickets, letters, notices, newspaper pages | API: `list=search&srnamespace=6`, `list=categorymembers&cmtype=file` (Category:Invoices of Poland, Category:Receipts, ...), `prop=imageinfo&iiprop=url\|sha1\|extmetadata` for URL, license and author |
| EUR-Lex / Publications Office | the Official Journal of the EU, born-digital two-column pages, Polish and English | eur-lex.europa.eu answers bots with an empty HTTP 202; download from the Cellar (`publications.europa.eu/resource/...` `DOC_1` links) |
| arXiv | two-column papers with a full-width title and abstract | CC BY / CC0 only |
| Polona, FBC, regional digital libraries | Polish historical print | only if the collector transcribes the page itself |

Always send a User-Agent with curl: `-A 'ocr-bench/0.1 (contact address)'`.

## What to pick

- Each page must be fully legible at full resolution. If any printed region cannot be
  transcribed with confidence, pick another page.
- Multi-column pages: vary the number of columns (2, 3, 4), gutters with and without a vertical
  rule, narrow gutters, full-width titles or tables between column bands, hanging indents,
  footnotes, at least one low-quality scan or photo, and born-digital PDFs.
- Skip ad collages, puzzles and pages whose articles jump between columns: their reading order
  is not well defined. Avoid Fraktur, and pages with much math.
- Business documents: modern and old invoices, receipts, bills, tickets, letters, forms; scans and
  phone photos.

## Claims (no overlap between agents)

Before downloading anything from a publication, read `bench/claims.tsv`. If the publication is
not there, append a line at once and only then download:

```sh
printf '<agent>\t<publication title, year/volume>\t<source url>\n' >> bench/claims.tsv
```

Delete `claims.tsv` when the round is merged.

## Files

- Name: `pl-<short-kebab-case>` or `en-<short-kebab-case>`, e.g. `pl-gazeta-lwowska-1914-dodatek`.
  Check that the name is free.
- **Image source:** download the original (not a thumbnail) to `bench/data/<name>.<ext>`
  (jpg/png), long side 1500–5000 px, at most 12 MB.
- **PDF source:** download to `bench/data/<name>.pdf`, render one page:
  `pdftoppm -r 200 -f N -l N -png -singlefile bench/data/<name>.pdf bench/data/<name>`.
  Another dpi is written as `N@dpi` in the manifest.
- **DjVu source:** download to `bench/data/<name>.djvu`, render one page at full resolution and
  convert it losslessly to PNG:
  `ddjvu -format=pnm -page=N bench/data/<name>.djvu /tmp/<name>.pnm` then
  `python3 -c 'import sys; from PIL import Image; Image.open(sys.argv[1]).save(sys.argv[2])' /tmp/<name>.pnm bench/data/<name>.png`.
  N is the index in the file, not the printed page number. If `ddjvu` is not installed, unpack
  the `djvulibre-bin` package into a scratch directory and call it with `LD_LIBRARY_PATH`.
- **Originals at most 45 MB** (GitHub warns above 50 MB; everything is tracked in git).
- **Manifest:** one row per document in the agent's own file, written when the document is done.
  Tab-separated, exactly 12 columns, `-` for an empty field:

  `name  set  file  url  sha256  page  license  author  source  lang  kind  notes`

  - `file` is the image the engine reads (the rendered PNG for PDF/DjVu);
  - `url` is the original download URL and `sha256` its digest (of the PDF/DjVu, not the PNG);
  - `page` is `N` or `N@dpi` for PDF/DjVu, `-` for an image;
  - `kind` is one of invoice, receipt, letter, form, notice, book, newspaper, journal, gazette,
    dictionary, paper, other;
  - `notes`: capture (scan, photo, born-digital), number of columns, separators, what is left
    out of the reference, and anything not fully verified.

## Transcription rules for `bench/ref/<set>/<name>.txt`

UTF-8, trailing newline.

- Exact characters as printed: case, diacritics, digits, punctuation, old orthography, and the
  document's own typos. A letter that is damaged in print or scan but still identifiable is
  written as that letter (a broken `c` stays `c`, not `:`); a wrong letter is kept.
- One printed line per text line. Keep end-of-line hyphenation as printed (`word-` / next line).
  Wikisource drafts join lines and remove hyphens; rebuild both.
- **Reading order:**
  - running head first;
  - text on one visual row of a form or receipt is joined left to right; tables row by row,
    cells separated by one space;
  - columns are read one after another, each top to bottom;
  - a *text* element across the columns (title, heading, abstract, full-width text or table)
    closes one band of columns and starts the next; a headline over only some columns starts the
    band of those columns;
  - a *figure* across the columns does not split bands when the column text runs on below it;
    when the rule and the natural reading order disagree, the sentence must continue;
  - footnotes and the page number where they are printed;
  - figure captions where they sit in their column.
- Leave out handwriting, signatures, stamps, logos, illustrations and any text inside them,
  ornaments, library stamps, bleed-through, redacted areas. Say so in the manifest notes.
- Whitespace runs and typographic quotes and dashes are normalized by the scorer; all other
  characters count.
- **Work method:** make zoomed crops with Pillow (column by column, strip by strip, 2× for small
  print), transcribe, then do a second full pass comparing every line with the image (diacritics
  ą ć ę ł ń ó ś ź ż, digits, l/1/I, O/0, punctuation, hyphens). Report the spots worth a second
  look as `file:line`.

## Merging a round

1. Check every agent manifest: 12 columns, `set` correct, the reference and image exist, the name
   is unique and not in `bench/manifest.tsv`.
2. Verify the files: for Commons sources compare the SHA-1 from the API with the local file;
   re-render each PDF/DjVu page with the recorded `page` and compare it pixel by pixel with the
   stored PNG (a page or dpi mismatch shows at once).
3. If an agent was stopped before writing its manifest, rebuild its rows from `claims.tsv`, the
   Commons API (URL, license, author, SHA-1) and the page search above, and mark them in the
   notes. Mark references whose second pass was not finished.
4. Append the rows to `bench/manifest.tsv`, delete the agent manifests and `claims.tsv`, make
   sure no stray files are left in `bench/data/`, and update the set sizes in
   [docs/benchmark.md](../docs/benchmark.md) and [README.md](README.md).

## A prompt to start from

Fill in the angle brackets.

```text
You are collecting OCR benchmark documents for an OCR engine (Java project at <repo path>).
Your documents go to the <dev | test3> set. <For a held-out set: it is scored only once, at the
end of the round, so these must be documents nobody tunes on.> Other agents collect in parallel
(<list their shares>), so publications must not overlap (see Claims).

YOUR SHARE: <N> <Polish | English> documents: <composition, e.g. 7 multi-column pages (2 classic
two-column book pages, 1–2 pages with three or more columns, 1–2 with a full-width element
between column bands, 1–2 born-digital two-column PDFs, at least one low-quality scan) and
3 business documents (2 invoices or receipts, 1 letter or form)>.

Follow bench/COLLECTING.md in the repository exactly: sources, hard rules (no OCR tool, no
source code, licenses, no private data), claims, file naming and rendering, the 12-column
manifest (your file: bench/manifest-new-<agent>.tsv, one row as soon as each document is done),
and the transcription rules, including the reading-order rule. Work in the scratch directory
<path> for crops. Do not touch anything outside bench/data, bench/ref/<set>, your manifest and
bench/claims.tsv.

Final report: a short table (name, kind, columns, capture, characters, license) and the spots
worth re-checking (file:line). Keep it short.
```
