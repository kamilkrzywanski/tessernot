package io.github.kamilkrzywanski.ocr.post;

import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.ocr.Metrics;
import io.github.kamilkrzywanski.ocr.ModelFiles;
import io.github.kamilkrzywanski.ocr.OcrConfig;
import io.github.kamilkrzywanski.ocr.OcrEngine;
import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.TextLine;
import io.github.kamilkrzywanski.ocr.image.Bgr;
import io.github.kamilkrzywanski.ocr.image.Geometry;
import io.github.kamilkrzywanski.ocr.image.Images;
import io.github.kamilkrzywanski.ocr.runtime.Graph;
import io.github.kamilkrzywanski.ocr.runtime.Jir;
import io.github.kamilkrzywanski.ocr.runtime.Node;
import io.github.kamilkrzywanski.ocr.runtime.Sig;
import io.github.kamilkrzywanski.ocr.runtime.Tensor;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostProcessTest {
    @Test
    void ctcCollapsesRepeatsAroundBlank() {
        Charset charset = Charset.of("a", "b", "c");
        float[] steps = {
                0, 1, 0, 0,
                0, 1, 0, 0,
                1, 0, 0, 0,
                0, 0, 1, 0,
                0, 0, 1, 0
        };
        Ctc.Result result = Ctc.greedy(steps, 5, 4, charset);
        assertEquals("ab", result.text());
        assertEquals(1f, result.score(), 1e-6);
    }

    @Test
    void ctcDecodesTimeMajorAndBatchedTensorsTheSameWay() {
        Charset charset = Charset.of("a", "b");
        float[] steps = {
                0, 1, 0,
                1, 0, 0,
                0, 0, 1
        };
        Ctc.Result flat = Ctc.greedy(steps, 3, 3, charset);
        Ctc.Result matrix = Ctc.greedy(Tensor.f32(new int[]{3, 3}, steps), charset);
        Ctc.Result batched = Ctc.greedy(Tensor.f32(new int[]{1, 3, 3}, steps), charset);
        assertEquals("ab", flat.text());
        assertEquals(flat.text(), matrix.text());
        assertEquals(flat.text(), batched.text());
        assertEquals(flat.score(), matrix.score(), 1e-6);
        assertEquals(flat.score(), batched.score(), 1e-6);
    }

    @Test
    void ctcKeepsACloseSpaceAfterAColon() {
        Charset charset = Charset.of(":", "a", " ");
        float[] kept = {
                0f, 1f, 0f, 0f,
                0.60f, 0f, 0f, 0.40f
        };
        assertEquals(": ", Ctc.greedy(kept, 2, 4, charset, true).text());

        float[] below = {
                0f, 1f, 0f, 0f,
                0.66f, 0f, 0f, 0.34f
        };
        assertEquals(":", Ctc.greedy(below, 2, 4, charset, true).text());

        float[] notAfterColon = {
                0f, 0f, 1f, 0f,
                0.60f, 0f, 0f, 0.40f
        };
        assertEquals("a", Ctc.greedy(notAfterColon, 2, 4, charset, true).text());
    }

    @Test
    void ctcSwapsAListedRunnerUpAboveItsMargin() {
        assertEquals("x", Ctc.greedy(new float[]{0.03f, 0.61f, 0.36f}, 1, 3, Charset.of("×", "x"), true).text());
        assertEquals("×", Ctc.greedy(new float[]{0.05f, 0.61f, 0.34f}, 1, 3, Charset.of("×", "x"), true).text());
        assertEquals("ł", Ctc.greedy(new float[]{0f, 0.65f, 0.35f}, 1, 3, Charset.of("l", "ł"), true).text());
        assertEquals("l", Ctc.greedy(new float[]{0.02f, 0.65f, 0.33f}, 1, 3, Charset.of("l", "ł"), true).text());
        assertEquals("ł", Ctc.greedy(new float[]{0.09f, 0.76f, 0.15f}, 1, 3, Charset.of("t", "ł"), true).text());
        assertEquals("t", Ctc.greedy(new float[]{0.10f, 0.76f, 0.14f}, 1, 3, Charset.of("t", "ł"), true).text());
        assertEquals("t", Ctc.greedy(new float[]{0.05f, 0.50f, 0.40f, 0.05f}, 1, 4, Charset.of("t", "a", "ł"), true).text());
        assertEquals(",", Ctc.greedy(new float[]{0.01f, 0.65f, 0.34f}, 1, 3, Charset.of(".", ","), true).text());
        assertEquals(".", Ctc.greedy(new float[]{0.02f, 0.65f, 0.33f}, 1, 3, Charset.of(".", ","), true).text());
        assertEquals("ć", Ctc.greedy(new float[]{0.12f, 0.71f, 0.17f}, 1, 3, Charset.of("ó", "ć"), true).text());
        assertEquals("ó", Ctc.greedy(new float[]{0.16f, 0.70f, 0.14f}, 1, 3, Charset.of("ó", "ć"), true).text());
        assertEquals("ó", Ctc.greedy(new float[]{0.03f, 0.51f, 0.46f, 0f}, 1, 4, Charset.of("ó", "o", "ć"), true).text());
        assertEquals("Ś", Ctc.greedy(new float[]{0.010f, 0.975f, 0.015f}, 1, 3, Charset.of("S", "Ś"), true).text());
        assertEquals("S", Ctc.greedy(new float[]{0.020f, 0.966f, 0.014f}, 1, 3, Charset.of("S", "Ś"), true).text());
        assertEquals("S", Ctc.greedy(new float[]{0.02f, 0.80f, 0.16f, 0.02f}, 1, 4, Charset.of("S", "A", "Ś"), true).text());
        assertEquals("s", Ctc.greedy(new float[]{0.0095f, 0.983f, 0.0075f}, 1, 3, Charset.of("s", "ś"), true).text());
        assertEquals("ó", Ctc.greedy(new float[]{0.02f, 0.93f, 0.05f}, 1, 3, Charset.of("o", "ó"), true).text());
        assertEquals("o", Ctc.greedy(new float[]{0.031f, 0.920f, 0.049f}, 1, 3, Charset.of("o", "ó"), true).text());
        assertEquals("o", Ctc.greedy(new float[]{0.02f, 0.70f, 0.20f, 0.08f}, 1, 4, Charset.of("o", "a", "ó"), true).text());
    }

    @Test
    void ctcWithoutCorrectionsKeepsTheWinner() {
        Charset colon = Charset.of(":", "a", " ");
        assertEquals(":", Ctc.greedy(new float[]{0f, 1f, 0f, 0f, 0.60f, 0f, 0f, 0.40f}, 2, 4, colon).text());
        assertEquals("l", Ctc.greedy(new float[]{0f, 0.65f, 0.35f}, 1, 3, Charset.of("l", "ł")).text());
        assertEquals("S", Ctc.greedy(new float[]{0.010f, 0.975f, 0.015f}, 1, 3, Charset.of("S", "Ś")).text());
        assertEquals(".", Ctc.greedy(new float[]{0.01f, 0.65f, 0.34f}, 1, 3, Charset.of(".", ",")).text());
    }

    @Test
    void ctcSoftmaxesLogitsOnce() {
        Charset charset = Charset.of("a", "b");
        Ctc.Result result = Ctc.greedy(new float[]{0f, 8f, 0f}, 1, 3, charset);
        assertEquals("a", result.text());
        assertTrue(result.score() > 0.99f);
        Ctc.Result again = Ctc.greedy(Ctc.softmax(new float[]{0f, 8f, 0f}, 1, 3), 1, 3, charset);
        assertEquals(result.text(), again.text());
        assertEquals(result.score(), again.score(), 1e-5);
    }

    @Test
    void charsetSkipsEmptyLinesAndAppendsSpace() throws Exception {
        Path path = Files.createTempFile("dict", ".txt");
        Files.writeString(path, "\uFEFFA\nB\nĄ\n \n\n");
        Charset charset = Charset.load(path, true);
        assertEquals(6, charset.size());
        assertEquals("", charset.at(0));
        assertEquals("A", charset.at(1));
        assertEquals("B", charset.at(2));
        assertEquals("Ą", charset.at(3));
        assertEquals(" ", charset.at(4));
        assertEquals(" ", charset.at(5));
        Files.deleteIfExists(path);
    }

    @Test
    void recDictHasPolishLettersAndSpaceLast() {
        Path path = Path.of("models", ModelFiles.DICT);
        assumeTrue(Files.exists(path));
        Charset charset = Charset.load(path, true);
        assertEquals(18710, charset.size());
        assertEquals(" ", charset.at(18709));
        for (int i = 0; i < "ĄąĘęŁłŃńÓóŚśŹźŻż".length(); ) {
            int cp = "ĄąĘęŁłŃńÓóŚśŹźŻż".codePointAt(i);
            String letter = new String(Character.toChars(cp));
            assertTrue(charset.indexOf(letter) > 0, letter);
            i += Character.charCount(cp);
        }
    }

    @Test
    void wrappedDetectionMapIsRejected() throws Exception {
        float[] empty = new float[0];
        OcrException wrapped = assertThrows(OcrException.class,
                () -> DbPost.find(empty, 65_536, 65_536, 0.3f, 0.5f, 1.6f, false, 10));
        assertTrue(wrapped.getMessage().contains("65536x65536"), wrapped.getMessage());
        OcrException negative = assertThrows(OcrException.class,
                () -> DbPost.find(empty, -1, 2, 0.3f, 0.5f, 1.6f, false, 10));
        assertTrue(negative.getMessage().contains("-1x2"), negative.getMessage());
        OcrException shortMap = assertThrows(OcrException.class,
                () -> DbPost.find(new float[3], 2, 2, 0.3f, 0.5f, 1.6f, false, 10));
        assertTrue(shortMap.getMessage().contains("2x2"), shortMap.getMessage());
        assertEquals(0, DbPost.find(empty, 0, 0, 0.3f, 0.5f, 1.6f, false, 10).size());

        Path dir = Files.createTempDirectory("ocr-det-wrap");
        Path model = dir.resolve("det.jir");
        try {
            Graph graph = new Graph();
            graph.inputs.add(new Sig("x", Tensor.F32, new int[]{1, 3, 32, 32}));
            graph.outputs.add(new Sig("y", Tensor.F32, null));
            graph.initializers.put("map", Tensor.f32(new int[]{1, 0, 46_341, 46_341}, new float[0]));
            graph.nodes.add(Node.of("Identity", "id", new String[]{"map"}, new String[]{"y"}));
            Jir.save(graph, model);
            try (OcrEngine engine = OcrEngine.create(OcrConfig.builder()
                    .detModel(model)
                    .threads(1)
                    .useRec(false)
                    .useCls(false)
                    .useVector(false)
                    .build())) {
                OcrException detected = assertThrows(OcrException.class,
                        () -> engine.recognize(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)));
                assertTrue(detected.getMessage().contains("46341x46341"), detected.getMessage());
            }
        } finally {
            Files.deleteIfExists(model);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void dbFindsTheBrightRectangle() {
        int width = 40;
        int height = 30;
        float[] prob = new float[width * height];
        for (int y = 4; y <= 10; y++) {
            for (int x = 5; x <= 14; x++) {
                prob[y * width + x] = 1f;
            }
        }
        List<DbPost.Box> boxes = DbPost.find(prob, width, height, 0.3f, 0.5f, 1.6f, false, 1000);
        assertEquals(1, boxes.size());
        DbPost.Box box = boxes.get(0);
        assertTrue(box.score() > 0.5f);
        assertTrue(box.centerX() > 5f && box.centerX() < 14f, "cx " + box.centerX());
        assertTrue(box.centerY() > 4f && box.centerY() < 10f, "cy " + box.centerY());
    }

    @Test
    void dilationExpandsTowardTheTopLeft() {
        byte[] src = new byte[9];
        src[8] = 1;
        byte[] dst = DbPost.dilate2x2(src, 3, 3);
        assertEquals(1, dst[4]);
        assertEquals(1, dst[5]);
        assertEquals(1, dst[7]);
        assertEquals(1, dst[8]);
        assertEquals(0, dst[0]);
    }

    @Test
    void cornerTouchIsOneComponent() {
        int width = 8;
        float[] prob = new float[width * width];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                prob[y * width + x] = 1f;
            }
        }
        for (int y = 4; y < 8; y++) {
            for (int x = 4; x < 8; x++) {
                prob[y * width + x] = 1f;
            }
        }
        List<DbPost.Box> boxes = DbPost.find(prob, width, width, 0.3f, 0.5f, 1.5f, false, 1000);
        assertEquals(1, boxes.size());
    }

    @Test
    void maxCandidatesCountsComponentsInRasterOrder() {
        float[] prob = new float[8 * 8];
        prob[1] = 1f;
        prob[1 + 8] = 1f;
        prob[1 + 16] = 1f;
        prob[6] = 1f;
        prob[6 + 8] = 1f;
        prob[6 + 16] = 1f;
        List<DbPost.Box> boxes = DbPost.find(prob, 8, 8, 0.3f, 0.2f, 1.5f, false, 1);
        assertTrue(boxes.size() <= 1);
    }

    @Test
    void readingOrderJoinsASharedRow() {
        TextLine right = line("B", 50, 1, 30, 10);
        TextLine below = line("C", 0, 40, 30, 10);
        TextLine left = line("A", 0, 0, 30, 10);
        List<List<TextLine>> rows = ReadingOrder.group(List.of(below, right, left));
        assertEquals(2, rows.size());
        assertEquals("A", rows.get(0).get(0).text());
        assertEquals("B", rows.get(0).get(1).text());
        assertEquals("C", rows.get(1).get(0).text());
        assertEquals("A B\nC", ReadingOrder.join(rows));
        assertEquals(3, ReadingOrder.flatten(rows).size());
    }

    @Test
    void readingOrderKeepsAPriceOnItsRow() {
        TextLine bread = line("Chleb", 0, 0, 40, 10);
        TextLine breadPrice = line("1.99", 90, 0, 20, 10);
        TextLine cheese = line("Ser", 0, 12, 30, 10);
        TextLine cheesePrice = line("3.29", 100, 12, 10, 10);
        TextLine meat = line("Mieso", 0, 24, 36, 10);
        TextLine meatPrice = line("4.99", 95, 24, 15, 10);
        String text = ReadingOrder.join(ReadingOrder.group(
                List.of(meatPrice, bread, cheesePrice, meat, breadPrice, cheese)));
        assertEquals("Chleb 1.99\nSer 3.29\nMieso 4.99", text);
    }

    @Test
    void readingOrderReadsTwoTextColumns() {
        TextLine left1 = line("L1", 0, 0, 40, 10);
        TextLine right1 = line("R1", 70, 0, 25, 10);
        TextLine left2 = line("L2", 0, 12, 20, 10);
        TextLine right2 = line("R2", 70, 12, 40, 10);
        TextLine left3 = line("L3", 0, 24, 30, 10);
        TextLine right3 = line("R3", 70, 24, 15, 10);
        String text = ReadingOrder.join(ReadingOrder.group(
                List.of(right2, left1, right3, left3, right1, left2)));
        assertEquals("L1\nL2\nL3\nR1\nR2\nR3", text);
    }

    @Test
    void verticalWordDoesNotGlueColumns() {
        TextLine tall = line("ORYGINAL", 0, 0, 8, 50);
        TextLine left1 = line("L1", 20, 0, 30, 10);
        TextLine right1 = line("R1", 70, 0, 25, 10);
        TextLine left2 = line("L2", 20, 12, 20, 10);
        TextLine right2 = line("R2", 70, 12, 40, 10);
        TextLine left3 = line("L3", 20, 24, 30, 10);
        TextLine right3 = line("R3", 70, 24, 15, 10);
        String text = ReadingOrder.join(ReadingOrder.group(
                List.of(right1, tall, left2, right3, left1, right2, left3)));
        assertEquals("ORYGINAL\nL1\nL2\nL3\nR1\nR2\nR3", text);
    }

    @Test
    void fullWidthLineSeparatesColumnBlocks() {
        List<TextLine> lines = List.of(
                line("L1", 0, 0, 40, 10),
                line("R1", 70, 0, 25, 10),
                line("L2", 0, 12, 20, 10),
                line("R2", 70, 12, 40, 10),
                line("TITLE", 0, 40, 110, 10),
                line("L3", 0, 60, 40, 10),
                line("R3", 70, 60, 25, 10),
                line("L4", 0, 72, 20, 10),
                line("R4", 70, 72, 40, 10));
        assertEquals("L1\nL2\nR1\nR2\nTITLE\nL3\nL4\nR3\nR4", ReadingOrder.join(ReadingOrder.group(lines)));
    }

    @Test
    void readingOrderKeepsAGridInRows() {
        List<TextLine> lines = List.of(
                line("a", 0, 0, 20, 10),
                line("b", 30, 0, 20, 10),
                line("c", 60, 0, 20, 10),
                line("d", 90, 0, 20, 10),
                line("e", 0, 12, 20, 10),
                line("f", 30, 12, 20, 10),
                line("g", 60, 12, 20, 10),
                line("h", 90, 12, 20, 10));
        assertEquals("a b c d\ne f g h", ReadingOrder.join(ReadingOrder.group(lines)));
    }

    @Test
    void rot90MatchesNumpy() {
        byte[] pixels = new byte[3 * 2 * 3];
        int[] values = {1, 2, 3, 4, 5, 6};
        for (int i = 0; i < values.length; i++) {
            pixels[i * 3] = pixels[i * 3 + 1] = pixels[i * 3 + 2] = (byte) values[i];
        }
        Bgr turned = Images.rot90ccw(new Bgr(pixels, 3, 2));
        assertEquals(2, turned.width());
        assertEquals(3, turned.height());
        int[] expect = {3, 6, 2, 5, 1, 4};
        for (int i = 0; i < expect.length; i++) {
            assertEquals(expect[i], turned.data()[i * 3] & 0xff);
        }
    }

    @Test
    void detSizeRoundsHalfUpTo32() {
        assertArrayEquals(new int[]{32, 32}, Images.detSize(40, 40, 960, "max"));
        assertArrayEquals(new int[]{64, 64}, Images.detSize(48, 48, 960, "max"));
        assertArrayEquals(new int[]{960, 480}, Images.detSize(1000, 500, 960, "max"));
        assertArrayEquals(new int[]{960, 960}, Images.detSize(40, 40, 960, "min"));
        assertArrayEquals(new int[]{32, 32}, Images.detSize(1, 1, 960, "max"));
        assertArrayEquals(new int[]{32, 32}, Images.detSize(16, 15, 960, "max"));
        OcrException huge = assertThrows(OcrException.class,
                () -> Images.detSize(Integer.MAX_VALUE, 1, Integer.MAX_VALUE, "max"));
        assertTrue(huge.getMessage().contains("2147483647x1"), huge.getMessage());
        OcrException scaled = assertThrows(OcrException.class,
                () -> Images.detSize(100_000, 1, 100_000, "min"));
        assertTrue(scaled.getMessage().contains("100000x1"), scaled.getMessage());
    }

    @Test
    void minAreaRectOfARectangle() {
        Geometry.Rect rect = Geometry.minAreaRect(new int[]{0, 10, 10, 0}, new int[]{0, 0, 4, 4});
        assertTrue(rect != null);
        double area = rect.width * rect.height;
        assertEquals(40.0, area, 1e-3);
        assertEquals(10.0, Math.max(rect.width, rect.height), 1e-3);
        assertEquals(4.0, Math.min(rect.width, rect.height), 1e-3);
    }

    @Test
    void textLineKeepsEightQuadValues() {
        TextLine line = new TextLine("a", 1f, 1f, null, null);
        assertEquals(8, line.quad().length);
        OcrException error = assertThrows(OcrException.class,
                () -> new TextLine("a", 1f, 1f, new float[4], new Rectangle()));
        assertTrue(error.getMessage().contains("8"), error.getMessage());
    }

    @Test
    void cerCountsASubstitution() {
        assertEquals(0.0, Metrics.cer("kot", "kot"));
        assertEquals(1.0 / 3.0, Metrics.cer("kat", "kot"), 1e-9);
        assertEquals(0.0, Metrics.cer("", ""));
        assertEquals(1.0, Metrics.cer("a", ""));
    }

    private static TextLine line(String text, float x, float y, float w, float h) {
        return new TextLine(text, 1f, 1f,
                new float[]{x, y, x + w, y, x + w, y + h, x, y + h},
                new Rectangle((int) x, (int) y, (int) w, (int) h));
    }
}
