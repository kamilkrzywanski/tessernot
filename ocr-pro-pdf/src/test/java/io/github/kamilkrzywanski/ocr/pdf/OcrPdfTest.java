package io.github.kamilkrzywanski.ocr.pdf;

import io.github.kamilkrzywanski.ocr.ModelFiles;
import io.github.kamilkrzywanski.ocr.OcrConfig;
import io.github.kamilkrzywanski.ocr.OcrEngine;
import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.OcrResult;
import io.github.kamilkrzywanski.ocr.internal.pdf.PdfSamples;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OcrPdfTest {
    private static OcrEngine engine;

    @BeforeAll
    static void open() {
        assumeTrue(modelsPresent(), "models/ is missing");
        engine = OcrEngine.create(OcrConfig.defaults(Path.of("models")));
    }

    @AfterAll
    static void shutdown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void pdfPageIsReadable() throws Exception {
        Path pdf = Files.createTempFile("invoice", ".pdf");
        PdfSamples.writeAsciiPdf(pdf);
        List<OcrResult> pages = OcrPdf.recognize(engine, pdf, 120f);
        assertEquals(1, pages.size());
        String text = pages.get(0).text();
        System.out.println(text);
        System.out.printf("pdf total %.1f ms%n", pages.get(0).totalMillis());
        assertTrue(text.contains("FAKTURA"), text);
        assertTrue(text.contains("1234567890") || text.contains("NIP"), text);
        Files.deleteIfExists(pdf);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void pdfPagesStayInOrder() throws Exception {
        Path pdf = Files.createTempFile("pages", ".pdf");
        try {
            PdfSamples.writeAsciiPages(pdf, "FAKTURA JEDEN", "NIP 1234567890", "HELLO POLAND");
            List<OcrResult> pages = OcrPdf.recognize(engine, pdf, 120f);
            assertEquals(3, pages.size());
            System.out.println("pdf0: " + pages.get(0).text());
            System.out.println("pdf1: " + pages.get(1).text());
            System.out.println("pdf2: " + pages.get(2).text());
            assertTrue(pages.get(0).text().contains("FAKTURA"), pages.get(0).text());
            assertTrue(pages.get(0).text().contains("JEDEN"), pages.get(0).text());
            assertTrue(pages.get(1).text().contains("1234567890") || pages.get(1).text().contains("NIP"),
                    pages.get(1).text());
            assertTrue(pages.get(2).text().toUpperCase().contains("HELLO"), pages.get(2).text());
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void emptyPdfHasNoPages() throws Exception {
        Path pdf = Files.createTempFile("empty", ".pdf");
        try {
            PdfSamples.writeAsciiPages(pdf);
            assertEquals(List.of(), OcrPdf.recognize(engine, pdf, 72f));
            assertThrows(OcrException.class, () -> OcrPdf.recognize(engine, pdf, 0f));
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    private static boolean modelsPresent() {
        return Files.exists(Path.of("models", ModelFiles.DET))
                && Files.exists(Path.of("models", ModelFiles.REC))
                && Files.exists(Path.of("models", ModelFiles.CLS))
                && Files.exists(Path.of("models", ModelFiles.DICT));
    }
}
