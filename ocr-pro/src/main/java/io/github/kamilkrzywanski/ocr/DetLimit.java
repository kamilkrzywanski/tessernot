package io.github.kamilkrzywanski.ocr;

/**
 * Which side of the page {@link OcrConfig#detLimitSideLen()} limits before detection.
 * <p>
 * {@link #MAX}, the default, scales a page down until its longer side is at most the limit; a
 * smaller page keeps its size. {@link #MIN} scales a page up until its shorter side is at least
 * the limit, for small crops whose print is too fine for the detector; a long narrow image grows
 * with it.
 */
public enum DetLimit {
    /** The longer side is at most the limit. */
    MAX,
    /** The shorter side is at least the limit. */
    MIN;

    /**
     * Reads a value from its name, in any case.
     *
     * @param value {@code max} or {@code min}
     * @return the value
     * @throws OcrException for any other text and for null
     */
    public static DetLimit parse(String value) {
        if (value != null) {
            for (DetLimit l : values()) {
                if (l.name().equalsIgnoreCase(value.trim())) {
                    return l;
                }
            }
        }
        throw new OcrException("detLimitType must be max or min");
    }
}
