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
