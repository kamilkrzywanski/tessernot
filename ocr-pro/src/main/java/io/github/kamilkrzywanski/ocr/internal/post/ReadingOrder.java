package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.medianHeight;

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
 * <p>
 * This class holds the entry points and the recursion over blocks, sections and columns. The
 * steps are in {@link PageSkew} (leveling), {@link LineRows} (rows and blank-row blocks),
 * {@link ColumnGutter} (the gutter), {@link ColumnSection} (sections between crossing rows),
 * {@link ColumnTests} (whether two sides are columns) and {@link MergedLines} (lines the detector
 * merged over a gutter); {@link LineBoxes} has the extents and medians they share.
 */
public final class ReadingOrder {
    /** Nested splits: columns inside a section inside a block. */
    static final int MAX_DEPTH = 6;

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
        List<TextLine> level = PageSkew.level(lines, original);
        return PageSkew.restore(columns ? order(level, null) : rowsOnly(level), original);
    }

    private static List<List<TextLine>> rowsOnly(List<TextLine> level) {
        float medianH = medianHeight(level);
        List<TextLine> normal = new ArrayList<>();
        List<TextLine> vertical = new ArrayList<>();
        for (TextLine line : level) {
            (LineRows.verticalWord(line, medianH) ? vertical : normal).add(line);
        }
        return LineRows.mergeByTop(normal.isEmpty() ? new ArrayList<>() : LineRows.seedRows(normal), vertical);
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
        List<TextLine> level = PageSkew.level(lines, original);
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
            if (LineRows.verticalWord(line, medianH)) {
                vertical.add(line);
            } else {
                normal.add(line);
            }
        }
        vertical.sort(Comparator.comparingDouble(TextLine::top).thenComparingDouble(TextLine::left));
        return normal.isEmpty()
                ? LineRows.singletonRows(vertical)
                : orderBlock(normal, vertical, medianH, 0, found);
    }

    /** Every line of the rows, in reading order. */
    public static List<TextLine> flatten(List<List<TextLine>> rows) {
        return LineBoxes.flatten(rows);
    }

    /** The page text: the lines of a row joined by a space, the rows by a line break. */
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

    /**
     * Text columns of the whole block first. Otherwise the block splits at blank rows, and each
     * piece is ordered again; a piece that does not split further reads as a zone.
     */
    private static List<List<TextLine>> orderBlock(List<TextLine> boxes, List<TextLine> vertical,
                                                   float medianH, int depth, Map<TextLine, Float> found) {
        List<List<TextLine>> rows = LineRows.seedRows(boxes);
        if (found != null) {
            MergedLines.widelyMerged(boxes, medianH, found);
        }
        if (depth < MAX_DEPTH) {
            List<List<TextLine>> columns = textColumns(rows, vertical, medianH, depth, found);
            if (columns != null) {
                return columns;
            }
        }
        List<List<List<TextLine>>> zones = LineRows.splitZones(rows, medianH);
        List<TextLine> loose = new ArrayList<>();
        List<List<TextLine>> owned = LineRows.assignVertical(vertical, zones, loose);
        List<List<List<TextLine>>> pieces = new ArrayList<>();
        for (int i = 0; i < zones.size(); i++) {
            if (zones.size() == 1 || depth >= MAX_DEPTH) {
                pieces.add(orderZone(zones.get(i), owned.get(i), medianH));
            } else {
                pieces.add(orderBlock(flatten(zones.get(i)), owned.get(i), medianH, depth + 1, found));
            }
        }
        return LineRows.stitch(pieces, loose);
    }

    /**
     * Sections between rows that cross the gutter, read side by side where both sides are text
     * columns. Null when no section is. Each side is ordered again, so it may hold more columns.
     */
    private static List<List<TextLine>> textColumns(List<List<TextLine>> rows, List<TextLine> vertical,
                                                    float medianH, int depth, Map<TextLine, Float> found) {
        ColumnGutter gutter = ColumnGutter.find(rows, medianH, true, ColumnGutter.TEXT_CROSSING);
        if (gutter == null) {
            return null;
        }
        float mid = gutter.mid();
        List<ColumnSection> sections = ColumnSection.sections(rows, gutter, medianH,
                (left, right) -> ColumnTests.textSplit(left, right, rows, medianH, depth, true));
        boolean any = false;
        for (ColumnSection section : sections) {
            any |= section.split;
        }
        if (!any) {
            return null;
        }
        if (found != null) {
            MergedLines.mergedLines(sections, gutter, medianH, found);
        }
        List<TextLine> unplaced = ColumnSection.place(vertical, sections, mid);
        List<List<TextLine>> out = new ArrayList<>();
        for (ColumnSection section : sections) {
            if (section.split) {
                out.addAll(orderBlock(flatten(section.left), section.leftExtras, medianH, depth + 1, found));
                out.addAll(orderBlock(flatten(section.right), section.rightExtras, medianH, depth + 1, found));
            } else if (section.crossing) {
                out.addAll(LineRows.mergeByTop(section.rows, section.extras));
            } else {
                out.addAll(orderBlock(flatten(section.rows), section.extras, medianH, depth + 1, found));
            }
        }
        return LineRows.mergeByTop(out, unplaced);
    }

    private static List<List<TextLine>> orderZone(List<List<TextLine>> zone, List<TextLine> vertical, float medianH) {
        ColumnGutter gutter = ColumnGutter.find(zone, medianH, false, ColumnGutter.CROSSING);
        if (gutter == null) {
            return LineRows.mergeByTop(zone, vertical);
        }
        float mid = gutter.mid();
        List<ColumnSection> sections = ColumnSection.sections(zone, gutter, medianH,
                (left, right) -> ColumnTests.columnsHold(left, right, zone, medianH));
        List<TextLine> unplaced = ColumnSection.place(vertical, sections, mid);
        List<List<TextLine>> out = new ArrayList<>();
        for (ColumnSection section : sections) {
            if (section.split) {
                out.addAll(LineRows.mergeByTop(section.left, section.leftExtras));
                out.addAll(LineRows.mergeByTop(section.right, section.rightExtras));
            } else {
                out.addAll(LineRows.mergeByTop(section.rows, section.extras));
            }
        }
        return LineRows.mergeByTop(out, unplaced);
    }
}
