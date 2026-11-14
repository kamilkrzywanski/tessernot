package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;
import io.github.kamilkrzywanski.ocr.internal.image.Bgr;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InkGuttersTest {
    private static final int W = 1000;
    private static final int H = 600;

    @Test
    void linesMergedOverAGutterAreCutInIt() {
        // Two columns of words with a 50 px gutter at x 450..500. Ten lines are one box over both
        // columns; six are two boxes that end and start at the gutter, as column lines do.
        Bgr page = blank();
        List<TextLine> lines = new ArrayList<>();
        List<TextLine> merged = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int y = 20 + i * 30;
            words(page, 50, 450, y);
            words(page, 500, 900, y);
            if (i < 10) {
                TextLine line = box("merged " + i, 40, 910, y);
                lines.add(line);
                merged.add(line);
            } else {
                lines.add(box("left " + i, 40, 470, y));
                lines.add(box("right " + i, 480, 910, y));
            }
        }
        Map<TextLine, float[]> cuts = InkGutters.cuts(page, lines);
        assertEquals(10, cuts.size());
        for (TextLine line : merged) {
            float[] t = cuts.get(line);
            assertEquals(1, t.length, Arrays.toString(t));
            assertEquals(435f / 870f, t[0], 0.02f);
        }
    }

    @Test
    void oneMergedLineIsCutWhereColumnMarginsAreStrong() {
        // Twelve rows of two plain columns and a single merged line at the bottom.
        Bgr page = blank();
        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int y = 20 + i * 30;
            words(page, 50, 450, y);
            words(page, 500, 900, y);
            lines.add(box("left " + i, 40, 470, y));
            lines.add(box("right " + i, 480, 910, y));
        }
        int y = 20 + 12 * 30;
        words(page, 50, 450, y);
        words(page, 500, 900, y);
        TextLine merged = box("merged", 40, 910, y);
        lines.add(merged);
        Map<TextLine, float[]> cuts = InkGutters.cuts(page, lines);
        assertEquals(1, cuts.size());
        assertEquals(435f / 870f, cuts.get(merged)[0], 0.02f);
    }

    @Test
    void alignedWordSpacesWithoutColumnMarginsStayWhole() {
        // One justified column whose lines all have a wide space at the same x: no line ends or
        // starts there, so it is not a gutter.
        Bgr page = blank();
        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int y = 20 + i * 30;
            words(page, 50, 462, y);
            words(page, 474, 900, y);
            lines.add(box("line " + i, 40, 910, y));
        }
        assertTrue(InkGutters.cuts(page, lines).isEmpty());
    }

    @Test
    void manyLinesWithInkAcrossASuggestedCutShowThatTheGutterIsNotOne() {
        // A letter in one column: every line has ink at x 475, where the boxes of a header and
        // a signature block made a gutter. None of the suggested cuts is made.
        Bgr letter = blank();
        Map<TextLine, Float> suggested = new java.util.IdentityHashMap<>();
        for (int i = 0; i < 12; i++) {
            int y = 20 + i * 30;
            words(letter, 50, 900, y);
            suggested.put(box("line " + i, 40, 910, y), 435f / 870f);
        }
        assertTrue(InkGutters.confirmed(letter, suggested).isEmpty());

        // Two such lines are not enough to say so: they are cut where it was suggested.
        suggested.clear();
        TextLine first = box("first", 40, 910, 20);
        suggested.put(first, 435f / 870f);
        suggested.put(box("second", 40, 910, 50), 435f / 870f);
        Map<TextLine, float[]> two = InkGutters.confirmed(letter, suggested);
        assertEquals(2, two.size());
        assertEquals(435f / 870f, two.get(first)[0]);
    }

    @Test
    void aSuggestedCutMovesToTheGutterInTheInk() {
        // Two columns with a gutter at x 450..500: three lines read as one box each are cut at
        // the middle of the gutter, not at 446 where the cut was suggested, in the last word of
        // the left column. A fourth line of the same gutter has ink across it and is cut where
        // it was suggested: most lines show the gutter.
        Bgr columns = blank();
        Map<TextLine, Float> suggested = new java.util.IdentityHashMap<>();
        List<TextLine> merged = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int y = 20 + i * 30;
            words(columns, 50, 450, y);
            words(columns, 500, 900, y);
            TextLine line = box("merged " + i, 40, 910, y);
            merged.add(line);
            suggested.put(line, 406f / 870f);
        }
        int y = 20 + 3 * 30;
        words(columns, 50, 900, y);
        TextLine across = box("across", 40, 910, y);
        suggested.put(across, 406f / 870f);
        Map<TextLine, float[]> cuts = InkGutters.confirmed(columns, suggested);
        assertEquals(4, cuts.size());
        for (TextLine line : merged) {
            assertEquals(435f / 870f, cuts.get(line)[0], 0.02f);
        }
        assertEquals(406f / 870f, cuts.get(across)[0]);

        // A cut suggested between letters, here in the gutter itself, stays where it is.
        Map<TextLine, float[]> inGutter = InkGutters.confirmed(columns, Map.of(merged.get(0), 420f / 870f));
        assertEquals(420f / 870f, inGutter.get(merged.get(0))[0]);

        // A box that cannot be looked at, here one too short, is cut as suggested.
        TextLine small = box("short", 40, 100, 300);
        Map<TextLine, float[]> kept = InkGutters.confirmed(columns, Map.of(small, 0.5f));
        assertEquals(0.5f, kept.get(small)[0]);
    }

    @Test
    void aRuleInANarrowGutterShowsTheGutter() {
        // Two columns set close: the left one ends 2 px before a rule 2 px wide at x 470, the
        // right one begins 2 px after it. There is no gap as wide as a gutter, but the rule runs
        // from the top of every box to its bottom, as no letter does.
        Bgr page = blank();
        Map<TextLine, Float> suggested = new java.util.IdentityHashMap<>();
        for (int i = 0; i < 5; i++) {
            int y = 20 + i * 30;
            words(page, 55, 468, y);
            words(page, 474, 900, y);
            suggested.put(box("merged " + i, 40, 910, y), 430f / 870f);
        }
        for (int y = 0; y < 200; y++) {
            for (int x = 470; x < 472; x++) {
                int i = (y * W + x) * 3;
                page.data()[i] = 0;
                page.data()[i + 1] = 0;
                page.data()[i + 2] = 0;
            }
        }
        assertEquals(5, InkGutters.confirmed(page, suggested).size());
    }

    private static Bgr blank() {
        byte[] data = new byte[W * H * 3];
        Arrays.fill(data, (byte) 255);
        return new Bgr(data, W, H);
    }

    /** Words 40 px long with 5 px spaces, inked in the middle of a 20 px line at {@code y}. */
    private static void words(Bgr page, int from, int to, int y) {
        for (int x0 = from; x0 < to; x0 += 45) {
            for (int x = x0; x < Math.min(to, x0 + 40); x++) {
                for (int yy = y + 5; yy < y + 15; yy++) {
                    int i = (yy * W + x) * 3;
                    page.data()[i] = 0;
                    page.data()[i + 1] = 0;
                    page.data()[i + 2] = 0;
                }
            }
        }
    }

    private static TextLine box(String text, float left, float right, float y) {
        return new TextLine(text, 1f, 1f, new float[]{left, y, right, y, right, y + 20, left, y + 20}, null);
    }
}
