# Polish print corrections

`Corrections.POLISH` (CLI: `--corrections polish`) adds rules on top of the plain PP-OCR pipeline.
They are **off by default**.

```java
OcrConfig config = OcrConfig.defaults(Path.of("models")).toBuilder()
        .corrections(Corrections.POLISH)
        .build();
```

## Where these rules come from

Every rule and threshold here was measured on three scans: a typed letter, a thermal supermarket
receipt and a Polish VAT invoice (`src/test/resources/quality/`). On those scans the rules cut
the error count from 0 / 9 / 18 edits (plain pipeline) to 0 / 1 / 0. These scans are the data
the rules were tuned on, so those numbers say nothing about other documents.

For other documents, see [benchmark.md](benchmark.md).

**On `dev`** (20 documents) the first version of these rules was checked:

- **Overall:** Polish word F1 rose from 0.895 to 0.916.
- **What helped:** nearly all of the gain came from the word lists putting back missing
  diacritics.
- **What hurt:**
  - the all-capitals rule (ZWIĄZEK became **ZAWIĄZEK**),
  - the dictionary on an abbreviation (*Przewod.* became **Przewód.**),
  - the period→comma swap at the end of an English sentence.

  Those three were fixed (rules 1 and 3 below).

**On `test`** (27 documents, collected afterwards):

- **Overall:** the rules give +0.8 points of Polish word F1 (0.893 to 0.901), with CER unchanged.
- **Cost:** 0.4 points on one English newspaper.
- **Still open:** a real place name that happens to differ from a dictionary word by one mark
  can still be changed (*Maluszyn* became *Małuszyn* on `dev`).

Measure on your own documents before you turn this on.

## Rules

The rules run in this order for each detected line. A "second read" means the line crop is
changed and read by the recognizer again. Most second reads keep the new text only when it
differs from the first read in the one expected way. Any other difference keeps the first read.

1. **CTC runner-ups.** In greedy decoding, some second-best characters replace the winner when
   their probability is high enough:
   a space after a colon (≥ 0.35 when blank wins), `x` for `×` (≥ 0.35), `ł`/`Ł` for `l`/`L`
   (≥ 0.34), `ł`/`Ł` for `t`/`T` (≥ 0.15), `,` for `.` after a digit (≥ 0.34), `ć` for `ó` (≥ 0.15),
   `Ś` for `S` (≥ 0.015) and `ó` for `o` (≥ 0.05).
2. **Main ink band.** After rotation and before the resize to height 48, a second ink band is
   dropped when at least 20 white rows separate it and it has clearly less ink. A neighbor line
   caught in the box then stops sharing the model height.
3. **Word lists.** These are optional files next to the models: `lexicon-pl.txt.gz` (sjp.pl) and
   `lexicon-en.txt.gz` (SCOWL). A token of 4–24 letters that is in neither list gets the only
   dictionary word reachable by one ordered family of OCR confusions. The order is: one Polish
   diacritic, then two, then `t`→`ł`, then `i`→`j`, then `ó`/`e`/`o`→`ć` (optionally with
   `s`→`ś`), then up to two `l`→`i`/`j`, then one `o`→`c`. When several words match, the token
   stays.
   - **Capital letters.** A token that is a word without its diacritics gets them back only when
     the same line already prints a Polish diacritic. A fiscal printer that drops them keeps
     `SPRZEDAZ`, and `PLATNOŚCI` becomes `PŁATNOŚCI`. Otherwise it may get one inserted letter
     plus one diacritic, when exactly one word fits (`ORYGNAL` → `ORYGINAŁ`).
   - **Abbreviations.** A token followed by a period that starts a longer dictionary word stays
     as it is (`Przewod.`).
   - A digit glued to a known word gets a space. A one-letter preposition glued to the next
     word is split off.
4. **Row number.** A narrow digit, a gap and a thin rule at the left edge, as in an invoice
   `Lp` column, are cut off. The line is then read again. The cut is kept when the second read
   is the first read without that leading number.
5. **Postal code at threshold 220.** A line that starts with `NN-NNN` and still has a digit in
   the city is read again after a global black/white cut at luma 220. The new read is kept when
   the code is the same and the city has at least three letters and no digit.
6. **Dot on Ż.** When a capital `Z` has a light gap in its top bar, a 5×5 dot is painted above
   it and the line is read again. The new read is kept when the only change is `ż`/`ź` on that Z.
7. **City spelling.** This uses the optional `postal-pl.txt.gz` file (GeoNames, CC BY 4.0).
   A line that is only a postal code and a city gets the official spelling. The code must have a
   single place name, the letters must already match without diacritics, and the list may not
   remove a diacritic the read has.
8. **Margin rereads.** These run only without the word lists. A line with `t` or `l` is read
   three more times: with 4 white rows above and below, with the ink shifted 4 px down, and
   with 8 white rows below. A read is kept when the only changes are `ł` for `t`/`l` or at most
   two spaces between letters. The read with the most such repairs wins.
9. **VAT field labels.** A line without digits that is one edit from exactly one known label of
   a Polish VAT invoice (`Miejsce wystawienia`, `Słownie`, ...) takes that label.
10. **Bracket space.** `( a)` becomes `(a)`.
11. **Lost first letter.** A line that is only `.x.` is read again from a crop 16 px wider to the
    left and below. The new read is kept when it adds one letter before the first dot (`j.m.`).
12. **Cell rules read as fences.** A line that starts or ends with `[`, `]` or `|` is read again
    after the tall dark columns in its outer 16 px are whitened. The new read is kept when the
    only change is that one character lost on a side.
13. **Amount and VAT rate.** A box that reads like `8.13 23` is cut at the vertical rule
    between the two parts. The cut is kept when each half reads back as its token.

In ruled tables, a cell crop gets the word lists (rule 3). The other rules apply to page lines
only.

## Turning rules into something general

Most of these rules are guesses from a handful of failures. Develop a rule on `dev`, then check
it once on `test`. A rule that helps there is a candidate for the plain pipeline; one that hurts
should go. Run `score` with and without `--corrections polish` (see
[benchmark.md](benchmark.md)) to compare.
