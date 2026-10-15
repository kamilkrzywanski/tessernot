package io.github.kamilkrzywanski.ocr;

import java.util.List;

/**
 * Full-page OCR result. {@code text} is lines joined in reading order.
 * {@code tables} are ruled grids found on the same page and do not change {@code text}.
 */
public record OcrResult(
        String text,
        List<TextLine> lines,
        List<TextTable> tables,
        long detectionNanos,
        long recognitionNanos,
        long totalNanos
) {
    public OcrResult {
        text = text == null ? "" : text;
        lines = lines == null ? List.of() : List.copyOf(lines);
        tables = tables == null ? List.of() : List.copyOf(tables);
    }

    public double detectionMillis() {
        return detectionNanos / 1_000_000.0;
    }

    public double recognitionMillis() {
        return recognitionNanos / 1_000_000.0;
    }

    public double totalMillis() {
        return totalNanos / 1_000_000.0;
    }
}
