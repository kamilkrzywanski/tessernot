package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Takes the skew out of a page before its lines are ordered: every box is rotated by the median
 * angle of the long lines, so a gutter that runs at a slant on a photo is vertical again. The
 * order is then mapped back to the lines as they were detected.
 */
final class PageSkew {
    /** A page turned less than this, in radians (about 0.3 degrees), is read as it is. */
    static final double MIN_SKEW = 0.005;
    /** More than this (about 20 degrees) is not skew, and the boxes are read as they are. */
    static final double MAX_SKEW = 0.35;
    /** Only lines at least this many times longer than high give the skew angle. */
    static final float SKEW_LINE = 3f;

    private PageSkew() {
    }

    /**
     * Boxes rotated by the page's skew, each mapped to its source line in {@code original}.
     * A level page returns the lines themselves.
     */
    static List<TextLine> level(List<TextLine> lines, Map<TextLine, TextLine> original) {
        double angle = skew(lines);
        if (Math.abs(angle) < MIN_SKEW || Math.abs(angle) > MAX_SKEW) {
            return lines;
        }
        double cos = Math.cos(-angle);
        double sin = Math.sin(-angle);
        List<TextLine> out = new ArrayList<>(lines.size());
        for (TextLine line : lines) {
            float[] q = line.quad();
            float[] turned = new float[8];
            for (int i = 0; i < 8; i += 2) {
                turned[i] = (float) (q[i] * cos - q[i + 1] * sin);
                turned[i + 1] = (float) (q[i] * sin + q[i + 1] * cos);
            }
            TextLine copy = new TextLine(line.text(), line.score(), line.detScore(), turned, line.bounds());
            original.put(copy, line);
            out.add(copy);
        }
        return out;
    }

    /** Median angle of the long edges of long lines, in radians; 0 when no line is long enough. */
    static double skew(List<TextLine> lines) {
        double[] angles = new double[lines.size()];
        int count = 0;
        for (TextLine line : lines) {
            float[] q = line.quad();
            float dx = (q[2] - q[0] + q[4] - q[6]) * 0.5f;
            float dy = (q[3] - q[1] + q[5] - q[7]) * 0.5f;
            float h = (float) Math.hypot((q[6] - q[0] + q[4] - q[2]) * 0.5f, (q[7] - q[1] + q[5] - q[3]) * 0.5f);
            if (dx > 0f && dx >= SKEW_LINE * h) {
                angles[count++] = Math.atan2(dy, dx);
            }
        }
        if (count == 0) {
            return 0.0;
        }
        Arrays.sort(angles, 0, count);
        int mid = count / 2;
        return (count & 1) == 1 ? angles[mid] : (angles[mid - 1] + angles[mid]) * 0.5;
    }

    static List<List<TextLine>> restore(List<List<TextLine>> rows, Map<TextLine, TextLine> original) {
        if (original.isEmpty()) {
            return rows;
        }
        List<List<TextLine>> out = new ArrayList<>(rows.size());
        for (List<TextLine> row : rows) {
            List<TextLine> back = new ArrayList<>(row.size());
            for (TextLine line : row) {
                back.add(original.getOrDefault(line, line));
            }
            out.add(back);
        }
        return out;
    }
}
