package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Reading order for printed lines.
 * A line joins the first row whose seed overlaps it by at least half the shorter height.
 * A vertical word stays out of that test, so a tall margin mark cannot glue the page into one row.
 * Two text columns separated by a gutter are read left column, then right column.
 * A right-aligned price stays on the item's row. A grid of three or more columns stays row by row.
 */
public final class ReadingOrder {
    /** Half the shorter box. */
    private static final float ROW_OVERLAP = 0.5f;
    /**
     * Taller than this multiple of its own width, and of the page's median line.
     * The margin word on the invoice is several times a text line; a narrow digit is not.
     */
    private static final float VERTICAL_RATIO = 1.5f;
    /**
     * Blank larger than this fraction of the median line starts a new block.
     * Header lines on the invoice leave a gap near 0.2 and stay one block.
     * The blank above the title is about 0.7, and the Nixon signature clears the address by about 0.4.
     */
    private static final float ZONE_GAP = 0.35f;
    /** Narrower than this fraction of the median line is word spacing, not a column gutter. */
    private static final float MIN_GUTTER = 0.8f;
    /** Right edges this close, in fractions of the median line, are a price rail. */
    private static final float RAIL_EDGE = 0.8f;
    /** A row mix at or above this many boxes is a grid and stays left to right. */
    private static final float GRID_ROW = 3f;

    private ReadingOrder() {
    }

