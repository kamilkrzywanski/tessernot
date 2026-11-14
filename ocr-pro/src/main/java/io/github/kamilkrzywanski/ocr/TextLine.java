package io.github.kamilkrzywanski.ocr;

import java.awt.Rectangle;

/**
 * One detected text line after recognition. {@code quad} is TL, TR, BR, BL in pixels (8 values).
 * A line does not change: {@link #quad()} and {@link #bounds()} hand out copies. {@link #left()},
 * {@link #top()}, {@link #right()} and {@link #bottom()} read the extent of the quad without one.
 *
 * @param text the text of the line
 * @param score the mean probability of the characters read, 0 to 1
 * @param detScore the mean probability of text inside the detected box, 0 to 1
 * @param quad x and y of the top left, top right, bottom right and bottom left corner, in pixels of the page
 * @param bounds the pixel rectangle around the quad
 */
public record TextLine(String text, float score, float detScore, float[] quad, Rectangle bounds) {
    /**
     * A null text becomes empty, a null quad eight zeros and a null rectangle an empty one; the
     * quad and the rectangle are copied.
     *
     * @throws OcrException when the quad does not have eight values
     */
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

    /** {@return a copy of the eight corner coordinates: x and y of the top left, top right, bottom right and bottom left corner} */
    @Override
    public float[] quad() {
        return quad.clone();
    }

    /** {@return a copy of the pixel rectangle around the quad} */
    @Override
    public Rectangle bounds() {
        return new Rectangle(bounds);
    }

    /** {@return the smallest x of the quad} */
    public float left() {
        return Math.min(Math.min(quad[0], quad[2]), Math.min(quad[4], quad[6]));
    }

    /** {@return the largest x of the quad} */
    public float right() {
        return Math.max(Math.max(quad[0], quad[2]), Math.max(quad[4], quad[6]));
    }

    /** {@return the smallest y of the quad} */
    public float top() {
        return Math.min(Math.min(quad[1], quad[3]), Math.min(quad[5], quad[7]));
    }

    /** {@return the largest y of the quad} */
    public float bottom() {
        return Math.max(Math.max(quad[1], quad[3]), Math.max(quad[5], quad[7]));
    }
}
