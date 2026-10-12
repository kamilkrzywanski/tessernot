package org.krzywanski.ocr.post;

import org.krzywanski.ocr.TextLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Groups boxes into rows when the vertical overlap is at least half the shorter box,
 * compared with any box already in that row. Rows are then sorted left to right.
 * A multi-column page can still interleave columns that share a row.
 */
public final class ReadingOrder {
    private ReadingOrder() {
    }

    public static List<List<TextLine>> group(List<TextLine> lines) {
        List<TextLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(ReadingOrder::top).thenComparingDouble(ReadingOrder::left));
        List<List<TextLine>> rows = new ArrayList<>();
        for (TextLine line : sorted) {
            List<TextLine> dest = null;
            for (List<TextLine> row : rows) {
                for (TextLine have : row) {
                    if (overlapRatio(line, have) >= 0.5f) {
                        dest = row;
                        break;
                    }
                }
                if (dest != null) {
                    break;
                }
            }
            if (dest == null) {
                dest = new ArrayList<>();
                rows.add(dest);
            }
            dest.add(line);
        }
        for (List<TextLine> row : rows) {
            row.sort(Comparator.comparingDouble(ReadingOrder::left));
        }
        return rows;
    }

    public static List<TextLine> flatten(List<List<TextLine>> rows) {
        List<TextLine> out = new ArrayList<>();
        for (List<TextLine> row : rows) {
            out.addAll(row);
        }
        return out;
    }

    public static String join(List<List<TextLine>> rows) {
        List<String> lines = new ArrayList<>(rows.size());
        for (List<TextLine> row : rows) {
            List<String> parts = new ArrayList<>(row.size());
            for (TextLine line : row) {
                parts.add(line.text());
            }
            lines.add(String.join(" ", parts));
        }
        return String.join("\n", lines);
    }

    static float top(TextLine line) {
        float y = line.quad()[1];
        y = Math.min(y, line.quad()[3]);
        y = Math.min(y, line.quad()[5]);
        return Math.min(y, line.quad()[7]);
    }

    static float bottom(TextLine line) {
        float y = line.quad()[1];
        y = Math.max(y, line.quad()[3]);
        y = Math.max(y, line.quad()[5]);
        return Math.max(y, line.quad()[7]);
    }

    static float left(TextLine line) {
        float x = line.quad()[0];
        x = Math.min(x, line.quad()[2]);
        x = Math.min(x, line.quad()[4]);
        return Math.min(x, line.quad()[6]);
    }

    private static float overlapRatio(TextLine a, TextLine b) {
        float overlap = Math.min(bottom(a), bottom(b)) - Math.max(top(a), top(b));
        if (overlap <= 0f) {
            return 0f;
        }
        float minHeight = Math.min(bottom(a) - top(a), bottom(b) - top(b));
        if (minHeight <= 1e-3f) {
            return 0f;
        }
        return overlap / minHeight;
    }
}
