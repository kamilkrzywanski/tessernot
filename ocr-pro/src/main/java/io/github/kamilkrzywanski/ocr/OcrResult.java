package io.github.kamilkrzywanski.ocr;

import java.util.List;

/**
 * Full-page OCR result. {@code text} is lines joined in reading order.
 * {@code tables} are the tables found on the same page, ruled or not, and do not change {@code text}.
 *
 * @param text the page text: the lines of a row joined by a space, the rows by a line break
 * @param lines the lines in reading order, unmodifiable
 * @param tables the tables of the page, top to bottom, unmodifiable; see {@link TextTable#ruled()}
 * @param detectionNanos the time detection took
 * @param recognitionNanos the time reading the lines, the reading order and the tables took
 * @param totalNanos the time of the whole call, from the bitmap to this result
 * @param rotation the turn, in degrees clockwise, that sets the picture upright: 0, or 90, 180
 *        or 270 when the page was found lying on its side or upside down and was read turned.
 *        The lines and tables are in the pixels of the picture as it was given either way.
 */
public record OcrResult(
        String text,
        List<TextLine> lines,
        List<TextTable> tables,
        long detectionNanos,
        long recognitionNanos,
        long totalNanos,
        int rotation
) {
    /**
     * A result of an upright page.
     *
     * @param text the page text
     * @param lines the lines in reading order
     * @param tables the tables of the page
     * @param detectionNanos the time detection took
     * @param recognitionNanos the time reading the lines took
     * @param totalNanos the time of the whole call
     */
    public OcrResult(String text, List<TextLine> lines, List<TextTable> tables,
                     long detectionNanos, long recognitionNanos, long totalNanos) {
        this(text, lines, tables, detectionNanos, recognitionNanos, totalNanos, 0);
    }

    /**
     * A null text becomes empty, null lists become empty, and the lists are copied.
     *
     * @throws OcrException when the rotation is not 0, 90, 180 or 270
     */
    public OcrResult {
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new OcrException("rotation must be 0, 90, 180 or 270");
        }
        text = text == null ? "" : text;
        lines = lines == null ? List.of() : List.copyOf(lines);
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    /** {@return {@link #detectionNanos()} in milliseconds} */
    public double detectionMillis() {
        return detectionNanos / 1_000_000.0;
    }

    /** {@return {@link #recognitionNanos()} in milliseconds} */
    public double recognitionMillis() {
        return recognitionNanos / 1_000_000.0;
    }

    /** {@return {@link #totalNanos()} in milliseconds} */
    public double totalMillis() {
        return totalNanos / 1_000_000.0;
    }
}
