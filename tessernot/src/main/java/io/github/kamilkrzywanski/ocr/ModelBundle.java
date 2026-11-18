package io.github.kamilkrzywanski.ocr;

import java.io.IOException;
import java.io.InputStream;

/**
 * Model files packaged in a JAR, found on the class path with {@link java.util.ServiceLoader}.
 * {@code tessernot-models} provides the default models and {@code tessernot-models-pl} the word lists
 * of {@link Corrections#POLISH}; {@link OcrConfig#defaults()} reads them through this interface.
 * A bundle names its implementation in {@code META-INF/services/io.github.kamilkrzywanski.ocr.ModelBundle}.
 */
public interface ModelBundle {
    /**
     * Opens a file of this bundle.
     *
     * @param name a file name, such as {@link ModelFiles#DET}
     * @return the file, which the caller closes, or null when this bundle does not hold it
     * @throws IOException when the file is there and cannot be opened
     */
    InputStream open(String name) throws IOException;
}
