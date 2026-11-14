package io.github.kamilkrzywanski.ocr.pdf;

import io.github.kamilkrzywanski.ocr.ModelFiles;
import io.github.kamilkrzywanski.ocr.OcrConfig;
import io.github.kamilkrzywanski.ocr.OcrEngine;
import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.OcrResult;
import io.github.kamilkrzywanski.ocr.internal.pdf.PdfSamples;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void aSearchableCopyKeepsThePagesAndCarriesTheirText() throws Exception {
        Path pdf = Files.createTempFile("pages", ".pdf");
        Path out = Files.createTempFile("searchable", ".pdf");
        try {
            PdfSamples.writeAsciiPages(pdf, "FAKTURA JEDEN", "HELLO POLAND");
            List<OcrResult> pages = OcrPdf.searchable(engine, pdf, out, 120f);
            assertEquals(2, pages.size());
            try (PDDocument document = Loader.loadPDF(out.toFile())) {
                assertEquals(2, document.getNumberOfPages());
                // The layer alone: the fonts of the page itself are left out.
                String layer = layerText(document);
                assertTrue(layer.contains("FAKTURA"), layer);
                assertTrue(layer.contains("JEDEN"), layer);
                assertTrue(layer.toUpperCase().contains("HELLO"), layer);
                assertTrue(layer.indexOf("FAKTURA") < layer.toUpperCase().indexOf("HELLO"), layer);
            }
            // The output may be the input.
            long before = Files.size(pdf);
            OcrPdf.searchable(engine, pdf, pdf, 120f);
            assertTrue(Files.size(pdf) > before);
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                assertTrue(layerText(document).contains("FAKTURA"));
            }
            assertThrows(OcrException.class, () -> OcrPdf.searchable(engine, pdf, out, 0f));
            assertThrows(OcrException.class, () -> OcrPdf.searchable(engine, pdf, null, 120f));
            assertThrows(OcrException.class, () -> OcrPdf.searchable(engine, pdf, out.getParent(), 120f));
        } finally {
            Files.deleteIfExists(pdf);
            Files.deleteIfExists(out);
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void anImageBecomesAOnePageSearchablePdf() throws Exception {
        Path png = Files.createTempFile("invoice", ".png");
        Path out = Files.createTempFile("searchable", ".pdf");
        try {
            java.awt.image.BufferedImage image = io.github.kamilkrzywanski.ocr.internal.eval.Samples.invoice();
            javax.imageio.ImageIO.write(image, "png", png.toFile());
            List<OcrResult> pages = OcrPdf.searchable(engine, png, out, 150f);
            assertEquals(1, pages.size());
            try (PDDocument document = Loader.loadPDF(out.toFile())) {
                assertEquals(1, document.getNumberOfPages());
                // 150 dpi: a pixel is 0.48 pt.
                assertEquals(image.getWidth() * 0.48f, document.getPage(0).getMediaBox().getWidth(), 0.01f);
                assertEquals(image.getHeight() * 0.48f, document.getPage(0).getMediaBox().getHeight(), 0.01f);
                String text = new PDFTextStripper().getText(document);
                assertTrue(text.contains("FAKTURA"), text);
                // The page shows the picture: the same pixels at the image's own size.
                java.awt.image.BufferedImage shown = new org.apache.pdfbox.rendering.PDFRenderer(document)
                        .renderImageWithDPI(0, 150f);
                assertEquals(image.getWidth(), shown.getWidth(), 1);
                assertEquals(image.getRGB(image.getWidth() / 2, 5) & 0xffffff, shown.getRGB(shown.getWidth() / 2, 5) & 0xffffff);
            }
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(out);
        }
    }

    /** The text written with Type 3 fonts, which only the layer uses. */
    private static String layerText(PDDocument document) throws java.io.IOException {
        StringBuilder out = new StringBuilder();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(org.apache.pdfbox.text.TextPosition p) {
                if (p.getFont() instanceof org.apache.pdfbox.pdmodel.font.PDType3Font) {
                    out.append(p.getUnicode());
                }
            }
        };
        stripper.setSuppressDuplicateOverlappingText(false);
        stripper.getText(document);
        return out.toString();
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
