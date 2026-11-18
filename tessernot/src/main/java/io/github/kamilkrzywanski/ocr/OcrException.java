package io.github.kamilkrzywanski.ocr;

/**
 * Every failure this library reports: a file that cannot be read, a model that does not fit, a
 * setting out of range, an image that cannot be decoded, a call on a closed engine. It is
 * unchecked; the message says what was wrong and names the file where there is one.
 */
public class OcrException extends RuntimeException {
    /**
     * A failure with no underlying exception.
     *
     * @param message what was wrong
     */
    public OcrException(String message) {
        super(message);
    }

    /**
     * A failure caused by another exception.
     *
     * @param message what was wrong
     * @param cause the exception underneath, such as an {@link java.io.IOException}
     */
    public OcrException(String message, Throwable cause) {
        super(message, cause);
    }
}
