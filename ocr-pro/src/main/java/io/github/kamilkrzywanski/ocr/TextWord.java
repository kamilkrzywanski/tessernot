package io.github.kamilkrzywanski.ocr;

/**
 * One word of a line with its place on the page: a run of characters between spaces.
 * <p>
 * The quad is cut out of the line's box where the recognizer read the word. Its corners are in
 * the order of the text, top left of the word first, then top right, bottom right and bottom
 * left, whichever way the line lies on the page: for a line that runs down the page the first
 * corner is at the top right of the box. The ends of a word are usually right to a tenth of the
 * line height and to about a third at worst, measured on rendered lines: the recognizer sees a
 * line in steps a sixth of its height wide.
 *
 * @param text the word
 * @param quad x and y of its four corners in pixels of the page, eight values
 */
public record TextWord(String text, float[] quad) {
    /**
     * A null text becomes empty; the quad is copied.
     *
     * @throws OcrException when the quad is null or does not have eight values
     */
    public TextWord {
        text = text == null ? "" : text;
        if (quad == null || quad.length != 8) {
            throw new OcrException("quad must have 8 values");
        }
        quad = quad.clone();
    }

    /** {@return a copy of the eight corner coordinates, in the order of the text} */
    @Override
    public float[] quad() {
        return quad.clone();
    }
}
