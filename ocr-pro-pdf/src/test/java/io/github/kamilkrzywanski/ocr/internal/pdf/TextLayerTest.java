package io.github.kamilkrzywanski.ocr.internal.pdf;

import io.github.kamilkrzywanski.ocr.OcrResult;
import io.github.kamilkrzywanski.ocr.TextLine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextLayerTest {
    private static TextLine line(String text, float x, float y, float w, float h) {
        return new TextLine(text, 1f, 1f, new float[] {x, y, x + w, y, x + w, y + h, x, y + h}, null);
    }

    private static OcrResult page(TextLine... lines) {
        return new OcrResult("", List.of(lines), List.of(), 0, 0, 0);
    }

    private static byte[] saved(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    @Test
    void theLayerReadsBackWithPolishLettersAndInOrder() throws IOException {
        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A5);
            document.addPage(page);
            TextLayer layer = new TextLayer(document);
            // Rendered at 144 dpi: two pixels per point.
            layer.add(page, page(line("Zażółć gęślą jaźń", 100, 100, 500, 40),
                    line("ŁÓDŹ 90-001 €", 100, 160, 300, 40),
                    line("", 100, 220, 300, 40)), 144f);
            layer.finish();
            bytes = saved(document);
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertEquals("Zażółć gęślą jaźń\nŁÓDŹ 90-001 €", text.strip().replace("\r\n", "\n"));
        }
    }

    @Test
    void everyCharacterOfALargeAlphabetKeepsItsCode() throws IOException {
        // More characters than one font has codes for: 500 letters of several scripts.
        StringBuilder all = new StringBuilder();
        for (int c = 0x100; all.length() < 300; c++) {
            if (Character.isLetter(c)) {
                all.append((char) c);
            }
        }
        for (int c = 0x4e00; c < 0x4e00 + 200; c++) {
            all.append((char) c);
        }
        String first = all.substring(0, 250);
        String second = all.substring(250);
        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            TextLayer layer = new TextLayer(document);
            layer.add(page, page(line(first, 10, 100, 1000, 20), line(second, 10, 200, 1000, 20)), 144f);
            layer.finish();
            bytes = saved(document);
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(false);
            String text = stripper.getText(document).replace("\r", "").replace("\n", "").replace(" ", "");
            assertEquals(first + second, text);
            assertEquals(3, document.getPage(0).getResources().getCOSObject()
                    .getCOSDictionary(org.apache.pdfbox.cos.COSName.FONT).size());
        }
    }

    /** The glyphs of the layer as the stripper places them on the shown page: x, baseline y, width. */
    private static List<float[]> layerGlyphs(PDDocument document) throws IOException {
        List<float[]> found = new java.util.ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition p) {
                if (p.getFont() instanceof org.apache.pdfbox.pdmodel.font.PDType3Font) {
                    found.add(new float[] {p.getX(), p.getY(), p.getWidth(),
                            p.getTextMatrix().getTranslateX(), p.getTextMatrix().getTranslateY()});
                }
                super.processTextPosition(p);
            }
        };
        stripper.setSuppressDuplicateOverlappingText(false);
        stripper.getText(document);
        return found;
    }

    @Test
    void aLineLiesWhereItWasReadOnEveryRotationOfThePage() throws IOException {
        for (int rotation : new int[] {0, 90, 180, 270}) {
            byte[] bytes;
            int[] ink;
            try (PDDocument document = new PDDocument()) {
                // A crop box that does not start at the origin, and a bar that lies flat on the shown page.
                PDPage page = new PDPage(new PDRectangle(30, 40, 400, 600));
                page.setRotation(rotation);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    if (rotation % 180 == 0) {
                        content.addRect(80, 300, 200, 20);
                    } else {
                        content.addRect(300, 80, 20, 200);
                    }
                    content.fill();
                }
                // The page as the engine sees it, and the bar on it as a line box.
                ink = inkBox(new PDFRenderer(document).renderImageWithDPI(0, 144f));
                assertEquals(400, ink[2] - ink[0], 2, "the bar is 200 pt long, rotation " + rotation);
                TextLayer layer = new TextLayer(document);
                layer.add(page, page(line("on the bar", ink[0], ink[1], ink[2] - ink[0], ink[3] - ink[1])), 144f);
                layer.finish();
                bytes = saved(document);
            }
            try (PDDocument document = Loader.loadPDF(bytes)) {
                List<float[]> glyphs = layerGlyphs(document);
                assertEquals(10, glyphs.size(), "rotation " + rotation);
                float[] first = glyphs.get(0);
                float[] last = glyphs.get(9);
                // 144 dpi: two pixels a point. The string runs from the left end of the bar to its
                // right end, on a baseline a quarter of the bar's height above its lower edge.
                assertEquals(ink[0] / 2f, first[0], 0.5f, "left, rotation " + rotation);
                assertEquals(ink[2] / 2f, last[0] + last[2], 0.5f, "right, rotation " + rotation);
                assertEquals(ink[3] / 2f - 0.25f * (ink[3] - ink[1]) / 2f, first[1], 0.5f, "baseline, rotation " + rotation);
                assertEquals(first[1], last[1], 0.01f, "level, rotation " + rotation);
            }
        }
    }

    @Test
    void aStandingBoxHoldsItsLineFromTheTopDown() throws IOException {
        byte[] bytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 600));
            document.addPage(page);
            TextLayer layer = new TextLayer(document);
            // 20 px wide, 300 px high, at 72 dpi: one pixel a point.
            layer.add(page, page(line("downward", 100, 100, 20, 300)), 72f);
            layer.finish();
            bytes = saved(document);
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            List<float[]> glyphs = layerGlyphs(document);
            assertEquals(8, glyphs.size());
            // In page coordinates, y up: the first glyph at the top of the box (y = 600 - 100),
            // the last near its bottom, all on the baseline a quarter in from the left edge.
            assertEquals(500f, glyphs.get(0)[4], 0.5f);
            assertTrue(glyphs.get(7)[4] > 200f && glyphs.get(7)[4] < 300f, "last glyph at y " + glyphs.get(7)[4]);
            for (float[] glyph : glyphs) {
                assertEquals(105f, glyph[3], 0.5f);
            }
        }
    }

    @Test
    void theLayerDrawsNothing() throws IOException {
        Path file = Files.createTempFile("layer", ".pdf");
        try {
            PdfSamples.writeAsciiPdf(file);
            int[] before;
            int[] after;
            try (PDDocument document = Loader.loadPDF(file.toFile())) {
                before = pixels(new PDFRenderer(document).renderImageWithDPI(0, 72f));
                TextLayer layer = new TextLayer(document);
                layer.add(document.getPage(0), page(line("FAKTURA 2024", 50, 80, 300, 40)), 72f);
                layer.finish();
                after = pixels(new PDFRenderer(document).renderImageWithDPI(0, 72f));
            }
            assertArrayEquals(before, after);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void widthsFollowTheLetter() {
        try (PDDocument document = new PDDocument()) {
            TextLayer layer = new TextLayer(document);
            assertEquals(layer.width('l'), layer.width('ł'));
            assertEquals(layer.width('a'), layer.width('ą'));
            assertEquals(layer.width('Z'), layer.width('Ż'));
            assertTrue(layer.width('i') < layer.width('m'));
            assertEquals(1000, layer.width(0x4e2d));
            assertEquals(layer.width('n'), layer.width(0x0416));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    /** Left, top, right, bottom of the dark pixels. */
    private static int[] inkBox(BufferedImage image) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 255) < 128) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x + 1);
                    bottom = Math.max(bottom, y + 1);
                }
            }
        }
        return new int[] {left, top, right, bottom};
    }
}
