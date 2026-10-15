package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reading order for printed lines.
 * A skewed page is turned level first: every box is rotated by the median angle of the long lines,
 * so a gutter that runs at a slant on a photo is vertical again.
 * A line joins the first row whose seed overlaps it by at least half the shorter height.
 * A vertical word stays out of that test, so a tall margin mark cannot glue the page into one row.
 * Text columns are found before blank rows split the page: a heading that sits at the same height
 * in both columns leaves a blank across the page, and cutting there would interleave the columns.
 * The gutter is the emptiest vertical strip of the block once each box is narrowed by its padding,
 * so columns whose boxes touch on a low-resolution scan still separate, and a title across the
 * columns only ends one column section. Each side is ordered again, so three or more columns split
 * one gutter at a time. Without text columns the block splits at blank rows, and each piece may
 * still split into two sides when both read like columns: lines that share a left edge and stand
 * alone on their rows. A form with scattered fields stays in rows. A right-aligned price stays on
 * the item's row. A grid of three or more short cells stays row by row.
 */
public final class ReadingOrder {
    /** Half the shorter box. */
    private static final float ROW_OVERLAP = 0.5f;
    /** Boxes sharing more than this share of the narrower width are stacked, not in one row. */
    private static final float STACKED = 0.5f;
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
     * A text column: this many lines, nearly all on one margin, alone on their rows, and spanning
     * most of the column width. A narrower gutter is accepted only between two such columns.
     */
    private static final int NARROW_LINES = 5;
    private static final float NARROW_ALIGN = 0.8f;
    private static final float NARROW_SINGLE = 0.8f;
    private static final float NARROW_FILL = 0.6f;
    /** Between text columns that no box crosses, lines may fill this little: a column of verse. */
    private static final float OPEN_FILL = 0.3f;
    /**
     * A side whose lines are narrower than this share of the lines beside it is a rail of list
     * numbers or bullets, not a column: "2." to "6." next to the items of the 1921 manual.
     */
    private static final float MARKER_SHARE = 0.25f;
    /** A side where at least this share of boxes are mostly digits is a column of values, not text. */
    private static final float VALUE_SHARE = 0.6f;
    /** Text columns of one page are set to similar widths; item names and prices are not. */
    private static final float WIDTH_SHARE = 0.5f;
    /** Boxes that may still cross the gutter, as a share of the block (a title, two merged lines). */
    private static final float CROSSING = 0.1f;
    /**
     * Text columns may have more: a title of several lines, a closing sentence and a signature
     * across the page. Each section between them must still hold two text columns.
     */
    private static final float TEXT_CROSSING = 0.35f;
    /**
     * A line this many median lines long that reaches past the gutter on one side by less than
     * {@link #CROSS_SIDE} of its width, and less than {@link #INTRUDE} median lines, picked up a
     * letter of the other column; it does not cross.
     */
    private static final float LONG_LINE = 4f;
    private static final float CROSS_SIDE = 0.1f;
    private static final float INTRUDE = 1.5f;
    /** Nested splits: columns inside a section inside a block. */
    private static final int MAX_DEPTH = 6;
    /** A page turned less than this, in radians (about 0.3 degrees), is read as it is. */
    private static final double MIN_SKEW = 0.005;
    /** More than this (about 20 degrees) is not skew, and the boxes are read as they are. */
    private static final double MAX_SKEW = 0.35;
    /** Only lines at least this many times longer than high give the skew angle. */
    private static final float SKEW_LINE = 3f;
    /**
     * A box across a text-column gutter is two merged lines when it is no taller than this many
     * median lines, starts on the left column's margin, and holds this many median lines of text
     * on each side of the gutter.
     */
    private static final float MERGED_HEIGHT = 1.5f;
    private static final float MERGED_SIDE = 2f;
    /** Boxes at least this share of the page wide may be merged lines when the gutter does not show. */
    private static final float WIDE_SHARE = 0.75f;

    private ReadingOrder() {
    }

    public static List<List<TextLine>> group(List<TextLine> lines) {
        return group(lines, true);
    }

