package io.github.kamilkrzywanski.ocr;

/**
 * Which characters the recognition model may output.
 * <p>
 * {@link #ALL} keeps the whole dictionary of the model: about 18,700 characters, most of them
 * Chinese and Japanese.
 * <p>
 * {@link #LATIN} keeps Latin and Greek letters, digits, punctuation and common symbols, 2,440 of
 * the 18,710 classes, and drops the rest from the model's last layer when it is loaded. That layer
 * then costs an eighth of the time, and a Latin letter can no longer be read as a similar CJK
 * character. Use it for documents in Latin script.
 */
public enum Alphabet {
    /** The whole dictionary of the model. */
    ALL,
    /** Latin and Greek letters, digits, punctuation and common symbols. */
    LATIN;

    /**
     * Reads a value from its name, in any case.
     *
     * @param value {@code all} or {@code latin}
     * @return the value
     * @throws OcrException for any other text and for null
     */
    public static Alphabet parse(String value) {
        if (value != null) {
            for (Alphabet a : values()) {
                if (a.name().equalsIgnoreCase(value.trim())) {
                    return a;
                }
            }
        }
        throw new OcrException("alphabet must be all or latin");
    }
}
