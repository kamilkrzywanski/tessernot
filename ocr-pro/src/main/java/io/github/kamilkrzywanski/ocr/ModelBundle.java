package io.github.kamilkrzywanski.ocr;

import java.io.IOException;
import java.io.InputStream;

/**
 * Model files packaged in a JAR, found on the class path with {@link java.util.ServiceLoader}.
 * {@code ocr-pro-models} provides the default models and {@code ocr-pro-models-pl} the word lists
 * of {@link Corrections#POLISH}; {@link OcrConfig#defaults()} reads them through this interface.
 * A bundle names its implementation in {@code META-INF/services/io.github.kamilkrzywanski.ocr.ModelBundle}.
 */
public interface ModelBundle {
    /** Opens the named file, such as {@link ModelFiles#DET}, or returns null when this bundle does not hold it. */
    InputStream open(String name) throws IOException;
}