    /**
     * With {@code columns} false, the page is read row by row, top to bottom: no columns, blocks or
     * sections. The skew is still taken out first, so a photo keeps its rows.
     */
    public static List<List<TextLine>> group(List<TextLine> lines, boolean columns) {
        if (lines.isEmpty()) {
            return new ArrayList<>();
        }
        Map<TextLine, TextLine> original = new IdentityHashMap<>();
        List<TextLine> level = level(lines, original);
        return restore(columns ? order(level, null) : rowsOnly(level), original);
    }

    private static List<List<TextLine>> rowsOnly(List<TextLine> level) {
        float medianH = medianHeight(level);
        List<TextLine> normal = new ArrayList<>();
        List<TextLine> vertical = new ArrayList<>();
        for (TextLine line : level) {
            (verticalWord(line, medianH) ? vertical : normal).add(line);
        }
        return mergeByTop(normal.isEmpty() ? new ArrayList<>() : seedRows(normal), vertical);
    }

    /**
     * Lines the detector merged across the gutter of two text columns, each with the fraction of its
     * length, from the left edge, where the gutter runs. Read as two lines, they let the columns
     * be read one after the other. A title across the columns, a line of a full-width paragraph and
     * a running head stay whole: they are taller, start off the column margin, or no column runs
     * right above or below them.
     */
    public static Map<TextLine, Float> cuts(List<TextLine> lines) {
        Map<TextLine, Float> out = new IdentityHashMap<>();
        if (lines.isEmpty()) {
            return out;
        }
        Map<TextLine, TextLine> original = new IdentityHashMap<>();
        List<TextLine> level = level(lines, original);
        Map<TextLine, Float> found = new IdentityHashMap<>();
        order(level, found);
        for (Map.Entry<TextLine, Float> cut : found.entrySet()) {
            float[] q = cut.getKey().quad();
            float from = (q[0] + q[6]) * 0.5f;
            float to = (q[2] + q[4]) * 0.5f;
            if (to - from > 1f) {
                float t = (cut.getValue() - from) / (to - from);
                out.put(original.getOrDefault(cut.getKey(), cut.getKey()), Math.max(0.05f, Math.min(0.95f, t)));
            }
        }
        return out;
    }

