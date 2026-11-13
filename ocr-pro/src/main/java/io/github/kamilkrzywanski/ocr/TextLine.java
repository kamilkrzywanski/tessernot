package io.github.kamilkrzywanski.ocr;

import java.awt.Rectangle;

/**
 * One detected text line after recognition. {@code quad} is TL, TR, BR, BL in pixels (8 values).
 * A line does not change: {@link #quad()} and {@link #bounds()} hand out copies. {@link #left()},
 * {@link #top()}, {@link #right()} and {@link #bottom()} read the extent of the quad without one.
 */
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

    /** A copy of the eight corner coordinates: x and y of the top left, top right, bottom right and bottom left corner. */
    @Override
    public float[] quad() {
        return quad.clone();
    }

    /** A copy of the pixel rectangle around the quad. */
    @Override
    public Rectangle bounds() {
        return new Rectangle(bounds);
    }

    /** The smallest x of the quad. */
    public float left() {
        return Math.min(Math.min(quad[0], quad[2]), Math.min(quad[4], quad[6]));
    }

    /** The largest x of the quad. */
    public float right() {
        return Math.max(Math.max(quad[0], quad[2]), Math.max(quad[4], quad[6]));
    }

    /** The smallest y of the quad. */
    public float top() {
        return Math.min(Math.min(quad[1], quad[3]), Math.min(quad[5], quad[7]));
    }

    /** The largest y of the quad. */
    public float bottom() {
        return Math.max(Math.max(quad[1], quad[3]), Math.max(quad[5], quad[7]));
    }
}
