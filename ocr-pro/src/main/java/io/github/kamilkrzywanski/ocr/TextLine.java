package io.github.kamilkrzywanski.ocr;

import java.awt.Rectangle;

/** One detected text line after recognition. {@code quad} is TL, TR, BR, BL in pixels (8 values). */
public record TextLine(String text, float score, float detScore, float[] quad, Rectangle bounds) {
    public TextLine {
        text = text == null ? "" : text;
        if (quad == null) {
            quad = new float[8];
        } else if (quad.length != 8) {
            throw new OcrException("quad must have 8 values");
        }
        quad = quad.clone();
        bounds = bounds == null ? new Rectangle() : new Rectangle(bounds);
    }
}