    /** Rows of level boxes. When {@code found} is not null, merged lines and their cut x are added to it. */
    private static List<List<TextLine>> order(List<TextLine> level, Map<TextLine, Float> found) {
        float medianH = medianHeight(level);
        List<TextLine> normal = new ArrayList<>();
        List<TextLine> vertical = new ArrayList<>();
        for (TextLine line : level) {
            if (verticalWord(line, medianH)) {
                vertical.add(line);
            } else {
                normal.add(line);
            }
        }
        vertical.sort(Comparator.comparingDouble(ReadingOrder::top).thenComparingDouble(ReadingOrder::left));
        return normal.isEmpty()
                ? singletonRows(vertical)
                : orderBlock(normal, vertical, medianH, 0, found);
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

    /**
     * Boxes rotated by the page's skew, each mapped to its source line in {@code original}.
     * A level page returns the lines themselves.
     */
    private static List<TextLine> level(List<TextLine> lines, Map<TextLine, TextLine> original) {
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
    private static double skew(List<TextLine> lines) {
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

    private static List<List<TextLine>> restore(List<List<TextLine>> rows, Map<TextLine, TextLine> original) {
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
            row.sort(Comparator.comparingDouble(ReadingOrder::left));
        }
        return rows;
    }

    /**
     * Text columns of the whole block first. Otherwise the block splits at blank rows, and each
     * piece is ordered again; a piece that does not split further reads as a zone.
     */
    private static List<List<TextLine>> orderBlock(List<TextLine> boxes, List<TextLine> vertical,
                                                   float medianH, int depth, Map<TextLine, Float> found) {
        List<List<TextLine>> rows = seedRows(boxes);
        if (found != null) {
            widelyMerged(boxes, medianH, found);
        }
        if (depth < MAX_DEPTH) {
            List<List<TextLine>> columns = textColumns(rows, vertical, medianH, depth, found);
            if (columns != null) {
                return columns;
            }
        }
        List<List<List<TextLine>>> zones = splitZones(rows, medianH);
        List<TextLine> loose = new ArrayList<>();
        List<List<TextLine>> owned = assignVertical(vertical, zones, loose);
        List<List<List<TextLine>>> pieces = new ArrayList<>();
        for (int i = 0; i < zones.size(); i++) {
            if (zones.size() == 1 || depth >= MAX_DEPTH) {
                pieces.add(orderZone(zones.get(i), owned.get(i), medianH));
            } else {
                pieces.add(orderBlock(flatten(zones.get(i)), owned.get(i), medianH, depth + 1, found));
            }
        }
        return stitch(pieces, loose);
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

    /**
     * Sections between rows that cross the gutter, read side by side where both sides are text
     * columns. Null when no section is. Each side is ordered again, so it may hold more columns.
     */
    private static List<List<TextLine>> textColumns(List<List<TextLine>> rows, List<TextLine> vertical,
                                                    float medianH, int depth, Map<TextLine, Float> found) {
        float[] gutter = gutter(rows, medianH, true, TEXT_CROSSING);
        if (gutter == null) {
            return null;
        }
        float mid = (gutter[0] + gutter[1]) * 0.5f;
        List<Section> sections = sections(rows, gutter, mid, medianH,
                (left, right) -> textSplit(left, right, rows, medianH, depth, true));
        boolean any = false;
        for (Section section : sections) {
            any |= section.split;
        }
        if (!any) {
            return null;
        }
        if (found != null) {
            mergedLines(sections, gutter, medianH, found);
        }
        List<TextLine> unplaced = place(vertical, sections, mid);
        List<List<TextLine>> out = new ArrayList<>();
        for (Section section : sections) {
            if (section.split) {
                out.addAll(orderBlock(flatten(section.left), section.leftExtras, medianH, depth + 1, found));
                out.addAll(orderBlock(flatten(section.right), section.rightExtras, medianH, depth + 1, found));
            } else if (section.crossing) {
                out.addAll(mergeByTop(section.rows, section.extras));
            } else {
                out.addAll(orderBlock(flatten(section.rows), section.extras, medianH, depth + 1, found));
            }
        }
        return mergeByTop(out, unplaced);
    }

    /**
     * Crossing rows that are two column lines read as one box. A candidate has the height of a text
     * line, starts on the left column's margin, runs well into both columns and ends inside the
     * right one. It counts when the columns go on right above or below it, directly or through
     * another such row.
     */
    private static void mergedLines(List<Section> sections, float[] gutter, float medianH, Map<TextLine, Float> found) {
        float margin = Float.POSITIVE_INFINITY;
        float end = Float.NEGATIVE_INFINITY;
        for (Section section : sections) {
            if (section.split) {
                margin = Math.min(margin, minLeft(flatten(section.left)));
                end = Math.max(end, maxRight(flatten(section.right)));
            }
        }
        int n = sections.size();
        TextLine[] merged = new TextLine[n];
        for (int i = 0; i < n; i++) {
            if (sections.get(i).crossing) {
                TextLine box = mergedBox(sections.get(i).rows.get(0), gutter, margin, medianH);
                merged[i] = box != null && right(box) <= end + ALIGN * medianH ? box : null;
            }
        }
        boolean[] accepted = new boolean[n];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (merged[i] != null && !accepted[i]
                        && (continues(sections, accepted, i, i - 1, medianH)
                        || continues(sections, accepted, i, i + 1, medianH))) {
                    accepted[i] = true;
                    changed = true;
                }
            }
        }
        float cut = (gutter[0] + gutter[1]) * 0.5f;
        for (int i = 0; i < n; i++) {
            if (accepted[i]) {
                found.put(merged[i], cut);
            }
        }
    }

    /** The only box of the row over the gutter, when it looks like two column lines in one. */
    private static TextLine mergedBox(List<TextLine> row, float[] gutter, float margin, float medianH) {
        TextLine merged = null;
        for (TextLine line : row) {
            if (reaches(line, gutter)) {
                if (merged != null) {
                    return null;
                }
                merged = line;
            }
        }
        return merged != null && mergedShape(merged, gutter, margin, medianH) ? merged : null;
    }

    /** The narrowed box reaches into the gutter. */
    private static boolean reaches(TextLine line, float[] gutter) {
        float pad = BOX_PAD * (bottom(line) - top(line));
        return left(line) + pad <= gutter[1] && right(line) - pad >= gutter[0];
    }

    /** A text line's height, starting on the left column's margin, with text well into both columns. */
    private static boolean mergedShape(TextLine line, float[] gutter, float margin, float medianH) {
        float height = bottom(line) - top(line);
        if (height > MERGED_HEIGHT * medianH) {
            return false;
        }
        float pad = BOX_PAD * height;
        return left(line) <= margin + ALIGN * medianH
                && left(line) + pad < gutter[0] - MERGED_SIDE * medianH
                && right(line) - pad > gutter[1] + MERGED_SIDE * medianH;
    }

    /**
     * When the detector merged so many lines that the gutter does not show, it is looked for among
     * the boxes narrower than {@link #WIDE_SHARE} of the block. If those form two text columns,
     * every box over the gutter with the shape of a merged line, ending inside the right column,
     * with a column line right above or below it is merged.
     */
    private static void widelyMerged(List<TextLine> boxes, float medianH, Map<TextLine, Float> found) {
        if (boxes.size() < 2 * NARROW_LINES) {
            return;
        }
        float span = maxRight(boxes) - minLeft(boxes);
        List<TextLine> narrow = new ArrayList<>();
        boolean anyWide = false;
        for (TextLine box : boxes) {
            if (right(box) - left(box) >= WIDE_SHARE * span) {
                anyWide = true;
            } else {
                narrow.add(box);
            }
        }
        if (!anyWide || narrow.size() < 2 * NARROW_LINES) {
            return;
        }
        List<List<TextLine>> rows = seedRows(narrow);
        float[] gutter = gutter(rows, medianH, true, CROSSING);
        if (gutter == null) {
            return;
        }
        float mid = (gutter[0] + gutter[1]) * 0.5f;
        List<TextLine> leftBoxes = new ArrayList<>();
        List<TextLine> rightBoxes = new ArrayList<>();
        for (TextLine box : narrow) {
            if (!reaches(box, gutter)) {
                ((left(box) + right(box)) * 0.5f < mid ? leftBoxes : rightBoxes).add(box);
            }
        }
        if (!textSplit(leftBoxes, rightBoxes, rows, medianH, 0, gutter[2] == 0f)) {
            return;
        }
        float margin = minLeft(leftBoxes);
        float end = maxRight(rightBoxes) + ALIGN * medianH;
        List<TextLine> candidates = new ArrayList<>();
        for (TextLine box : boxes) {
            if (!found.containsKey(box) && reaches(box, gutter) && mergedShape(box, gutter, margin, medianH)
                    && right(box) <= end) {
                candidates.add(box);
            }
        }
        // Merged lines often come in runs; one counts next to a column line or next to another that counted.
        List<TextLine> accepted = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = candidates.size() - 1; i >= 0; i--) {
                TextLine box = candidates.get(i);
                if (nextTo(box, leftBoxes, medianH) || nextTo(box, rightBoxes, medianH) || nextTo(box, accepted, medianH)) {
                    accepted.add(box);
                    candidates.remove(i);
                    changed = true;
                }
            }
        }
        for (TextLine box : accepted) {
            found.put(box, mid);
        }
    }

    /** Some line of the column sits right above or below the box, with no blank between them. */
    private static boolean nextTo(TextLine box, List<TextLine> column, float medianH) {
        for (TextLine line : column) {
            float blank = Math.max(top(line) - bottom(box), top(box) - bottom(line));
            if (blank <= ZONE_GAP * medianH && Math.abs(top(line) - top(box)) <= 2f * medianH) {
                return true;
            }
        }
        return false;
    }

    /** Section {@code j} is split into columns, or an accepted merged row, with no blank between it and {@code i}. */
    private static boolean continues(List<Section> sections, boolean[] accepted, int i, int j, float medianH) {
        if (j < 0 || j >= sections.size() || !sections.get(j).split && !accepted[j]) {
            return false;
        }
        Section a = sections.get(Math.min(i, j));
        Section b = sections.get(Math.max(i, j));
        return b.top() - a.bottom() <= ZONE_GAP * medianH;
    }

    private static List<List<TextLine>> orderZone(List<List<TextLine>> zone, List<TextLine> vertical, float medianH) {
        float[] gutter = gutter(zone, medianH, false, CROSSING);
        if (gutter == null) {
            return mergeByTop(zone, vertical);
        }
        float mid = (gutter[0] + gutter[1]) * 0.5f;
        List<Section> sections = sections(zone, gutter, mid, medianH,
                (left, right) -> columnsHold(left, right, zone, medianH));
        List<TextLine> unplaced = place(vertical, sections, mid);
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

    /** A row with a box over the gutter (a title across both columns) closes a section and stays whole. */
    private static List<Section> sections(List<List<TextLine>> rows, float[] gutter, float mid, float medianH,
                                          Splitter splitter) {
        List<Section> sections = new ArrayList<>();
        List<List<TextLine>> open = new ArrayList<>();
        for (List<TextLine> row : rows) {
            if (crosses(row, gutter, medianH)) {
                if (!open.isEmpty()) {
                    sections.add(Section.of(open, mid, splitter));
                    open = new ArrayList<>();
                }
                sections.add(Section.whole(row));
            } else {
                open.add(row);
            }
        }
        if (!open.isEmpty()) {
            sections.add(Section.of(open, mid, splitter));
        }
        return sections;
    }

    /** Vertical words go to the section they overlap most, on the side of their centre; the rest are returned. */
    private static List<TextLine> place(List<TextLine> vertical, List<Section> sections, float mid) {
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
        return unplaced;
    }

    /**
     * The x range (inclusive) of the emptiest vertical strip between at least two boxes on each side,
     * after each box loses its padding, and the number of boxes still over it. Null for a small block, a block that no strip separates, or
     * a grid unless {@code grid} allows one (text columns check their sides instead). At most
     * {@code crossing} of the boxes may still cross the strip.
     */
    private static float[] gutter(List<List<TextLine>> zone, float medianH, boolean grid, float crossing) {
        List<TextLine> boxes = new ArrayList<>();
        for (List<TextLine> row : zone) {
            boxes.addAll(row);
        }
        if (boxes.size() < 4 || !grid && medianCount(zone) >= GRID_ROW) {
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
        if (bestFrom < 0 || least > Math.max(1f, crossing * boxes.size())) {
            return null;
        }
        return new float[]{zoneLeft + bestFrom, zoneLeft + bestTo, least};
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

    /** A row crosses when one of its boxes does. */
    private static boolean crosses(List<TextLine> row, float[] gutter, float medianH) {
        for (TextLine line : row) {
            if (crossing(line, gutter, medianH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The narrowed box reaches into the gutter, as the coverage count saw it. A long line that
     * runs past one edge of the gutter only a little does not: it is a line of one column that
     * picked up the last letter of the other, where the gutter is narrow. A page number between
     * the columns still crosses.
     */
    private static boolean crossing(TextLine line, float[] gutter, float medianH) {
        float pad = BOX_PAD * (bottom(line) - top(line));
        float from = left(line) + pad;
        float to = right(line) - pad;
        if (from > gutter[1] || to < gutter[0]) {
            return false;
        }
        float width = to - from;
        return width < LONG_LINE * medianH
                || Math.min(gutter[0] - from, to - gutter[1]) >= Math.min(CROSS_SIDE * width, INTRUDE * medianH);
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
        if (valueRail(rightBoxes, leftBoxes, gap, medianH) || values(leftBoxes) || values(rightBoxes)) {
            return false;
        }
        if (gap >= MIN_GUTTER * medianH) {
            return formSide(leftBoxes, rows, medianH) && formSide(rightBoxes, rows, medianH);
        }
        return textSide(leftBoxes, rows, medianH) && textSide(rightBoxes, rows, medianH);
    }

    /**
     * Both sides are text columns of similar width, or split into such columns themselves.
     * Prices beside item names are a rail, not a column. When no box crosses between the sides
     * ({@code clean}), short lines are allowed: a column of verse fills a third of its width.
     */
    private static boolean textSplit(List<TextLine> leftBoxes, List<TextLine> rightBoxes,
                                     List<List<TextLine>> rows, float medianH, int depth, boolean clean) {
        if (leftBoxes.size() < NARROW_LINES || rightBoxes.size() < NARROW_LINES) {
            return false;
        }
        float leftWidth = medianWidth(leftBoxes);
        float rightWidth = medianWidth(rightBoxes);
        if (Math.min(leftWidth, rightWidth) < WIDTH_SHARE * Math.max(leftWidth, rightWidth)) {
            return false;
        }
        float gap = minLeft(rightBoxes) - maxRight(leftBoxes);
        if (valueRail(rightBoxes, leftBoxes, gap, medianH) || values(leftBoxes) || values(rightBoxes)) {
            return false;
        }
        float minFill = clean ? OPEN_FILL : NARROW_FILL;
        return columnLike(leftBoxes, rows, medianH, depth, minFill)
                && columnLike(rightBoxes, rows, medianH, depth, minFill);
    }

    /** One text column, or a side that splits again into text columns. */
    private static boolean columnLike(List<TextLine> side, List<List<TextLine>> rows, float medianH, int depth,
                                      float minFill) {
        if (textSide(side, rows, medianH, minFill)) {
            return true;
        }
        if (depth + 1 >= MAX_DEPTH || side.size() < 2 * NARROW_LINES) {
            return false;
        }
        if (splitsInto(side, rows, medianH, depth)) {
            return true;
        }
        // Lines merged over a narrow gutter hide it; the narrower boxes still show it.
        float span = maxRight(side) - minLeft(side);
        List<TextLine> narrow = new ArrayList<>();
        for (TextLine line : side) {
            if (right(line) - left(line) < WIDE_SHARE * span) {
                narrow.add(line);
            }
        }
        return narrow.size() < side.size() && narrow.size() >= 2 * NARROW_LINES
                && splitsInto(narrow, rows, medianH, depth);
    }

    /** The gutter of {@code boxes} splits them into two text columns. */
    private static boolean splitsInto(List<TextLine> boxes, List<List<TextLine>> rows, float medianH, int depth) {
        float[] gutter = gutter(seedRows(boxes), medianH, true, CROSSING);
        if (gutter == null) {
            return false;
        }
        float mid = (gutter[0] + gutter[1]) * 0.5f;
        List<TextLine> leftBoxes = new ArrayList<>();
        List<TextLine> rightBoxes = new ArrayList<>();
        for (TextLine line : boxes) {
            if ((left(line) + right(line)) * 0.5f < mid) {
                leftBoxes.add(line);
            } else {
                rightBoxes.add(line);
            }
        }
        return textSplit(leftBoxes, rightBoxes, rows, medianH, depth + 1, gutter[2] == 0f);
    }

    private static boolean formSide(List<TextLine> side, List<List<TextLine>> rows, float medianH) {
        return side.size() < FORM_LINES
                || alignedShare(side, medianH) >= FORM_ALIGN && singleShare(side, rows) >= FORM_SINGLE;
    }

    private static boolean textSide(List<TextLine> side, List<List<TextLine>> rows, float medianH) {
        return textSide(side, rows, medianH, NARROW_FILL);
    }

    private static boolean textSide(List<TextLine> side, List<List<TextLine>> rows, float medianH, float minFill) {
        return side.size() >= NARROW_LINES
                && marginShare(side, medianH) >= NARROW_ALIGN
                && singleLineShare(side, rows, medianH) >= NARROW_SINGLE
                && fill(side) >= minFill;
    }

    /**
     * Share of the rows touching this side that hold one line on it. Boxes closer than
     * {@link #MIN_GUTTER} are pieces of one line: a sharper detection splits "1)" or a wide word
     * gap off the rest of the line.
     */
    private static float singleLineShare(List<TextLine> side, List<List<TextLine>> rows, float medianH) {
        Set<TextLine> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(side);
        int touched = 0;
        int single = 0;
        for (List<TextLine> row : rows) {
            int pieces = 0;
            float end = Float.NEGATIVE_INFINITY;
            for (TextLine line : row) {
                if (set.contains(line)) {
                    if (left(line) - end > MIN_GUTTER * medianH) {
                        pieces++;
                    }
                    end = Math.max(end, right(line));
                }
            }
            if (pieces > 0) {
                touched++;
                if (pieces == 1) {
                    single++;
                }
            }
        }
        return touched == 0 ? 0f : single / (float) touched;
    }

    /**
     * Share of lines that start on one of the side's two most used left margins or end on its
     * right margin. A justified column has both sides, so an indented first line, a short last line
     * and a signature set to the right all count, and so does a second margin of hanging indents
     * or numbered paragraphs. A centred heading does not.
     */
    private static float marginShare(List<TextLine> side, float medianH) {
        float reach = ALIGN * medianH;
        float[] lefts = new float[side.size()];
        float[] rights = new float[side.size()];
        for (int i = 0; i < lefts.length; i++) {
            lefts[i] = left(side.get(i));
            rights[i] = right(side.get(i));
        }
        float leftMargin = densest(lefts, reach, Float.NaN);
        float secondMargin = densest(lefts, reach, leftMargin);
        float rightMargin = densest(rights, reach, Float.NaN);
        int on = 0;
        for (int i = 0; i < lefts.length; i++) {
            if (Math.abs(lefts[i] - leftMargin) <= reach
                    || Math.abs(lefts[i] - secondMargin) <= reach
                    || Math.abs(rights[i] - rightMargin) <= reach) {
                on++;
            }
        }
        return on / (float) lefts.length;
    }

    /**
     * The value with the most others within {@code reach} of it, leaving out values within reach
     * of {@code taken}. NaN when nothing is left.
     */
    private static float densest(float[] values, float reach, float taken) {
        float best = Float.NaN;
        int bestNear = 0;
        for (float a : values) {
            if (Math.abs(a - taken) <= reach) {
                continue;
            }
            int near = 0;
            for (float b : values) {
                if (Math.abs(a - b) <= reach && !(Math.abs(b - taken) <= reach)) {
                    near++;
                }
            }
            if (near > bestNear) {
                bestNear = near;
                best = a;
            }
        }
        return best;
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
     * Most boxes of the side are numbers (at least three digits, more digits than letters): amounts,
     * quantities, rates, dates. Such a side belongs to
     * the rows of the labels beside it, wherever its edges fall; a total printed larger than the
     * other amounts must not turn the totals of a receipt into two columns.
     */
    private static boolean values(List<TextLine> side) {
        int numeric = 0;
        for (TextLine line : side) {
            int digits = 0;
            int letters = 0;
            for (int i = 0; i < line.text().length(); i++) {
                char c = line.text().charAt(i);
                if (Character.isDigit(c)) {
                    digits++;
                } else if (Character.isLetter(c)) {
                    letters++;
                }
            }
            if (digits >= 3 && digits > letters) {
                numeric++;
            }
        }
        return numeric >= VALUE_SHARE * side.size();
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

    /**
     * Boxes of one row stand side by side. Two boxes that share more than {@link #STACKED} of the
     * narrower one's width are lines of one column, one above the other, even when a curved page
     * makes their boxes overlap in height.
     */
    private static boolean stacked(TextLine a, TextLine b) {
        float overlap = Math.min(right(a), right(b)) - Math.max(left(a), left(b));
        float narrower = Math.min(right(a) - left(a), right(b) - left(b));
        return narrower > 0f && overlap > STACKED * narrower;
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

    /** Decides whether the left and right boxes of a section are columns. */
    private interface Splitter {
        boolean split(List<TextLine> leftBoxes, List<TextLine> rightBoxes);
    }

    /** Rows between two crossing rows: either read as they are, or left column then right column. */
    private static final class Section {
        final List<List<TextLine>> rows;
        final boolean split;
        final boolean crossing;
        final List<List<TextLine>> left;
        final List<List<TextLine>> right;
        final List<TextLine> extras = new ArrayList<>();
        final List<TextLine> leftExtras = new ArrayList<>();
        final List<TextLine> rightExtras = new ArrayList<>();

        private Section(List<List<TextLine>> rows, boolean split, boolean crossing,
                        List<List<TextLine>> left, List<List<TextLine>> right) {
            this.rows = rows;
            this.split = split;
            this.crossing = crossing;
            this.left = left;
            this.right = right;
        }

        static Section whole(List<TextLine> row) {
            List<List<TextLine>> rows = new ArrayList<>();
            rows.add(row);
            return new Section(rows, false, true, List.of(), List.of());
        }

        static Section of(List<List<TextLine>> rows, float mid, Splitter splitter) {
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
            if (splitter.split(leftBoxes, rightBoxes)) {
                return new Section(rows, true, false, leftRows, rightRows);
            }
            return new Section(rows, false, false, List.of(), List.of());
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
