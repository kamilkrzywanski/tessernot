package io.github.kamilkrzywanski.ocr.pdf;

import io.github.kamilkrzywanski.ocr.OcrEngine;
import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.OcrImages;
import io.github.kamilkrzywanski.ocr.OcrPool;
import io.github.kamilkrzywanski.ocr.OcrResult;
import io.github.kamilkrzywanski.ocr.internal.image.Images;
import io.github.kamilkrzywanski.ocr.internal.pdf.PdfPages;
import io.github.kamilkrzywanski.ocr.internal.pdf.SearchablePdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;

/**
 * PDF input and searchable PDF output. Every page is rendered with Apache PDFBox at {@code dpi}
 * and read as an image; the text layer is not used. The file is read into memory once and
 * released after the last page is rendered. An encrypted PDF is rejected before the first page.
 * <p>
 * {@link #searchable(OcrEngine, Path, Path, float)} writes the text back under the pages, so a
 * viewer can search, select and copy it.
 */
public final class OcrPdf {
    private OcrPdf() {
    }

    /**
     * Reads every page of a PDF.
     *
     * @param engine the engine that reads the pages
     * @param pdf the PDF file
     * @param dpi the resolution the pages are rendered at; 300 suits print of normal size
     * @return the results in page order
     * @throws OcrException when the engine is null or closed, the file is missing, not a PDF or
     *         encrypted, or {@code dpi} is not positive
     */
    public static List<OcrResult> recognize(OcrEngine engine, Path pdf, float dpi) {
        return recognize(engine, pdf, dpi, null);
    }

    /**
     * Same as {@link #recognize(OcrEngine, Path, float)}. {@code handler} is called in page order as
     * soon as that page is done, before later pages finish. A null handler only collects the list.
     *
     * @param engine the engine that reads the pages
     * @param pdf the PDF file
     * @param dpi the resolution the pages are rendered at
     * @param handler told about each page as soon as it is read, or null
     * @return the results in page order
     * @throws OcrException as {@link #recognize(OcrEngine, Path, float)} does
     */
    public static List<OcrResult> recognize(OcrEngine engine, Path pdf, float dpi, OcrEngine.PageHandler handler) {
        if (engine == null) {
            throw new OcrException("engine is null");
        }
        // A closed engine is reported before the file is touched, as for the image calls.
        if (engine.isClosed()) {
            throw new OcrException("engine is closed");
        }
        try (PdfPages.Source source = PdfPages.open(pdf, dpi)) {
            return engine.recognizePages(source.pages(), pages(source), handler);
        }
    }

    /**
     * Reads every page of a PDF on one engine borrowed from {@code pool}.
     *
     * @param pool the pool that lends the engine
     * @param pdf the PDF file
     * @param dpi the resolution the pages are rendered at
     * @return the results in page order
     * @throws OcrException when the pool is null or closed, the file is missing, not a PDF or
     *         encrypted, or {@code dpi} is not positive
     */
    public static List<OcrResult> recognize(OcrPool pool, Path pdf, float dpi) {
        return recognize(pool, pdf, dpi, null);
    }

    /**
     * Same as {@link #recognize(OcrEngine, Path, float, OcrEngine.PageHandler)} on one engine borrowed from {@code pool}.
     *
     * @param pool the pool that lends the engine
     * @param pdf the PDF file
     * @param dpi the resolution the pages are rendered at
     * @param handler told about each page as soon as it is read, or null
     * @return the results in page order
     * @throws OcrException as {@link #recognize(OcrPool, Path, float)} does
     */
    public static List<OcrResult> recognize(OcrPool pool, Path pdf, float dpi, OcrEngine.PageHandler handler) {
        if (pool == null) {
            throw new OcrException("pool is null");
        }
        try (PdfPages.Source source = PdfPages.open(pdf, dpi)) {
            return pool.recognizePages(source.pages(), pages(source), handler);
        }
    }

    /**
     * Reads a PDF or an image and writes a PDF that looks the same and can be searched: the text
     * of every line lies under the line, invisible. A PDF keeps its pages as they are, with the
     * text added; one that already has a text layer gets a second one. An image (PNG, JPEG, GIF,
     * BMP or TIFF) becomes a one-page PDF as large as the image is at {@code dpi}.
     * <p>
     * The text is selectable in the place of its line; inside a line, words lie about where they
     * are printed. {@code output} may be the input file: it is replaced when the new file is complete.
     *
     * @param engine the engine that reads the pages
     * @param input a PDF or an image file
     * @param output the PDF to write
     * @param dpi the resolution a PDF's pages are rendered at for reading, or the resolution of
     *            the image, which sets the page size
     * @return the results in page order; one for an image
     * @throws OcrException when the engine is null or closed, the input cannot be read or is
     *         encrypted, {@code dpi} is not positive, or the output cannot be written
     */
    public static List<OcrResult> searchable(OcrEngine engine, Path input, Path output, float dpi) {
        return searchable(engine, input, output, dpi, null);
    }

    /**
     * Same as {@link #searchable(OcrEngine, Path, Path, float)}. {@code handler} is called in
     * page order as soon as a page is read; the file is written after the last page.
     *
     * @param engine the engine that reads the pages
     * @param input a PDF or an image file
     * @param output the PDF to write
     * @param dpi the resolution the pages are rendered at, or the resolution of the image
     * @param handler told about each page as soon as it is read, or null
     * @return the results in page order; one for an image
     * @throws OcrException as {@link #searchable(OcrEngine, Path, Path, float)} does
     */
    public static List<OcrResult> searchable(OcrEngine engine, Path input, Path output, float dpi,
                                             OcrEngine.PageHandler handler) {
        if (engine == null) {
            throw new OcrException("engine is null");
        }
        if (output == null) {
            throw new OcrException("output is null");
        }
        if (input == null) {
            throw new OcrException("input is null");
        }
        if (!Float.isFinite(dpi) || dpi <= 0f) {
            throw new OcrException("dpi must be positive");
        }
        if (isImage(input)) {
            BufferedImage image = OcrImages.read(input);
            OcrResult result = engine.recognize(image);
            if (handler != null) {
                handler.onPage(0, 1, result);
            }
            SearchablePdf.fromImage(input, image, result, dpi, output);
            return List.of(result);
        }
        List<OcrResult> pages = recognize(engine, input, dpi, handler);
        SearchablePdf.fromPdf(input, pages, dpi, output);
        return pages;
    }

    private static boolean isImage(Path input) {
        if (!Files.isRegularFile(input)) {
            throw new OcrException("cannot read " + input + ": not a file");
        }
        byte[] head = new byte[14];
        try (InputStream in = Files.newInputStream(input)) {
            return Images.hasImageHeader(head, in.read(head));
        } catch (IOException e) {
            throw new OcrException("cannot read " + input + ": " + e.getMessage(), e);
        }
    }

    /**
     * Rejects a missing path, an image passed where a PDF belongs, and an encrypted PDF, without
     * rendering a page. Lets a caller fail before it loads the models.
     *
     * @param pdf the PDF file
     * @throws OcrException when the file cannot be read as an unencrypted PDF
     */
    public static void requireReadable(Path pdf) {
        PdfPages.requireUnlocked(pdf);
    }

    /** Renders page {@code index}; the document is closed right after the last page. */
    private static IntFunction<BufferedImage> pages(PdfPages.Source source) {
        int count = source.pages();
        return index -> {
            BufferedImage image = source.render(index);
            if (index + 1 == count) {
                source.close();
            }
            return image;
        };
    }
}
