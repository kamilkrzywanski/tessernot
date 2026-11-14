package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.eval.Samples;
import io.github.kamilkrzywanski.ocr.internal.eval.Metrics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.kamilkrzywanski.ocr.internal.image.Images;
import io.github.kamilkrzywanski.ocr.internal.runtime.Graph;
import io.github.kamilkrzywanski.ocr.internal.runtime.Jir;
import io.github.kamilkrzywanski.ocr.internal.runtime.Node;
import io.github.kamilkrzywanski.ocr.internal.runtime.Sig;
import io.github.kamilkrzywanski.ocr.internal.runtime.Tensor;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OcrEndToEndTest {
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

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void droppingFragmentsKeepsSureShortLines() {
        assertFalse(OcrConfig.defaults(Path.of("models")).dropFragments());
        OcrConfig config = OcrConfig.defaults(Path.of("models")).toBuilder().dropFragments(true).build();
        assertTrue(config.toBuilder().build().dropFragments());
        BufferedImage image = new BufferedImage(900, 260, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = Images.prepare(image);
        try {
            g.setFont(Samples.font(48f));
            g.drawString("FAKTURA 2024", 40, 84);
            g.drawString("NIP", 40, 200);
        } finally {
            g.dispose();
        }
        try (OcrEngine dropping = OcrEngine.create(config)) {
            String text = dropping.recognize(image).text();
            assertTrue(text.contains("FAKTURA"), text);
            assertTrue(text.contains("NIP"), text);
        }
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
        OcrException pages = assertThrows(OcrException.class, () -> other.recognizePages(1, i -> blank, null));
        assertTrue(pages.getMessage().contains("closed"), pages.getMessage());
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

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void aPageOnItsSideOrUpsideDownReadsAsTheUprightOne() {
        BufferedImage upright = Samples.invoice();
        OcrResult straight = engine.recognize(upright);
        assertEquals(0, straight.rotation());
        TextLine title = straight.lines().get(0);
        BufferedImage page = upright;
        // Each step turns the picture a quarter clockwise: the turn back grows by 270 each time.
        for (int quarter = 1; quarter <= 3; quarter++) {
            BufferedImage turned = new BufferedImage(page.getHeight(), page.getWidth(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < page.getHeight(); y++) {
                for (int x = 0; x < page.getWidth(); x++) {
                    turned.setRGB(page.getHeight() - 1 - y, x, page.getRGB(x, y));
                }
            }
            page = turned;
            OcrResult result = engine.recognize(page);
            assertEquals((360 - 90 * quarter) % 360, result.rotation(), "quarter " + quarter);
            assertEquals(straight.text(), result.text(), "quarter " + quarter);
            // The first line lies where the turn put it, in the pixels of the turned picture.
            Rectangle was = title.bounds();
            Rectangle expected = switch (quarter) {
                case 1 -> new Rectangle(upright.getHeight() - was.y - was.height, was.x, was.height, was.width);
                case 2 -> new Rectangle(upright.getWidth() - was.x - was.width, upright.getHeight() - was.y - was.height,
                        was.width, was.height);
                default -> new Rectangle(was.y, upright.getWidth() - was.x - was.width, was.height, was.width);
            };
            Rectangle found = result.lines().get(0).bounds();
            assertEquals(title.text(), result.lines().get(0).text());
            // Its words too, corner for corner: the first corner of a word is the top left of its text.
            List<TextWord> words = result.lines().get(0).words();
            assertEquals(title.words().size(), words.size(), "quarter " + quarter);
            for (int w = 0; w < words.size(); w++) {
                float[] was2 = title.words().get(w).quad();
                float[] now = words.get(w).quad();
                for (int corner = 0; corner < 4; corner++) {
                    float x = was2[corner * 2];
                    float y = was2[corner * 2 + 1];
                    int width = upright.getWidth();
                    int height = upright.getHeight();
                    // The same quarter turns clockwise the picture was given.
                    for (int turn = 0; turn < quarter; turn++) {
                        float nx = height - y;
                        y = x;
                        x = nx;
                        int swap = width;
                        width = height;
                        height = swap;
                    }
                    assertEquals(x, now[corner * 2], 8f, "quarter " + quarter + " word " + w + " corner " + corner + " x");
                    assertEquals(y, now[corner * 2 + 1], 8f, "quarter " + quarter + " word " + w + " corner " + corner + " y");
                }
            }
            assertTrue(Math.abs(found.x - expected.x) <= 4 && Math.abs(found.y - expected.y) <= 4
                            && Math.abs(found.width - expected.width) <= 6 && Math.abs(found.height - expected.height) <= 6,
                    "quarter " + quarter + ": " + found + " for " + expected);
        }
        // With the turn off the page is read as it lies, and nothing is reported.
        OcrConfig asGiven = engine.config().toBuilder().pageOrientation(false).threads(2).build();
        try (OcrEngine plain = OcrEngine.create(asGiven)) {
            assertEquals(0, plain.recognize(page).rotation());
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void wordsLieWhereTheyArePrinted() {
        String[] texts = {"Zażółć gęślą jaźń i wróć do domu", "FAKTURA VAT nr 2024/10/0042 z dnia 12.10.2024",
                "il li ill Illinois minimum www MMM WWW"};
        BufferedImage page = new BufferedImage(1500, 420, BufferedImage.TYPE_INT_RGB);
        Graphics2D draw = Images.prepare(page);
        List<List<float[]>> truth = new ArrayList<>();
        try {
            draw.setFont(Samples.font(40f));
            FontMetrics metrics = draw.getFontMetrics();
            for (int i = 0; i < texts.length; i++) {
                draw.drawString(texts[i], 60, 100 + 120 * i);
                // Where the font put each word: from the width of the text before it.
                List<float[]> spans = new ArrayList<>();
                int at = 0;
                for (String word : texts[i].split(" ")) {
                    int from = texts[i].indexOf(word, at);
                    spans.add(new float[] {60 + metrics.stringWidth(texts[i].substring(0, from)),
                            60 + metrics.stringWidth(texts[i].substring(0, from + word.length()))});
                    at = from + word.length();
                }
                truth.add(spans);
            }
        } finally {
            draw.dispose();
        }
        OcrResult result = engine.recognize(page);
        assertEquals(texts.length, result.lines().size(), result.text());
        int checked = 0;
        for (int i = 0; i < texts.length; i++) {
            TextLine line = result.lines().get(i);
            assertEquals(texts[i], line.text());
            List<TextWord> words = line.words();
            assertEquals(truth.get(i).size(), words.size(), line.text());
            float height = line.bottom() - line.top();
            for (int w = 0; w < words.size(); w++) {
                float[] quad = words.get(w).quad();
                assertEquals(texts[i].split(" ")[w], words.get(w).text());
                // Both ends within half the line height, about one letter. The third line is the
                // hard one: runs of i and l beside m and W, where a timestep is wider than a letter.
                assertEquals(truth.get(i).get(w)[0], quad[0], height / 2f, line.text() + ": start of " + words.get(w).text());
                assertEquals(truth.get(i).get(w)[1], quad[2], height / 2f, line.text() + ": end of " + words.get(w).text());
                // The strip is as high as the line's box.
                assertEquals(line.top(), quad[1], 3f);
                assertEquals(line.bottom(), quad[5], 3f);
                checked++;
            }
        }
        assertEquals(22, checked);
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
