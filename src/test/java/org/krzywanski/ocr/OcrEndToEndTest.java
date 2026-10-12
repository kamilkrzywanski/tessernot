package org.krzywanski.ocr;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.krzywanski.ocr.image.Images;
import org.krzywanski.ocr.runtime.Graph;
import org.krzywanski.ocr.runtime.Jir;
import org.krzywanski.ocr.runtime.Node;
import org.krzywanski.ocr.runtime.Sig;
import org.krzywanski.ocr.runtime.Tensor;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OcrEndToEndTest {
    private static OcrEngine engine;

    @BeforeAll
    static void open() {
        assumeTrue(modelsPresent(), "models/ is missing");
        engine = OcrEngine.create(OcrConfig.latin(Path.of("models")));
    }

    @AfterAll
    static void shutdown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void renderedInvoiceStaysReadable() {
        OcrResult result = engine.recognize(Samples.invoice());
        System.out.println(result.text());
        System.out.printf("invoice det %.1f ms  rec %.1f ms  total %.1f ms  lines %d%n",
                result.detectionMillis(), result.recognitionMillis(), result.totalMillis(), result.lines().size());
        double cer = Metrics.cer(result.text(), Samples.invoiceText());
        System.out.println("CER " + cer);
        assertTrue(cer < 0.25, "CER " + cer + " text [" + result.text() + "]");
        assertTrue(result.text().contains("FAKTURA"), result.text());
        assertTrue(result.text().contains("NIP"), result.text());
        assertTrue(result.text().contains("Zażółć gęślą jaźń"), result.text());
        assertTrue(result.tables().isEmpty(), result.tables().toString());
    }

    @Test
    void fixedRecHeightMustMatchTheModel() throws Exception {
        Path dir = Files.createTempDirectory("ocr-rec-h");
        Path dict = dir.resolve("dict.txt");
        Path fixed = dir.resolve("fixed.jir");
        Path dynamic = dir.resolve("dynamic.jir");
        try {
            Files.writeString(dict, "A\n");
            saveRec(fixed, 32);
            saveRec(dynamic, -1);
            OcrException mismatch = assertThrows(OcrException.class,
                    () -> OcrEngine.create(recOnly(fixed, dict).recHeight(48).build()));
            assertTrue(mismatch.getMessage().contains("REC height 48"), mismatch.getMessage());
            assertTrue(mismatch.getMessage().contains("model input 32"), mismatch.getMessage());
            try (OcrEngine matched = OcrEngine.create(recOnly(fixed, dict).recHeight(32).build())) {
                assertTrue(matched != null);
            }
            try (OcrEngine open = OcrEngine.create(recOnly(dynamic, dict).recHeight(48).build())) {
                assertTrue(open != null);
            }
        } finally {
            Files.deleteIfExists(fixed);
            Files.deleteIfExists(dynamic);
            Files.deleteIfExists(dict);
            Files.deleteIfExists(dir);
        }
    }

    private static OcrConfig.Builder recOnly(Path model, Path dict) {
        return OcrConfig.builder()
                .recModel(model)
                .dict(dict)
                .useDet(false)
                .useCls(false)
                .useVector(false)
                .threads(1);
    }

    private static void saveRec(Path path, int height) {
        Graph graph = new Graph();
        graph.inputs.add(new Sig("x", Tensor.F32, new int[]{-1, 3, height, -1}));
        graph.outputs.add(new Sig("y", Tensor.F32, new int[]{-1, -1, -1}));
        graph.nodes.add(Node.of("Identity", "id", new String[]{"x"}, new String[]{"y"}));
        Jir.save(graph, path);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void recognizeLineReadsASingleRow() {
        BufferedImage image = new BufferedImage(900, 140, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = Images.prepare(image);
        try {
            g.setFont(Samples.font(48f));
            g.drawString("FAKTURA 2024", 40, 84);
        } finally {
            g.dispose();
        }
        OcrResult result = engine.recognizeLine(image);
        System.out.println("line: " + result.text());
        assertEquals(1, result.lines().size());
        assertEquals(1f, result.lines().get(0).detScore(), 0f);
        assertTrue(result.text().contains("FAKTURA"), result.text());

        String tightText = "FAKTURA 2024";
        OcrResult tight = engine.recognizeLine(tightLine(tightText));
        System.out.println("tight: " + tight.text());
        assertEquals(tightText, tight.text());
    }

    /** A line crop: the glyphs fill the bitmap, which is what recognition resizes to height 48. */
    private static BufferedImage tightLine(String text) {
        Font font = Samples.font(48f);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        int width = Math.max(32, fm.stringWidth(text) + 32);
        int height = Math.max(32, fm.getHeight() + 16);
        int baseline = fm.getAscent() + 8;
        pg.dispose();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = Images.prepare(image);
        try {
            g.setFont(font);
            g.drawString(text, 16, baseline);
        } finally {
            g.dispose();
        }
        return image;
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void pdfPageIsReadable() throws Exception {
        Path pdf = Files.createTempFile("invoice", ".pdf");
        Samples.writeAsciiPdf(pdf);
        List<OcrResult> pages = engine.recognizePdf(pdf, 120f);
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
            Samples.writeAsciiPages(pdf, "FAKTURA JEDEN", "NIP 1234567890", "HELLO POLAND");
            List<OcrResult> pages = engine.recognizePdf(pdf, 120f);
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
    void cliPdfPrintsPageHeadersOnStdout() throws Exception {
        Path pdf = Files.createTempFile("cli-pages", ".pdf");
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        try {
            Samples.writeAsciiPages(pdf, "FAKTURA JEDEN", "HELLO POLAND");
            System.setOut(new PrintStream(outBytes, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBytes, true, StandardCharsets.UTF_8));
            int code = Cli.execute(new String[]{"pdf", pdf.toString(), "--dpi", "120", "--threads", "2"});
            String text = outBytes.toString(StandardCharsets.UTF_8);
            String errors = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(0, code, errors);
            assertTrue(text.contains("--- page 1 ---"), text);
            assertTrue(text.contains("FAKTURA"), text);
            assertTrue(text.contains("--- page 2 ---"), text);
            assertTrue(text.contains("HELLO"), text);
            assertTrue(text.indexOf("--- page 1 ---") < text.indexOf("--- page 2 ---"), text);
            assertTrue(errors.contains("det "), errors);
            assertEquals(-1, errors.indexOf("--- page"));
        } finally {
            System.setOut(out);
            System.setErr(err);
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void emptyPdfHasNoPages() throws Exception {
        Path pdf = Files.createTempFile("empty", ".pdf");
        try {
            Samples.writeAsciiPages(pdf);
            assertEquals(List.of(), engine.recognizePdf(pdf, 72f));
            assertThrows(OcrException.class, () -> engine.recognizePdf(pdf, 0f));
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void exifOrientationSixReadsTheUprightLine() throws Exception {
        BufferedImage visual = new BufferedImage(900, 140, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = Images.prepare(visual);
        try {
            g.setFont(Samples.font(48f));
            g.drawString("FAKTURA 2024", 40, 84);
        } finally {
            g.dispose();
        }
        Path plain = Files.createTempFile("faktura-plain", ".jpg");
        Path turned = Files.createTempFile("faktura-exif", ".jpg");
        try {
            Files.write(plain, jpegBytes(visual));
            Files.write(turned, tagOrientation(jpegBytes(rotateCcw(visual)), 6));
            BufferedImage read = Images.read(turned);
            assertEquals(visual.getWidth(), read.getWidth());
            assertEquals(visual.getHeight(), read.getHeight());
            OcrResult plainResult = engine.recognize(Images.read(plain));
            OcrResult turnedResult = engine.recognize(read);
            System.out.println("jpeg: " + plainResult.text());
            System.out.println("exif6: " + turnedResult.text());
            assertTrue(plainResult.text().contains("FAKTURA"), plainResult.text());
            assertTrue(turnedResult.text().contains("FAKTURA"), turnedResult.text());
        } finally {
            Files.deleteIfExists(plain);
            Files.deleteIfExists(turned);
        }
    }

    @Test
    void closedEngineRejectsTheNextCall() {
        OcrEngine other = OcrEngine.create(engine.config().toBuilder().threads(1).build());
        BufferedImage blank = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = Images.prepare(blank);
        g.dispose();
        try {
            assertTrue(other.recognize(blank).lines().isEmpty());
        } finally {
            other.close();
        }
        other.close();
        OcrException error = assertThrows(OcrException.class, () -> other.recognize(blank));
        assertTrue(error.getMessage().contains("closed"), error.getMessage());
        assertThrows(OcrException.class, () -> other.recognizeLine(blank));
        OcrException pdf = assertThrows(OcrException.class, () -> other.recognizePdf(Path.of("missing.pdf"), 72f));
        assertTrue(pdf.getMessage().contains("closed"), pdf.getMessage());
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void blankTinyAndNullImagesStaySafe() {
        BufferedImage blank = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = Images.prepare(blank);
        g.dispose();
        OcrResult blankResult = engine.recognize(blank);
        assertTrue(blankResult.lines().isEmpty(), blankResult.text());

        BufferedImage tiny = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        OcrResult tinyResult = engine.recognize(tiny);
        assertTrue(tinyResult.lines().isEmpty(), tinyResult.text());
        assertThrows(OcrException.class, () -> engine.recognize(null));
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void verticalLineIsTurnedUpright() {
        BufferedImage row = new BufferedImage(520, 96, BufferedImage.TYPE_INT_RGB);
        Graphics2D draw = Images.prepare(row);
        try {
            draw.setFont(Samples.font(42f));
            draw.drawString("FAKTURA", 24, 64);
        } finally {
            draw.dispose();
        }
        BufferedImage page = new BufferedImage(row.getHeight(), row.getWidth(), BufferedImage.TYPE_INT_RGB);
        Graphics2D turn = page.createGraphics();
        try {
            turn.translate(page.getWidth(), 0);
            turn.rotate(Math.PI / 2);
            turn.drawImage(row, 0, 0, null);
        } finally {
            turn.dispose();
        }
        OcrResult result = engine.recognize(page);
        System.out.println("vertical: " + result.text());
        assertTrue(result.text().contains("FAKTURA"), result.text());
    }

    /** Stored the way a camera tags Orientation 6: pixels are 90 degrees counter-clockwise. */
    private static BufferedImage rotateCcw(BufferedImage src) {
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage dst = new BufferedImage(height, width, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                dst.setRGB(y, width - 1 - x, src.getRGB(x, y));
            }
        }
        return dst;
    }

    private static byte[] jpegBytes(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(1f);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static byte[] tagOrientation(byte[] jpeg, int orientation) {
        byte[] payload = new byte[32];
        payload[0] = 'E';
        payload[1] = 'x';
        payload[2] = 'i';
        payload[3] = 'f';
        payload[6] = 'I';
        payload[7] = 'I';
        payload[8] = 42;
        payload[10] = 8;
        payload[14] = 1;
        payload[16] = 0x12;
        payload[17] = 0x01;
        payload[18] = 3;
        payload[20] = 1;
        payload[24] = (byte) orientation;
        byte[] segment = new byte[4 + payload.length];
        segment[0] = (byte) 0xff;
        segment[1] = (byte) 0xe1;
        segment[2] = 0;
        segment[3] = (byte) (payload.length + 2);
        System.arraycopy(payload, 0, segment, 4, payload.length);
        byte[] out = new byte[jpeg.length + segment.length];
        out[0] = jpeg[0];
        out[1] = jpeg[1];
        System.arraycopy(segment, 0, out, 2, segment.length);
        System.arraycopy(jpeg, 2, out, 2 + segment.length, jpeg.length - 2);
        return out;
    }

    private static boolean modelsPresent() {
        return Files.exists(Path.of("models", ModelFiles.DET))
                && Files.exists(Path.of("models", ModelFiles.REC))
                && Files.exists(Path.of("models", ModelFiles.CLS))
                && Files.exists(Path.of("models", ModelFiles.DICT));
    }
}
