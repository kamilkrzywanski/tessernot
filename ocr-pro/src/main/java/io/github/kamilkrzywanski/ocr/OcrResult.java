package io.github.kamilkrzywanski.ocr;

import java.util.List;

/**
 * Full-page OCR result. {@code text} is lines joined in reading order.
 * {@code tables} are ruled grids found on the same page and do not change {@code text}.
 *
 * @param text the page text: the lines of a row joined by a space, the rows by a line break
 * @param lines the lines in reading order, unmodifiable
 * @param tables the ruled grids of the page, top to bottom, unmodifiable
 * @param detectionNanos the time detection took
 * @param recognitionNanos the time reading the lines, the reading order and the tables took
 * @param totalNanos the time of the whole call, from the bitmap to this result
 */
public record OcrResult(
        String text,
        List<TextLine> lines,
        List<TextTable> tables,
        long detectionNanos,
        long recognitionNanos,
        long totalNanos
) {
    /** A null text becomes empty, null lists become empty, and the lists are copied. */
    public OcrResult {
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
