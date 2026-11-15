# Polish print corrections

`Corrections.POLISH` (CLI: `--corrections polish`) adds rules on top of the plain PP-OCR pipeline.
They are **off by default**.

```java
OcrConfig config = OcrConfig.defaults().toBuilder()
        .corrections(Corrections.POLISH)
        .build();
```

The word lists and postal codes these rules use are in `ocr-pro-models-pl`, or next to the models
when they come from a directory (`OcrConfig.defaults(dir)`). Without them the rules still run,
without the dictionary steps.

## Where these rules come from

Every rule and threshold here was measured on three scans: a typed letter, a thermal supermarket
receipt and a Polish VAT invoice (`ocr-pro/src/test/resources/quality/`). On those scans the rules cut
the error count from 0 / 9 / 18 edits (plain pipeline) to 0 / 1 / 0. These scans are the data
the rules were tuned on, so those numbers say nothing about other documents.

For other documents, see [benchmark.md](benchmark.md).

**Now** (2026-10-23, with the word-list search of rule 3 in place of the old letter swaps), the
profile against the plain pipeline, CER pooled over the Polish pages and Polish word F1:

| Set | CER pl | Word F1 pl | Pages better / worse |
| --- | --- | --- | --- |
| `dev` (39) | 0.021 → 0.019 | 0.940 → 0.953 | 16 / 3 |
| `test` (27) | 0.054 → 0.052 | 0.915 → 0.934 | 16 / 1 |
| `test2` (20) | 0.031 → 0.028 | 0.912 → 0.932 | 9 / 2 |

With the engine of 2026-11-15, on the two sets nobody tuned on: `test2` 0.029 → 0.026 and
0.917 → 0.935, 7 Polish pages better and 2 worse; `test3` (97) 0.167 → 0.159 and 0.827 → 0.878,
35 better and 5 worse.

English pages gain too (the Aparri News 1900 by 30 edits, Britannica 1911 by 11), because the
search uses both lists. What still hurts, and why:

- **Historical spelling.** A mark the image gives no evidence for costs exactly 3 nats in the
  search, inside the 4-nat margin, so a listed modern form wins over an unlisted old one:
  `zdala` became `zdała` on the 1914 gazette and `Krola` became `Króla` on Volumina Legum
  (+18 edits, the one loss on `dev` above 10). The same floor is what restores `osiągnięty`
  from `osiagnięty` when the print lost the hook, so the search cannot tell the two apart. For
  a document of its period, pass its own word list with `--words`; the search then has the
  printed form.
- **Faint print under rule 2** (fixed 2026-10-23). The parking ticket on `dev` lost a line of
  faint brown digits: with ink defined as luma below 150, a speck was the heavier band and the
  digits were cut away. Ink is now the darker of the crop's two luma classes (Otsu): the
  ticket has 19 edits against 29 before and 18 without the profile, and the 1935 MSZ letter
  loses the 3 edits the profile gained on it. Nothing else on `dev` or `test` moved. The letter
  swaps of rule 1 were suspected first and were not it. Leaving them to the word-list search
  when the Polish lexicon is loaded was tried and dropped: five pages lost one or two edits
  each (3 on `dev`, 2 on `test`) and none gained.

The profile is still worse than the plain pipeline on seven pages of `dev` and `test`
together: Volumina Legum (+18, the historical spelling above), the arXiv paper and Dziennik
Ustaw 1994 (+2 each), and the 1896 Monthly Weather Review, the Kuria letter, Wieś Ilustrowana
1911 and the 1936 ZHP statute (+1 each).

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

The rules run in this order for each detected line; the code is `PolishRules.reread` (rule 1 is
`RunnerUps`, in the decoder), which asks the engine for each second read. A "second read" means
the line crop is changed and read by the recognizer again. Most second reads keep the new text only when it
differs from the first read in the one expected way. Any other difference keeps the first read.

1. **CTC runner-ups.** In greedy decoding, some second-best characters replace the winner when
   their probability is high enough:
   a space after a colon (≥ 0.35 when blank wins), `x` for `×` (≥ 0.35), `ł`/`Ł` for `l`/`L`
   (≥ 0.34), `ł`/`Ł` for `t`/`T` (≥ 0.15), `,` for `.` after a digit (≥ 0.34), `ć` for `ó` (≥ 0.15),
   `Ś` for `S` (≥ 0.015) and `ó` for `o` (≥ 0.05).
   A rule is decided once for the whole run of timesteps the winner holds, and applies when any
   of them meets the bar, so a letter spread over two timesteps stays one letter. The character
   keeps the winner's probability in the line score: a correction does not push a line under
   the recognition threshold. The table is `RunnerUps.polish`; the decoder itself has no rules.
2. **Main ink band.** After rotation and before the resize to height 48, a second ink band is
   dropped when at least 20 white rows separate it and it has clearly less ink. A neighbor line
   caught in the box then stops sharing the model height. Ink and paper are the two luma
   classes of the crop itself (Otsu's split), so faint print counts and a speck does not set
   the cut; a crop whose two classes are less than 40 of luma apart is left alone.
3. **Word-list search.** These are optional files next to the models: `lexicon-pl.txt.gz`
   (sjp.pl) and `lexicon-en.txt.gz` (SCOWL). With both present, every unlisted word is searched
   again on the CTC output with the two lists, as described in the README under "Word lists in
   any language". This is the same search `--words` runs; it has no Polish rules of its own.
   Both files must be sorted by bytes, lowercase, one word per `\n`; a file in another order or
   with `\r\n` line ends fails the load instead of silently finding nothing.

   Until 2026-10-17 this step was a fixed, ordered family of letter swaps measured on the three
   scans (one or two diacritics, `t`→`ł`, `i`→`j`, `ó`/`e`/`o`→`ć`, `l`→`i`/`j`, `o`→`c`, capitals
   with an inserted letter, glued one-letter prepositions split off). Measured with the polish
   profile (`dev` / `test` word F1): 0.955 / 0.934 with the swaps alone, 0.960 / 0.943 with the
   search and the swaps, 0.964 / 0.946 with the search and no swaps. The swaps were removed. On
   the three scans this costs Żniwna and Polędw on the receipt (the printer prints no mark on
   those lines, so the search adds none), and the spaces in "i magazyn" and "i Wspólnicy" and
   the ł in Spółka (read as the listed Spotka) on the invoice.
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
   remove a diacritic the read has. Each letter keeps the case it was read in: `LODZ` becomes
   `ŁÓDŹ`.
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

In ruled tables, a cell crop gets the word-list search (rule 3). The other rules apply to page
lines only.

## Turning rules into something general

Most of these rules are guesses from a handful of failures. Develop a rule on `dev`, then check
it once on `test`. A rule that helps there is a candidate for the plain pipeline; one that hurts
should go. Run `score` with and without `--corrections polish` (see
[benchmark.md](benchmark.md)) to compare.
