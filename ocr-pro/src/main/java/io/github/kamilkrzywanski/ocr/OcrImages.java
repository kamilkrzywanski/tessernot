package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.image.Images;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** Reads an image file for {@link OcrEngine#recognize(BufferedImage)}. */
public final class OcrImages {
    private OcrImages() {
    }

    /**
     * Decodes PNG, JPEG, GIF, BMP or TIFF. A JPEG is turned upright with its EXIF Orientation tag;
     * other formats are returned as stored. A missing file, a directory, a PDF, and a TIFF or GIF
     * with more than one image fail with {@link OcrException}.
     *
     * @param path the image file
     * @return the decoded image
     * @throws OcrException when the file cannot be read as one image
     */
    public static BufferedImage read(Path path) {
        return Images.read(path);
    }
}
