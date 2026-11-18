package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;

/**
 * Short lines the recognizer is unsure of: mostly stamps, ornaments read as characters, and
 * stray marks. The engine leaves them out of the result after the reading order is found
 * ({@code OcrConfig.dropFragments}), and the reading order does not let them decide whether two
 * blocks stand beside each other.
 */
public final class Fragments {
    /** A fragment is at most this many characters... */
    public static final int CHARS = 10;
    /** ...and its mean character probability is below this. */
    public static final float SCORE = 0.8f;

    private Fragments() {
    }

    /** Whether {@code line} is a fragment. */
    public static boolean is(TextLine line) {
        return line.text().strip().length() <= CHARS && line.score() < SCORE;
    }
}
