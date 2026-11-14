package io.github.kamilkrzywanski.ocr.internal.pdf;

import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.OcrResult;
import io.github.kamilkrzywanski.ocr.internal.image.Images;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Writes a PDF that looks like its source and carries the recognized text under the picture,
 * see {@link TextLayer}. A PDF keeps every object it has; an image becomes a one-page PDF.
 */
public final class SearchablePdf {
    private SearchablePdf() {
    }

    /**
     * A copy of {@code pdf} with the text of {@code pages} added, one result per page, rendered
     * at {@code dpi} when they were read. The copy is not encrypted and carries no valid
     * signature: adding to a page changes the bytes a signature covers.
     */
    public static void fromPdf(Path pdf, List<OcrResult> pages, float dpi, Path output) {
        requireOutput(output);
        try (PDDocument document = Loader.loadPDF(Files.readAllBytes(pdf))) {
            if (document.getNumberOfPages() != pages.size()) {
                throw new OcrException(pdf + " has " + document.getNumberOfPages() + " pages, not " + pages.size());
            }
            document.setAllSecurityToBeRemoved(true);
            TextLayer layer = new TextLayer(document);
            for (int i = 0; i < pages.size(); i++) {
                layer.add(document.getPage(i), pages.get(i), dpi);
            }
            layer.finish();
            save(document, output);
        } catch (InvalidPasswordException e) {
            throw new OcrException(pdf + " is encrypted", e);
        } catch (IOException e) {
            throw new OcrException("cannot write " + output + ": " + e.getMessage(), e);
        }
    }

    /**
     * A one-page PDF of {@code image}, as large as the image is at {@code dpi}, with the text of
     * {@code result}. {@code source} is the file the image was read from: a JPEG that needs no
     * turn is stored as it is, anything else is stored without loss.
     */
    public static void fromImage(Path source, BufferedImage image, OcrResult result, float dpi, Path output) {
        requireOutput(output);
        if (!Float.isFinite(dpi) || dpi <= 0f) {
            throw new OcrException("dpi must be positive");
        }
        try (PDDocument document = new PDDocument()) {
            float width = image.getWidth() * 72f / dpi;
            float height = image.getHeight() * 72f / dpi;
            PDPage page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            PDImageXObject picture = picture(document, source, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(picture, 0, 0, width, height);
            }
            TextLayer layer = new TextLayer(document);
            layer.add(page, result, dpi);
            layer.finish();
            save(document, output);
        } catch (IOException e) {
            throw new OcrException("cannot write " + output + ": " + e.getMessage(), e);
        }
    }

    private static PDImageXObject picture(PDDocument document, Path source, BufferedImage image) throws IOException {
        if (source != null) {
            byte[] bytes = Files.readAllBytes(source);
            boolean jpeg = bytes.length > 2 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8;
            if (jpeg && Images.storedUpright(bytes)) {
                PDImageXObject stored = JPEGFactory.createFromStream(document, new ByteArrayInputStream(bytes));
                if (stored.getWidth() == image.getWidth() && stored.getHeight() == image.getHeight()) {
                    return stored;
                }
            }
        }
        return LosslessFactory.createFromImage(document, image);
    }

    private static void requireOutput(Path output) {
        if (output == null) {
            throw new OcrException("output is null");
        }
        if (Files.isDirectory(output)) {
            throw new OcrException("cannot write " + output + ": it is a directory");
        }
    }

    /** Written beside the target first, so the target may be the source and a failure leaves no half file. */
    private static void save(PDDocument document, Path output) throws IOException {
        Path absolute = output.toAbsolutePath();
        Path directory = absolute.getParent();
        Path part = Files.createTempFile(directory, absolute.getFileName().toString(), ".part");
        try {
            document.save(part.toFile());
            try {
                Files.move(part, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }
}
