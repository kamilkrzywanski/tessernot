package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.internal.eval.Metrics;
import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.ocr.DetLimit;
import io.github.kamilkrzywanski.ocr.ModelFiles;
import io.github.kamilkrzywanski.ocr.OcrConfig;
import io.github.kamilkrzywanski.ocr.OcrEngine;
import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.TextLine;
import io.github.kamilkrzywanski.ocr.internal.image.Bgr;
import io.github.kamilkrzywanski.ocr.internal.image.Geometry;
import io.github.kamilkrzywanski.ocr.internal.image.Images;
import io.github.kamilkrzywanski.ocr.internal.runtime.Graph;
import io.github.kamilkrzywanski.ocr.internal.runtime.Jir;
import io.github.kamilkrzywanski.ocr.internal.runtime.Node;
import io.github.kamilkrzywanski.ocr.internal.runtime.Sig;
import io.github.kamilkrzywanski.ocr.internal.runtime.Tensor;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        Charset amount = Charset.of("9", ".", ",", "s");
        assertEquals("9,", Ctc.greedy(new float[]{0f, 1f, 0f, 0f, 0f, 0.01f, 0f, 0.65f, 0.34f, 0f}, 2, 5, amount, true).text());
        assertEquals("9.", Ctc.greedy(new float[]{0f, 1f, 0f, 0f, 0f, 0.02f, 0f, 0.65f, 0.33f, 0f}, 2, 5, amount, true).text());
        // After a letter the period ends a sentence and stays.
        assertEquals("s.", Ctc.greedy(new float[]{0f, 0f, 0f, 0f, 1f, 0.01f, 0f, 0.65f, 0.34f, 0f}, 2, 5, amount, true).text());
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

    /** A letter that wins two timesteps is one letter, whichever of them carries the runner-up. */
    @Test
    void ctcRunnerUpIsDecidedOncePerRunOfTheWinner() {
        Charset el = Charset.of("l", "ł");
        assertEquals("ł", Ctc.greedy(new float[]{0.01f, 0.63f, 0.36f, 0.01f, 0.90f, 0.09f}, 2, 3, el, true).text());
        assertEquals("ł", Ctc.greedy(new float[]{0.01f, 0.90f, 0.09f, 0.01f, 0.63f, 0.36f}, 2, 3, el, true).text());
        assertEquals("ł", Ctc.greedy(new float[]{0.01f, 0.63f, 0.36f, 0.01f, 0.63f, 0.36f}, 2, 3, el, true).text());
        assertEquals("l", Ctc.greedy(new float[]{0.01f, 0.90f, 0.09f, 0.01f, 0.90f, 0.09f}, 2, 3, el, true).text());
        Charset tee = Charset.of("t", "ł");
        assertEquals("ł", Ctc.greedy(new float[]{0.04f, 0.80f, 0.16f, 0.04f, 0.92f, 0.04f}, 2, 3, tee, true).text());
        Charset oo = Charset.of("o", "ó");
        assertEquals("ó", Ctc.greedy(new float[]{0.02f, 0.96f, 0.02f, 0.02f, 0.92f, 0.06f}, 2, 3, oo, true).text());
        // A blank between two runs keeps them two letters, and only the run with the runner-up changes.
        assertEquals("łl", Ctc.greedy(new float[]{
                0.01f, 0.63f, 0.36f,
                0.98f, 0.01f, 0.01f,
                0.01f, 0.90f, 0.09f}, 3, 3, el, true).text());
        // A swapped run right before a run of that same letter is still one letter, as a repeat is.
        assertEquals("ł", Ctc.greedy(new float[]{
                0.01f, 0.63f, 0.36f,
                0.01f, 0.09f, 0.90f}, 2, 3, el, true).text());
        // The comma rule looks at the text before the whole run.
        Charset amount = Charset.of("9", ".", ",");
        assertEquals("9,", Ctc.greedy(new float[]{
                0f, 1f, 0f, 0f,
                0.01f, 0f, 0.90f, 0.09f,
                0.01f, 0f, 0.65f, 0.34f}, 3, 4, amount, true).text());
    }

    /** A runner-up that wins keeps the winner's probability, so a correction does not drop the line. */
    @Test
    void ctcScoreOfARunnerUpIsTheWinnersProbability() {
        Ctc.Result capital = Ctc.greedy(new float[]{0.010f, 0.975f, 0.015f}, 1, 3, Charset.of("S", "Ś"), true);
        assertEquals("Ś", capital.text());
        assertEquals(0.975f, capital.score(), 1e-6);
        Charset colon = Charset.of(":", "a", " ");
        Ctc.Result spaced = Ctc.greedy(new float[]{0f, 1f, 0f, 0f, 0.60f, 0f, 0f, 0.40f}, 2, 4, colon, true);
        assertEquals(": ", spaced.text());
        assertEquals(0.8f, spaced.score(), 1e-6);
        Ctc.Result plain = Ctc.greedy(new float[]{0.010f, 0.975f, 0.015f}, 1, 3, Charset.of("S", "Ś"));
        assertEquals(capital.score(), plain.score(), 0f);
    }

    @Test
    void runnerUpRulesLeaveOutCharactersTheCharsetLacks() {
        RunnerUps rules = RunnerUps.polish(Charset.of("a", "l"));
        assertEquals(-1, rules.swap(2, 1, 0.9f, true));
        assertEquals(-1, rules.spaceAfterColon(1, 0.9f));
        Ctc.Result result = Ctc.greedy(new float[]{0f, 0.1f, 0.9f}, 1, 3, Charset.of("a", "l"), rules, null);
        assertEquals("l", result.text());
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
    void paddedColumnsThatTouchStillSplit() {
        // A low-resolution page: unclip padding makes the two columns meet at x = 100.
        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lines.add(line("L" + i, 0, i * 12, 100, 10));
            lines.add(line("R" + i, 99, i * 12, 100, 10));
        }
        assertEquals("L0\nL1\nL2\nL3\nL4\nL5\nR0\nR1\nR2\nR3\nR4\nR5",
                ReadingOrder.join(ReadingOrder.group(lines)));
    }

    @Test
    void aShortLineDrawnOverItsNeighbourStaysInItsColumn() {
        // Columns that touch at x = 100. In the fourth row the left column ends with a short
        // reference whose box runs 7 px into the line of the right column.
        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            lines.add(i == 3 ? line("note", 70, i * 12, 37, 10) : line("L" + i, 0, i * 12, 100, 10));
            lines.add(line("R" + i, 99, i * 12, 100, 10));
        }
        assertEquals("L0\nL1\nL2\nnote\nL4\nL5\nL6\nL7\nR0\nR1\nR2\nR3\nR4\nR5\nR6\nR7",
                ReadingOrder.join(ReadingOrder.group(lines)));

        // The same reference without the overlap: the line of the right column is indented and
        // begins 4 px behind it. The gutter runs between the two.
        List<TextLine> apart = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            apart.add(i == 3 ? line("note", 70, i * 12, 37, 10) : line("L" + i, 0, i * 12, 100, 10));
            apart.add(i == 3 ? line("R3", 111, i * 12, 88, 10) : line("R" + i, 99, i * 12, 100, 10));
        }
        assertEquals("L0\nL1\nL2\nnote\nL4\nL5\nL6\nL7\nR0\nR1\nR2\nR3\nR4\nR5\nR6\nR7",
                ReadingOrder.join(ReadingOrder.group(apart)));

        // The same box with nothing beside it is a page number between the columns: it ends a section.
        List<TextLine> numbered = new ArrayList<>();
        StringBuilder upper = new StringBuilder();
        StringBuilder lower = new StringBuilder();
        for (String side : new String[] {"L", "R"}) {
            for (int i = 0; i < 13; i++) {
                if (i != 6) {
                    numbered.add(line(side + i, side.equals("L") ? 0 : 99, i * 12, 100, 10));
                    (i < 6 ? upper : lower).append(side).append(i).append('\n');
                }
            }
        }
        numbered.add(line("7", 85, 6 * 12, 30, 10));
        assertEquals(upper + "7\n" + lower.toString().strip(), ReadingOrder.join(ReadingOrder.group(numbered)));
    }

    /**
     * The boxes of a three-column magazine page scanned 3 degrees off level (The Independent,
     * 1898, from the benchmark). A few lines of the first two columns are merged over their
     * gutter. The side that holds those two columns has to split again, so a merged line must
     * not count for the width of the column its centre falls in. Each row of the file is a quad
     * and the number of characters read in it; the text here is that many letters.
     */
    @Test
    void aSkewedThreeColumnPageIsReadByColumns() throws java.io.IOException {
        List<TextLine> lines = new ArrayList<>();
        try (java.io.InputStream in = PostProcessTest.class.getResourceAsStream("/layout/three-columns-skewed.tsv")) {
            String[] rows = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < rows.length; i++) {
                String[] p = rows[i].split("\t");
                float[] quad = new float[8];
                for (int k = 0; k < 8; k++) {
                    quad[k] = Float.parseFloat(p[k]);
                }
                lines.add(new TextLine("w".repeat(Integer.parseInt(p[8])), 1f, 1f, quad, null));
            }
        }
        // Which column a box is in, on the levelled page: the gutters are at x = 731 and 1263.
        Map<TextLine, TextLine> original = new java.util.IdentityHashMap<>();
        Map<TextLine, Integer> column = new java.util.IdentityHashMap<>();
        for (TextLine level : PageSkew.level(lines, original)) {
            float centre = (level.left() + level.right()) * 0.5f;
            column.put(original.get(level), centre < 731f ? 0 : centre < 1263f ? 1 : 2);
        }
        // Below the title lines the page is about 70 rows of three columns. Read by columns the
        // order changes column a few times; read by rows, twice in every row.
        int changes = 0;
        int previous = -1;
        for (TextLine line : ReadingOrder.flatten(ReadingOrder.group(lines))) {
            int now = column.get(line);
            if (previous >= 0 && now != previous) {
                changes++;
            }
            previous = now;
        }
        assertTrue(changes < 40, "the order changes column " + changes + " times");
    }

    @Test
    void titleAcrossColumnsEndsASection() {
        List<TextLine> lines = List.of(
                line("L1", 0, 0, 40, 10),
                line("R1", 70, 0, 25, 10),
                line("L2", 0, 12, 20, 10),
                line("R2", 70, 12, 40, 10),
                line("TITLE", 0, 24, 110, 10),
                line("L3", 0, 36, 40, 10),
                line("R3", 70, 36, 25, 10),
                line("L4", 0, 48, 20, 10),
                line("R4", 70, 48, 40, 10));
        assertEquals("L1\nL2\nR1\nR2\nTITLE\nL3\nL4\nR3\nR4", ReadingOrder.join(ReadingOrder.group(lines)));
    }

    @Test
    void scatteredFormFieldsStayInRows() {
        // Fields of a ticket on the left (ragged, two to a row), the issuing office on the right.
        // Most rows hold two boxes, so this is not the grid rule.
        List<TextLine> lines = List.of(
                line("Dane", 60, 0, 60, 10),
                line("Kasa", 200, 0, 40, 10),
                line("Klasa", 0, 12, 30, 10),
                line("MENOS", 200, 12, 50, 10),
                line("2", 5, 24, 6, 10),
                line("TAM", 40, 24, 40, 10),
                line("Biala", 200, 24, 45, 10),
                line("POC", 30, 36, 30, 10),
                line("8611", 200, 36, 40, 10),
                line("Wyjazd", 0, 48, 40, 10),
                line("do", 90, 48, 15, 10),
                line("data", 200, 48, 40, 10));
        assertEquals("Dane Kasa\nKlasa MENOS\n2 TAM Biala\nPOC 8611\nWyjazd do data",
                ReadingOrder.join(ReadingOrder.group(lines)));
    }

    @Test
    void listNumbersAreNotAColumn() {
        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            lines.add(line((i + 1) + ".", 0, i * 12, 8, 10));
            lines.add(line("item " + i, 30, i * 12, 120, 10));
        }
        assertEquals("1. item 0\n2. item 1\n3. item 2\n4. item 3\n5. item 4",
                ReadingOrder.join(ReadingOrder.group(lines)));
    }

    @Test
    void headingsAtOneHeightDoNotCutColumns() {
        // Each column has its own heading, and both sit at the same height with blank rows around them.
        // A blank across the page is not a section break while the columns go on.
        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add(line("L" + i, 0, i * 12, 100, 10));
            lines.add(line("R" + i, 130, i * 12, 100, 10));
        }
        lines.add(line("H1", 30, 70, 40, 10));
        lines.add(line("H2", 160, 70, 40, 10));
        for (int i = 4; i < 8; i++) {
            lines.add(line("L" + i, 0, 44 + i * 12, 100, 10));
            lines.add(line("R" + i, 130, 44 + i * 12, 100, 10));
        }
        assertEquals("L0\nL1\nL2\nL3\nH1\nL4\nL5\nL6\nL7\nR0\nR1\nR2\nR3\nH2\nR4\nR5\nR6\nR7",
                ReadingOrder.join(ReadingOrder.group(lines)));
    }

    @Test
    void threeTextColumnsReadOneAfterAnother() {
        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lines.add(line("A" + i, 0, i * 12, 100, 10));
            lines.add(line("B" + i, 130, i * 12, 100, 10));
            lines.add(line("C" + i, 260, i * 12, 100, 10));
        }
        assertEquals("A0\nA1\nA2\nA3\nA4\nA5\nB0\nB1\nB2\nB3\nB4\nB5\nC0\nC1\nC2\nC3\nC4\nC5",
                ReadingOrder.join(ReadingOrder.group(lines)));
    }

    @Test
    void skewedColumnsSplitOnceTheyAreLevel() {
        // A photo turned by 3 degrees: down a column of 24 lines the gutter drifts by more than
        // its own width, so the right column's first line lands in the left column's first row.
        List<TextLine> lines = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            lines.add(turned("L" + i, 0, i * 14, 100, 10, 3));
            lines.add(turned("R" + i, 110, i * 14, 100, 10, 3));
            expected.add("L" + i);
        }
        for (int i = 0; i < 24; i++) {
            expected.add("R" + i);
        }
        assertEquals(String.join("\n", expected), ReadingOrder.join(ReadingOrder.group(lines)));
    }

    @Test
    void lineMergedAcrossTheGutterIsCutThere() {
        // Row 5 is one box over both columns. The title above is taller, and the full-width line
        // below has a blank above it, so neither is two column lines.
        List<TextLine> lines = new ArrayList<>();
        lines.add(line("TITLE", 40, 0, 150, 20));
        for (int i = 0; i < 12; i++) {
            float y = 30 + i * 12;
            if (i == 5) {
                lines.add(line("L5 R5", 0, y, 230, 10));
            } else {
                lines.add(line("L" + i, 0, y, 100, 10));
                lines.add(line("R" + i, 130, y, 100, 10));
            }
        }
        lines.add(line("WIDE", 0, 190, 230, 10));
        Map<TextLine, Float> cuts = ReadingOrder.cuts(lines);
        assertEquals(1, cuts.size());
        TextLine merged = cuts.keySet().iterator().next();
        assertEquals("L5 R5", merged.text());
        assertEquals(115f / 230f, cuts.get(merged), 0.03f);
    }

    @Test
    void runOfMergedLinesIsCutWhereTheGutterHides() {
        // Four of ten rows are one box over both columns, three of them in a run, so the gutter
        // only shows among the narrower boxes. The paragraph above the columns stays whole.
        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            lines.add(line("P" + i, 0, i * 12, 230, 10));
        }
        List<String> merged = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            float y = 50 + i * 12;
            if (i >= 2 && i <= 4 || i == 6) {
                lines.add(line("M" + i, 0, y, 230, 10));
                merged.add("M" + i);
            } else {
                lines.add(line("L" + i, 0, y, 100, 10));
                lines.add(line("R" + i, 130, y, 100, 10));
            }
        }
        Map<TextLine, Float> cuts = ReadingOrder.cuts(lines);
        List<String> found = new ArrayList<>();
        for (Map.Entry<TextLine, Float> cut : cuts.entrySet()) {
            found.add(cut.getKey().text());
            assertEquals(115f / 230f, cut.getValue(), 0.03f);
        }
        found.sort(null);
        assertEquals(merged, found);
    }

    @Test
    void totalsOfAReceiptStayOnTheirRows() {
        // The amounts do not share a right edge (the total is printed larger), but they are
        // numbers, so they stay on the rows of their labels.
        List<TextLine> lines = List.of(
                line("Sprzed. opod. PTU A", 0, 0, 180, 10),
                line("61,96", 300, 0, 50, 10),
                line("Kwota PTU A 23,00%", 0, 12, 170, 10),
                line("11,59", 302, 12, 48, 10),
                line("SUMA PTU", 0, 24, 80, 10),
                line("11,59", 303, 24, 48, 10),
                line("SUMA PLN", 0, 36, 80, 14),
                line("61,96", 270, 36, 95, 14));
        assertEquals("Sprzed. opod. PTU A 61,96\nKwota PTU A 23,00% 11,59\nSUMA PTU 11,59\nSUMA PLN 61,96",
                ReadingOrder.join(ReadingOrder.group(lines)));
    }

    @Test
    void rowsLayoutReadsColumnsRowByRow() {
        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lines.add(line("L" + i, 0, i * 12, 100, 10));
            lines.add(line("R" + i, 130, i * 12, 100, 10));
        }
        assertEquals("L0 R0\nL1 R1\nL2 R2\nL3 R3\nL4 R4\nL5 R5",
                ReadingOrder.join(ReadingOrder.group(lines, false)));
    }

    @Test
    void latinCharsetKeepsLatinGreekAndSharedSymbols() {
        Charset all = Charset.of("a", "中", "ł", "1", "€", "Ａ", "μ", "、", "カ", " ");
        int[] keep = all.latin();
        Charset latin = all.subset(keep);
        assertArrayEquals(new int[]{0, 1, 3, 4, 5, 7, 10}, keep);
        assertEquals("", latin.at(0));
        assertEquals("ł", latin.at(2));
        assertEquals(" ", latin.at(6));
        assertEquals(7, latin.size());
    }

    @Test
    void stackedLinesOfACurvedPageStayInTheirOrder() {
        // On a curved photo the boxes of two lines of one column overlap by 60 % of their height.
        // They share most of their width, so they are two rows, read top first.
        TextLine upper = line("upper", 10, 0, 190, 20);
        TextLine lower = line("lower", 0, 8, 190, 20);
        assertEquals("upper\nlower", ReadingOrder.join(ReadingOrder.group(List.of(lower, upper))));
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
        assertArrayEquals(new int[]{32, 32}, Images.detSize(40, 40, 960, DetLimit.MAX));
        assertArrayEquals(new int[]{64, 64}, Images.detSize(48, 48, 960, DetLimit.MAX));
        assertArrayEquals(new int[]{960, 480}, Images.detSize(1000, 500, 960, DetLimit.MAX));
        assertArrayEquals(new int[]{960, 960}, Images.detSize(40, 40, 960, DetLimit.MIN));
        assertArrayEquals(new int[]{32, 32}, Images.detSize(1, 1, 960, DetLimit.MAX));
        assertArrayEquals(new int[]{32, 32}, Images.detSize(16, 15, 960, DetLimit.MAX));
        OcrException huge = assertThrows(OcrException.class,
                () -> Images.detSize(Integer.MAX_VALUE, 1, Integer.MAX_VALUE, DetLimit.MAX));
        assertTrue(huge.getMessage().contains("2147483647x1"), huge.getMessage());
        OcrException scaled = assertThrows(OcrException.class,
                () -> Images.detSize(100_000, 1, 100_000, DetLimit.MIN));
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

    /** {@link #line} turned by {@code degrees} around the origin, as on a skewed photo. */
    private static TextLine turned(String text, float x, float y, float w, float h, double degrees) {
        double a = Math.toRadians(degrees);
        float[] q = {x, y, x + w, y, x + w, y + h, x, y + h};
        float[] out = new float[8];
        for (int i = 0; i < 8; i += 2) {
            out[i] = (float) (q[i] * Math.cos(a) - q[i + 1] * Math.sin(a));
            out[i + 1] = (float) (q[i] * Math.sin(a) + q[i + 1] * Math.cos(a));
        }
        return new TextLine(text, 1f, 1f, out, null);
    }
}
