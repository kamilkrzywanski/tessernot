package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.rowBottom;
import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.rowLeft;
import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.rowTop;

/**
 * Lines into rows, rows into blocks, and the pieces back into one list of rows.
 * A line joins the first row whose seed overlaps it by at least half the shorter height.
 * A vertical word stays out of that test, so a tall margin mark cannot glue the page into one row.
 */
final class LineRows {
    /** Half the shorter box. */
    static final float ROW_OVERLAP = 0.5f;
    /** Boxes sharing more than this share of the narrower width are stacked, not in one row. */
    static final float STACKED = 0.5f;
    /**
     * Taller than this multiple of its own width, and of the page's median line.
     * The margin word on the invoice is several times a text line; a narrow digit is not.
     */
    static final float VERTICAL_RATIO = 1.5f;
    /**
     * Blank larger than this fraction of the median line starts a new block.
     * Header lines on the invoice leave a gap near 0.2 and stay one block.
     * The blank above the title is about 0.7, and the Nixon signature clears the address by about 0.4.
     */
    static final float ZONE_GAP = 0.35f;

    private LineRows() {
    }

    static boolean verticalWord(TextLine line, float medianH) {
        float h = line.bottom() - line.top();
        float w = line.right() - line.left();
        return h >= VERTICAL_RATIO * w && h >= VERTICAL_RATIO * medianH;
    }

    static List<List<TextLine>> seedRows(List<TextLine> lines) {
        List<TextLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(TextLine::top).thenComparingDouble(TextLine::left));
        List<List<TextLine>> rows = new ArrayList<>();
        List<TextLine> seeds = new ArrayList<>();
        for (TextLine line : sorted) {
            int dest = -1;
            for (int i = 0; i < seeds.size(); i++) {
                if (overlapRatio(line, seeds.get(i)) >= ROW_OVERLAP && !stacked(line, seeds.get(i))) {
                    dest = i;
                    break;
                }
            }
            if (dest < 0) {
                List<TextLine> row = new ArrayList<>();
                row.add(line);
                rows.add(row);
                seeds.add(line);
            } else {
                rows.get(dest).add(line);
            }
        }
        for (List<TextLine> row : rows) {
            row.sort(Comparator.comparingDouble(TextLine::left));
        }
        return rows;
    }

    static List<List<List<TextLine>>> splitZones(List<List<TextLine>> rows, float medianH) {
        List<List<List<TextLine>>> zones = new ArrayList<>();
        List<List<TextLine>> zone = new ArrayList<>();
        zones.add(zone);
        zone.add(rows.get(0));
        float bottom = rowBottom(rows.get(0));
        float limit = ZONE_GAP * medianH;
        for (int i = 1; i < rows.size(); i++) {
            List<TextLine> row = rows.get(i);
            if (rowTop(row) - bottom > limit) {
                zone = new ArrayList<>();
                zones.add(zone);
                bottom = rowBottom(row);
            } else {
                bottom = Math.max(bottom, rowBottom(row));
            }
            zone.add(row);
        }
        return zones;
    }

    /**
     * Vertical words that overlap a block are returned per block.
     * The rest, which sit in the margin between blocks, are appended to {@code loose} in top-to-bottom order.
     */
    static List<List<TextLine>> assignVertical(
            List<TextLine> vertical,
            List<List<List<TextLine>>> zones,
            List<TextLine> loose) {
        List<List<TextLine>> owned = new ArrayList<>();
        for (int i = 0; i < zones.size(); i++) {
            owned.add(new ArrayList<>());
        }
        boolean[] used = new boolean[vertical.size()];
        for (int v = 0; v < vertical.size(); v++) {
            TextLine word = vertical.get(v);
            int best = -1;
            float bestOverlap = 0f;
            for (int z = 0; z < zones.size(); z++) {
                float overlap = verticalOverlap(word, zones.get(z));
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = z;
                }
            }
            if (best >= 0) {
                owned.get(best).add(word);
                used[v] = true;
            }
        }
        for (int v = 0; v < vertical.size(); v++) {
            if (!used[v]) {
                loose.add(vertical.get(v));
            }
        }
        return owned;
    }

    static List<List<TextLine>> mergeByTop(List<List<TextLine>> rows, List<TextLine> extras) {
        if (extras.isEmpty()) {
            return rows;
        }
        List<TextLine> sorted = new ArrayList<>(extras);
        sorted.sort(Comparator.comparingDouble(TextLine::top).thenComparingDouble(TextLine::left));
        List<List<TextLine>> out = new ArrayList<>(rows.size() + sorted.size());
        int i = 0;
        int j = 0;
        while (i < rows.size() || j < sorted.size()) {
            if (j >= sorted.size()) {
                out.add(rows.get(i++));
            } else if (i >= rows.size()) {
                out.add(single(sorted.get(j++)));
            } else if (rowBefore(rows.get(i), sorted.get(j))) {
                out.add(rows.get(i++));
            } else {
                out.add(single(sorted.get(j++)));
            }
        }
        return out;
    }

    static boolean rowBefore(List<TextLine> row, TextLine line) {
        int byTop = Float.compare(rowTop(row), line.top());
        if (byTop != 0) {
            return byTop < 0;
        }
        return Float.compare(rowLeft(row), line.left()) <= 0;
    }

    static List<List<TextLine>> stitch(List<List<List<TextLine>>> pieces, List<TextLine> loose) {
        List<List<TextLine>> out = new ArrayList<>();
        int p = 0;
        for (List<List<TextLine>> piece : pieces) {
            float start = rowTop(piece.get(0));
            while (p < loose.size() && loose.get(p).top() < start) {
                out.add(single(loose.get(p)));
                p++;
            }
            out.addAll(piece);
        }
        while (p < loose.size()) {
            out.add(single(loose.get(p)));
            p++;
        }
        return out;
    }

    static List<List<TextLine>> singletonRows(List<TextLine> lines) {
        List<List<TextLine>> rows = new ArrayList<>();
        for (TextLine line : lines) {
            rows.add(single(line));
        }
        return rows;
    }

    static List<TextLine> single(TextLine line) {
        List<TextLine> row = new ArrayList<>();
        row.add(line);
        return row;
    }

    static float verticalOverlap(TextLine word, List<List<TextLine>> zone) {
        float zoneTop = rowTop(zone.get(0));
        float zoneBottom = zoneTop;
        for (List<TextLine> row : zone) {
            zoneBottom = Math.max(zoneBottom, rowBottom(row));
        }
        return Math.min(word.bottom(), zoneBottom) - Math.max(word.top(), zoneTop);
    }

    /**
     * Boxes of one row stand side by side. Two boxes that share more than {@link #STACKED} of the
     * narrower one's width are lines of one column, one above the other, even when a curved page
     * makes their boxes overlap in height.
     */
    static boolean stacked(TextLine a, TextLine b) {
        float overlap = Math.min(a.right(), b.right()) - Math.max(a.left(), b.left());
        float narrower = Math.min(a.right() - a.left(), b.right() - b.left());
        return narrower > 0f && overlap > STACKED * narrower;
    }

    static float overlapRatio(TextLine a, TextLine b) {
        float overlap = Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top());
        if (overlap <= 0f) {
            return 0f;
        }
        float minHeight = Math.min(a.bottom() - a.top(), b.bottom() - b.top());
        if (minHeight <= 1e-3f) {
            return 0f;
        }
        return overlap / minHeight;
    }
}
