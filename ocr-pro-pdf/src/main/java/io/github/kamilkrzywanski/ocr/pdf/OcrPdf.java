package io.github.kamilkrzywanski.ocr.pdf;

import io.github.kamilkrzywanski.ocr.OcrEngine;
import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.OcrPool;
import io.github.kamilkrzywanski.ocr.OcrResult;
import io.github.kamilkrzywanski.ocr.internal.pdf.PdfPages;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;

/**
 * PDF input. Every page is rendered with Apache PDFBox at {@code dpi} and read as an image; the
 * text layer is not used. The file is read into memory once and released after the last page is
 * rendered. An encrypted PDF is rejected before the first page.
 */
public final class OcrPdf {
    private OcrPdf() {
    }

    public static List<OcrResult> recognize(OcrEngine engine, Path pdf, float dpi) {
        return recognize(engine, pdf, dpi, null);
    }

    /**
     * Same as {@link #recognize(OcrEngine, Path, float)}. {@code handler} is called in page order as
     * soon as that page is done, before later pages finish. A null handler only collects the list.
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

    public static List<OcrResult> recognize(OcrPool pool, Path pdf, float dpi) {
        return recognize(pool, pdf, dpi, null);
    }

    /** Same as {@link #recognize(OcrEngine, Path, float, OcrEngine.PageHandler)} on one engine borrowed from {@code pool}. */
    public static List<OcrResult> recognize(OcrPool pool, Path pdf, float dpi, OcrEngine.PageHandler handler) {
        if (pool == null) {
            throw new OcrException("pool is null");
        }
        try (PdfPages.Source source = PdfPages.open(pdf, dpi)) {
            return pool.recognizePages(source.pages(), pages(source), handler);
        }
    }

    /**
     * Rejects a missing path, an image passed where a PDF belongs, and an encrypted PDF, without
     * rendering a page. Lets a caller fail before it loads the models.
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
