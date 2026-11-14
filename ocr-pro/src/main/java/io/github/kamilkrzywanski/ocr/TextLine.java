package io.github.kamilkrzywanski.ocr;

import java.awt.Rectangle;
import java.util.List;

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
 * @param words the words of the line with their boxes, left to right as read, unmodifiable.
 *        Empty when the places of the characters are not known: the line was not read, or its
 *        text was changed after the read, as by a second read of {@link Corrections#POLISH}
 */
public record TextLine(String text, float score, float detScore, float[] quad, Rectangle bounds, List<TextWord> words) {
    /**
     * A line without word boxes.
     *
     * @param text the text of the line
     * @param score the mean probability of the characters read
     * @param detScore the mean probability of text inside the detected box
     * @param quad the four corners, eight values, or null
     * @param bounds the pixel rectangle around the quad, or null
     */
    public TextLine(String text, float score, float detScore, float[] quad, Rectangle bounds) {
        this(text, score, detScore, quad, bounds, List.of());
    }

    /**
     * A null text becomes empty, a null quad eight zeros, a null rectangle an empty one and a
     * null word list empty; the quad, the rectangle and the list are copied.
     *
     * @throws OcrException when the quad does not have eight values
     */
    public TextLine {
        words = words == null ? List.of() : List.copyOf(words);
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
