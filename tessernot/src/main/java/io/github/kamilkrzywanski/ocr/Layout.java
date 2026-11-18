package io.github.kamilkrzywanski.ocr;

/**
 * How the lines of a page are put in reading order.
 * <p>
 * {@link #AUTO} finds text columns, blocks set side by side (the seller and buyer of an invoice)
 * and titles across columns, and cuts a line the detector merged across a narrow gutter.
 * It suits a mixed stream of documents.
 * <p>
 * {@link #ROWS} reads the page row by row, top to bottom, with no layout analysis. Use it when
 * every page has one column, such as receipts, tickets or labels, so a column can never be found
 * where there is none. A skewed photo is still turned level first.
 */
public enum Layout {
    /** Columns, blocks side by side and titles across columns are found. */
    AUTO,
    /** Row by row, top to bottom. */
    ROWS;

    /**
     * Reads a value from its name, in any case.
     *
     * @param value {@code auto} or {@code rows}
     * @return the value
     * @throws OcrException for any other text and for null
     */
    public static Layout parse(String value) {
        if (value != null) {
            for (Layout l : values()) {
                if (l.name().equalsIgnoreCase(value.trim())) {
                    return l;
                }
            }
        }
        throw new OcrException("layout must be auto or rows");
    }
}