    public static List<List<TextLine>> group(List<TextLine> lines) {
        if (lines.isEmpty()) {
            return new ArrayList<>();
        }
        float medianH = medianHeight(lines);
        List<TextLine> normal = new ArrayList<>();
        List<TextLine> vertical = new ArrayList<>();
        for (TextLine line : lines) {
            if (verticalWord(line, medianH)) {
                vertical.add(line);
            } else {
                normal.add(line);
            }
        }
        vertical.sort(Comparator.comparingDouble(ReadingOrder::top).thenComparingDouble(ReadingOrder::left));
        if (normal.isEmpty()) {
            return singletonRows(vertical);
        }
        List<List<TextLine>> rows = seedRows(normal);
        List<List<List<TextLine>>> zones = splitZones(rows, medianH);
        List<TextLine> loose = new ArrayList<>();
        List<List<TextLine>> owned = assignVertical(vertical, zones, loose);
        List<List<List<TextLine>>> pieces = new ArrayList<>();
        for (int i = 0; i < zones.size(); i++) {
            pieces.add(orderZone(zones.get(i), owned.get(i), medianH));
        }
        return stitch(pieces, loose);
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

    static float right(TextLine line) {
        float x = line.quad()[0];
        x = Math.max(x, line.quad()[2]);
        x = Math.max(x, line.quad()[4]);
        return Math.max(x, line.quad()[6]);
    }

    private static boolean verticalWord(TextLine line, float medianH) {
        float h = bottom(line) - top(line);
        float w = right(line) - left(line);
        return h >= VERTICAL_RATIO * w && h >= VERTICAL_RATIO * medianH;
    }

    private static List<List<TextLine>> seedRows(List<TextLine> lines) {
        List<TextLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(ReadingOrder::top).thenComparingDouble(ReadingOrder::left));
        List<List<TextLine>> rows = new ArrayList<>();
        List<TextLine> seeds = new ArrayList<>();
        for (TextLine line : sorted) {
            int dest = -1;
            for (int i = 0; i < seeds.size(); i++) {
                if (overlapRatio(line, seeds.get(i)) >= ROW_OVERLAP) {
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
            row.sort(Comparator.comparingDouble(ReadingOrder::left));
        }
        return rows;
    }

    private static List<List<List<TextLine>>> splitZones(List<List<TextLine>> rows, float medianH) {
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
    private static List<List<TextLine>> assignVertical(
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

    private static List<List<TextLine>> orderZone(List<List<TextLine>> zone, List<TextLine> vertical, float medianH) {
        ColumnLayout layout = columns(zone, medianH);
        if (!layout.split) {
            return mergeByTop(layout.left, vertical);
        }
        List<TextLine> onLeft = new ArrayList<>();
        List<TextLine> onRight = new ArrayList<>();
        for (TextLine word : vertical) {
            float midX = (left(word) + right(word)) * 0.5f;
            if (midX < layout.gutterMid) {
                onLeft.add(word);
            } else {
                onRight.add(word);
            }
        }
        List<List<TextLine>> out = mergeByTop(layout.left, onLeft);
        out.addAll(mergeByTop(layout.right, onRight));
        return out;
    }

    private static ColumnLayout columns(List<List<TextLine>> zone, float medianH) {
        List<TextLine> boxes = new ArrayList<>();
        for (List<TextLine> row : zone) {
            boxes.addAll(row);
        }
        if (boxes.size() < 4 || medianCount(zone) >= GRID_ROW) {
            return ColumnLayout.rows(zone);
        }
        List<List<TextLine>> groups = xClusters(boxes);
        if (groups.size() != 2) {
            return ColumnLayout.rows(zone);
        }
        List<TextLine> leftBoxes = groups.get(0);
        List<TextLine> rightBoxes = groups.get(1);
        if (leftBoxes.size() < 2 || rightBoxes.size() < 2) {
            return ColumnLayout.rows(zone);
        }
        float leftEdge = minLeft(rightBoxes);
        float rightEdge = maxRight(leftBoxes);
        float gutter = leftEdge - rightEdge;
        if (gutter < MIN_GUTTER * medianH || valueRail(rightBoxes, gutter, medianH)) {
            return ColumnLayout.rows(zone);
        }
        Set<TextLine> leftSet = Collections.newSetFromMap(new IdentityHashMap<>());
        leftSet.addAll(leftBoxes);
        List<List<TextLine>> leftRows = new ArrayList<>();
        List<List<TextLine>> rightRows = new ArrayList<>();
        for (List<TextLine> row : zone) {
            List<TextLine> onLeft = new ArrayList<>();
            List<TextLine> onRight = new ArrayList<>();
            for (TextLine line : row) {
                if (leftSet.contains(line)) {
                    onLeft.add(line);
                } else {
                    onRight.add(line);
                }
            }
            if (!onLeft.isEmpty()) {
                leftRows.add(onLeft);
            }
            if (!onRight.isEmpty()) {
                rightRows.add(onRight);
            }
        }
        return new ColumnLayout(leftRows, rightRows, (rightEdge + leftEdge) * 0.5f, true);
    }

    /**
     * Prices on a receipt share a right edge, and the hole in front of them is wider than the price.
     * A text column is ragged on the right, or the gutter is the narrower of the two strips.
     */
    private static boolean valueRail(List<TextLine> right, float gutter, float medianH) {
        float minRight = Float.POSITIVE_INFINITY;
        float maxRight = Float.NEGATIVE_INFINITY;
        float minLeft = Float.POSITIVE_INFINITY;
        float maxLeft = Float.NEGATIVE_INFINITY;
        float[] widths = new float[right.size()];
        for (int i = 0; i < right.size(); i++) {
            TextLine line = right.get(i);
            minRight = Math.min(minRight, right(line));
            maxRight = Math.max(maxRight, right(line));
            minLeft = Math.min(minLeft, left(line));
            maxLeft = Math.max(maxLeft, left(line));
            widths[i] = right(line) - left(line);
        }
        float rightRange = maxRight - minRight;
        boolean aligned = rightRange <= RAIL_EDGE * medianH;
        boolean raggedLeft = maxLeft - minLeft > rightRange + 0.5f * medianH;
        boolean wideHole = gutter >= median(widths);
        return aligned && (raggedLeft || wideHole);
    }

    private static List<List<TextLine>> xClusters(List<TextLine> boxes) {
        List<TextLine> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingDouble(ReadingOrder::left).thenComparingDouble(ReadingOrder::right));
        List<List<TextLine>> groups = new ArrayList<>();
        List<TextLine> current = new ArrayList<>();
        float edge = Float.NEGATIVE_INFINITY;
        for (TextLine line : sorted) {
            if (current.isEmpty() || left(line) > edge + 1f) {
                current = new ArrayList<>();
                groups.add(current);
                edge = right(line);
            } else {
                edge = Math.max(edge, right(line));
            }
            current.add(line);
        }
        return groups;
    }

    private static List<List<TextLine>> mergeByTop(List<List<TextLine>> rows, List<TextLine> extras) {
        if (extras.isEmpty()) {
            return rows;
        }
        List<TextLine> sorted = new ArrayList<>(extras);
        sorted.sort(Comparator.comparingDouble(ReadingOrder::top).thenComparingDouble(ReadingOrder::left));
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

    private static boolean rowBefore(List<TextLine> row, TextLine line) {
        int byTop = Float.compare(rowTop(row), top(line));
        if (byTop != 0) {
            return byTop < 0;
        }
        return Float.compare(rowLeft(row), left(line)) <= 0;
    }

    private static List<List<TextLine>> stitch(List<List<List<TextLine>>> pieces, List<TextLine> loose) {
        List<List<TextLine>> out = new ArrayList<>();
        int p = 0;
        for (List<List<TextLine>> piece : pieces) {
            float start = rowTop(piece.get(0));
            while (p < loose.size() && top(loose.get(p)) < start) {
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

    private static List<List<TextLine>> singletonRows(List<TextLine> lines) {
        List<List<TextLine>> rows = new ArrayList<>();
        for (TextLine line : lines) {
            rows.add(single(line));
        }
        return rows;
    }

    private static List<TextLine> single(TextLine line) {
        List<TextLine> row = new ArrayList<>();
        row.add(line);
        return row;
    }

    private static float verticalOverlap(TextLine word, List<List<TextLine>> zone) {
        float zoneTop = rowTop(zone.get(0));
        float zoneBottom = zoneTop;
        for (List<TextLine> row : zone) {
            zoneBottom = Math.max(zoneBottom, rowBottom(row));
        }
        return Math.min(bottom(word), zoneBottom) - Math.max(top(word), zoneTop);
    }

    private static float medianHeight(List<TextLine> lines) {
        float[] heights = new float[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            heights[i] = bottom(lines.get(i)) - top(lines.get(i));
        }
        return median(heights);
    }

    private static float medianCount(List<List<TextLine>> rows) {
        float[] counts = new float[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            counts[i] = rows.get(i).size();
        }
        return median(counts);
    }

    private static float median(float[] values) {
        Arrays.sort(values);
        int mid = values.length / 2;
        if ((values.length & 1) == 1) {
            return values[mid];
        }
        return (values[mid - 1] + values[mid]) * 0.5f;
    }

    private static float rowTop(List<TextLine> row) {
        float y = top(row.get(0));
        for (int i = 1; i < row.size(); i++) {
            y = Math.min(y, top(row.get(i)));
        }
        return y;
    }

    private static float rowBottom(List<TextLine> row) {
        float y = bottom(row.get(0));
        for (int i = 1; i < row.size(); i++) {
            y = Math.max(y, bottom(row.get(i)));
        }
        return y;
    }

    private static float rowLeft(List<TextLine> row) {
        float x = left(row.get(0));
        for (int i = 1; i < row.size(); i++) {
            x = Math.min(x, left(row.get(i)));
        }
        return x;
    }

    private static float minLeft(List<TextLine> lines) {
        float x = left(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            x = Math.min(x, left(lines.get(i)));
        }
        return x;
    }

    private static float maxRight(List<TextLine> lines) {
        float x = right(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            x = Math.max(x, right(lines.get(i)));
        }
        return x;
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

    private record ColumnLayout(List<List<TextLine>> left, List<List<TextLine>> right, float gutterMid, boolean split) {
        static ColumnLayout rows(List<List<TextLine>> rows) {
            return new ColumnLayout(rows, List.of(), 0f, false);
        }
    }
}
