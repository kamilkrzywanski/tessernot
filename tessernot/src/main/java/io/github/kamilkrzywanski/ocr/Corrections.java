package io.github.kamilkrzywanski.ocr;

/**
 * Text repairs applied after the network has read a line.
 * <p>
 * {@link #NONE} is the PP-OCR pipeline as published: detection, angle classifier,
 * recognition and greedy CTC, followed by reading order and the ruled-table pass.
 * <p>
 * {@link #POLISH} adds rules for Polish print: runner-up swaps in CTC (ł, ó, Ś, ...),
 * the optional {@code lexicon-pl.txt.gz} / {@code lexicon-en.txt.gz} and
 * {@code postal-pl.txt.gz} lists, and second reads of a crop for invoice details
 * (row numbers glued to a name, a postal code, the dot on Ż, cell rules read as {@code |},
 * an amount glued to a VAT rate, known VAT field labels). The thresholds were measured
 * on a few scans; see {@code docs/corrections.md} and the held-out numbers in
 * {@code docs/benchmark.md} before turning it on for other documents.
 */
public enum Corrections {
    /** The pipeline with no repairs. */
    NONE,
    /** The rules and word lists for Polish print. */
    POLISH;

    /**
     * Reads a value from its name, in any case.
     *
     * @param value {@code none} or {@code polish}
     * @return the value
     * @throws OcrException for any other text and for null
     */
    public static Corrections parse(String value) {
        if (value != null) {
            for (Corrections c : values()) {
                if (c.name().equalsIgnoreCase(value.trim())) {
                    return c;
                }
            }
        }
        throw new OcrException("corrections must be none or polish");
    }
}
