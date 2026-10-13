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
 * Two text columns separated by a gutter are read left column, then right column. The gutter is
 * the emptiest vertical strip of the block once each box is narrowed by its padding, so columns
 * whose boxes touch on a low-resolution scan still separate, and a title across both columns
 * only ends one column section. A split is kept only when both sides read like columns: lines
 * that share a left edge and stand alone on their rows. A form with scattered fields stays in rows.
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
    /**
     * Detection boxes are padded by unclip. This fraction of a box height is taken off each side
     * before looking for a gutter. On the 691 px newspaper the padded columns touch at x = 343.
     */
    private static final float BOX_PAD = 0.25f;
    /** Left edges within this many median lines count as one margin; it covers a paragraph indent. */
    private static final float ALIGN = 1.5f;
    /**
     * With a gutter of at least {@link #MIN_GUTTER}, a side of this many lines or more must read like
     * a column: most left edges on one margin, most rows with one box on that side.
     * The fare block of the train ticket has 0.38 and 0.56; the invoice parties have 0.80 and 0.75.
     */
    private static final int FORM_LINES = 4;
    private static final float FORM_ALIGN = 0.6f;
    private static final float FORM_SINGLE = 0.65f;
    /**
     * A narrower gutter is accepted only between two full text columns: this many lines a side,
     * nearly all on one margin, alone on their rows, and spanning most of the column width.
     */
    private static final int NARROW_LINES = 5;
    private static final float NARROW_ALIGN = 0.8f;
    private static final float NARROW_SINGLE = 0.8f;
    private static final float NARROW_FILL = 0.6f;
    /**
     * A side whose lines are narrower than this share of the lines beside it is a rail of list
     * numbers or bullets, not a column: "2." to "6." next to the items of the 1921 manual.
     */
    private static final float MARKER_SHARE = 0.25f;
    /** Boxes that may still cross the gutter, as a share of the block (a title, two merged lines). */
    private static final float CROSSING = 0.1f;

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
        float[] gutter = gutter(zone, medianH);
        if (gutter == null) {
            return mergeByTop(zone, vertical);
        }
        float mid = (gutter[0] + gutter[1]) * 0.5f;
        // A row with a box over the gutter (a title across both columns) closes a section and stays whole.
        List<Section> sections = new ArrayList<>();
        List<List<TextLine>> open = new ArrayList<>();
        for (List<TextLine> row : zone) {
            if (crosses(row, gutter)) {
                if (!open.isEmpty()) {
                    sections.add(Section.of(open, mid, medianH));
                    open = new ArrayList<>();
                }
                sections.add(Section.whole(row));
            } else {
                open.add(row);
            }
        }
        if (!open.isEmpty()) {
            sections.add(Section.of(open, mid, medianH));
        }
        List<TextLine> unplaced = new ArrayList<>();
        for (TextLine word : vertical) {
            Section best = null;
            float bestOverlap = 0f;
            for (Section section : sections) {
                float overlap = Math.min(bottom(word), section.bottom()) - Math.max(top(word), section.top());
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = section;
                }
            }
            if (best == null) {
                unplaced.add(word);
            } else if (!best.split) {
                best.extras.add(word);
            } else if ((left(word) + right(word)) * 0.5f < mid) {
                best.leftExtras.add(word);
            } else {
                best.rightExtras.add(word);
            }
        }
        List<List<TextLine>> out = new ArrayList<>();
        for (Section section : sections) {
            if (section.split) {
                out.addAll(mergeByTop(section.left, section.leftExtras));
                out.addAll(mergeByTop(section.right, section.rightExtras));
            } else {
                out.addAll(mergeByTop(section.rows, section.extras));
            }
        }
        return mergeByTop(out, unplaced);
    }

    /**
     * The x range (inclusive) of the emptiest vertical strip between at least two boxes on each side,
     * after each box loses its padding. Null for a small block, a grid, or a block that no strip separates.
     */
    private static float[] gutter(List<List<TextLine>> zone, float medianH) {
        List<TextLine> boxes = new ArrayList<>();
        for (List<TextLine> row : zone) {
            boxes.addAll(row);
        }
        if (boxes.size() < 4 || medianCount(zone) >= GRID_ROW) {
            return null;
        }
        float zoneLeft = minLeft(boxes);
        int width = (int) Math.ceil(maxRight(boxes) - zoneLeft) + 2;
        int[] cover = new int[width + 1];
        float[] lefts = new float[boxes.size()];
        float[] rights = new float[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            TextLine box = boxes.get(i);
            float pad = BOX_PAD * (bottom(box) - top(box));
            lefts[i] = left(box) + pad - zoneLeft;
            rights[i] = right(box) - pad - zoneLeft;
            int from = Math.max(0, (int) Math.ceil(lefts[i]));
            int to = Math.min(width - 1, (int) Math.floor(rights[i]));
            if (from <= to) {
                cover[from]++;
                cover[to + 1]--;
            }
        }
        Arrays.sort(lefts);
        Arrays.sort(rights);
        int least = Integer.MAX_VALUE;
        int bestFrom = -1;
        int bestTo = -1;
        int runFrom = -1;
        int count = 0;
        for (int x = 0; x < width; x++) {
            count += cover[x];
            if (countBelow(rights, x) < 2 || countAbove(lefts, x) < 2) {
                runFrom = -1;
            } else if (count < least) {
                least = count;
                runFrom = x;
                bestFrom = x;
                bestTo = x;
            } else if (count == least) {
                if (runFrom < 0) {
                    runFrom = x;
                }
                if (x - runFrom > bestTo - bestFrom) {
                    bestFrom = runFrom;
                    bestTo = x;
                }
            } else {
                runFrom = -1;
            }
        }
        if (bestFrom < 0 || least > Math.max(1f, CROSSING * boxes.size())) {
            return null;
        }
        return new float[]{zoneLeft + bestFrom, zoneLeft + bestTo};
    }

    /** Values strictly below {@code x} in a sorted array. */
    private static int countBelow(float[] sorted, float x) {
        int i = Arrays.binarySearch(sorted, x);
        if (i < 0) {
            return -i - 1;
        }
        while (i > 0 && sorted[i - 1] == x) {
            i--;
        }
        return i;
    }

    /** Values strictly above {@code x} in a sorted array. */
    private static int countAbove(float[] sorted, float x) {
        int i = Arrays.binarySearch(sorted, x);
        if (i < 0) {
            return sorted.length - (-i - 1);
        }
        while (i < sorted.length - 1 && sorted[i + 1] == x) {
            i++;
        }
        return sorted.length - i - 1;
    }

    /** A box crosses when its narrowed extent reaches into the gutter, as the coverage count saw it. */
    private static boolean crosses(List<TextLine> row, float[] gutter) {
        for (TextLine line : row) {
            float pad = BOX_PAD * (bottom(line) - top(line));
            if (left(line) + pad <= gutter[1] && right(line) - pad >= gutter[0]) {
                return true;
            }
        }
        return false;
    }

    /** Whether the two sides of a section are columns to read one after the other. */
    private static boolean columnsHold(List<TextLine> leftBoxes, List<TextLine> rightBoxes,
                                       List<List<TextLine>> rows, float medianH) {
        if (leftBoxes.size() < 2 || rightBoxes.size() < 2) {
            return false;
        }
        float leftWidth = medianWidth(leftBoxes);
        float rightWidth = medianWidth(rightBoxes);
        if (Math.min(leftWidth, rightWidth) < MARKER_SHARE * Math.max(leftWidth, rightWidth)) {
            return false;
        }
        float gap = minLeft(rightBoxes) - maxRight(leftBoxes);
        if (valueRail(rightBoxes, leftBoxes, gap, medianH)) {
            return false;
        }
        if (gap >= MIN_GUTTER * medianH) {
            return formSide(leftBoxes, rows, medianH) && formSide(rightBoxes, rows, medianH);
        }
        return textSide(leftBoxes, rows, medianH) && textSide(rightBoxes, rows, medianH);
    }

    private static boolean formSide(List<TextLine> side, List<List<TextLine>> rows, float medianH) {
        return side.size() < FORM_LINES
                || alignedShare(side, medianH) >= FORM_ALIGN && singleShare(side, rows) >= FORM_SINGLE;
    }

    private static boolean textSide(List<TextLine> side, List<List<TextLine>> rows, float medianH) {
        return side.size() >= NARROW_LINES
                && alignedShare(side, medianH) >= NARROW_ALIGN
                && singleShare(side, rows) >= NARROW_SINGLE
                && fill(side) >= NARROW_FILL;
    }

    /** The largest share of lines whose left edges lie within {@link #ALIGN} median lines of one another. */
    private static float alignedShare(List<TextLine> side, float medianH) {
        int best = 0;
        for (TextLine a : side) {
            int near = 0;
            for (TextLine b : side) {
                if (Math.abs(left(a) - left(b)) <= ALIGN * medianH) {
                    near++;
                }
            }
            best = Math.max(best, near);
        }
        return best / (float) side.size();
    }

    /** Share of the rows touching this side that hold exactly one of its boxes. */
    private static float singleShare(List<TextLine> side, List<List<TextLine>> rows) {
        Set<TextLine> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(side);
        int touched = 0;
        int single = 0;
        for (List<TextLine> row : rows) {
            int count = 0;
            for (TextLine line : row) {
                if (set.contains(line)) {
                    count++;
                }
            }
            if (count > 0) {
                touched++;
                if (count == 1) {
                    single++;
                }
            }
        }
        return touched == 0 ? 0f : single / (float) touched;
    }

    private static float medianWidth(List<TextLine> lines) {
        float[] widths = new float[lines.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = right(lines.get(i)) - left(lines.get(i));
        }
        return median(widths);
    }

    /** Median line width as a share of the side's width. */
    private static float fill(List<TextLine> side) {
        float span = maxRight(side) - minLeft(side);
        if (span <= 0f) {
            return 0f;
        }
        float[] shares = new float[side.size()];
        for (int i = 0; i < shares.length; i++) {
            shares[i] = (right(side.get(i)) - left(side.get(i))) / span;
        }
        return median(shares);
    }

    /**
     * Prices on a receipt share a right edge, and the hole in front of them is wider than the price.
     * A text column is ragged on the right, or the gutter is the narrower of the two strips.
     * A justified column also shares a right edge, but its lines are as long as the lines beside it;
     * prices are short next to the item names.
     */
    private static boolean valueRail(List<TextLine> right, List<TextLine> leftSide, float gutter, float medianH) {
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
        float valueWidth = median(widths);
        boolean wideHole = gutter >= valueWidth;
        float[] names = new float[leftSide.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = right(leftSide.get(i)) - left(leftSide.get(i));
        }
        boolean shortValues = valueWidth < 0.5f * median(names);
        return aligned && (raggedLeft && shortValues || wideHole);
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

    /** Rows between two crossing rows: either read as they are, or left column then right column. */
    private static final class Section {
        final List<List<TextLine>> rows;
        final boolean split;
        final List<List<TextLine>> left;
        final List<List<TextLine>> right;
        final List<TextLine> extras = new ArrayList<>();
        final List<TextLine> leftExtras = new ArrayList<>();
        final List<TextLine> rightExtras = new ArrayList<>();

        private Section(List<List<TextLine>> rows, boolean split, List<List<TextLine>> left, List<List<TextLine>> right) {
            this.rows = rows;
            this.split = split;
            this.left = left;
            this.right = right;
        }

        static Section whole(List<TextLine> row) {
            List<List<TextLine>> rows = new ArrayList<>();
            rows.add(row);
            return new Section(rows, false, List.of(), List.of());
        }

        static Section of(List<List<TextLine>> rows, float mid, float medianH) {
            List<TextLine> leftBoxes = new ArrayList<>();
            List<TextLine> rightBoxes = new ArrayList<>();
            List<List<TextLine>> leftRows = new ArrayList<>();
            List<List<TextLine>> rightRows = new ArrayList<>();
            for (List<TextLine> row : rows) {
                List<TextLine> onLeft = new ArrayList<>();
                List<TextLine> onRight = new ArrayList<>();
                for (TextLine line : row) {
                    if ((left(line) + right(line)) * 0.5f < mid) {
                        onLeft.add(line);
                    } else {
                        onRight.add(line);
                    }
                }
                leftBoxes.addAll(onLeft);
                rightBoxes.addAll(onRight);
                if (!onLeft.isEmpty()) {
                    leftRows.add(onLeft);
                }
                if (!onRight.isEmpty()) {
                    rightRows.add(onRight);
                }
            }
            if (columnsHold(leftBoxes, rightBoxes, rows, medianH)) {
                return new Section(rows, true, leftRows, rightRows);
            }
            return new Section(rows, false, List.of(), List.of());
        }

        float top() {
            float y = Float.POSITIVE_INFINITY;
            for (List<TextLine> row : rows) {
                y = Math.min(y, rowTop(row));
            }
            return y;
        }

        float bottom() {
            float y = Float.NEGATIVE_INFINITY;
            for (List<TextLine> row : rows) {
                y = Math.max(y, rowBottom(row));
            }
            return y;
        }
    }
}
