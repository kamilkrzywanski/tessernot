package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;
import io.github.kamilkrzywanski.ocr.TextTable;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.rowBottom;
import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.rowTop;

/**
 * Tables with no rules, found from the boxes of the lines: rows that follow one another, each of
 * several boxes, whose boxes stand in the same columns with blank strips between them from the
 * first row to the last.
 * <p>
 * The rows are those of {@link ReadingOrder}, after it has decided what is a text column: the
 * lines of a newspaper page come here one to a row and never form a table. A table needs at
 * least {@link #MIN_ROWS} rows and {@link #MIN_COLUMNS} columns that are set like columns: at
 * least three of the cells of such a column, and at least half of them, begin at one x or end at
 * one x, to within half a line height. That is what tells a table from large print that the
 * detector cut into words: the gaps between words line up over three or four rows by chance, but
 * the words do not begin under one another. Two columns are not enough: a label beside its
 * value, or two short paragraphs side by side, look the same. A row whose box lies over a blank
 * strip, such as a total across the page, ends the table when fewer than three columns would be
 * left.
 * <p>
 * This reads nothing again and looks at no pixel: a cell is the text of the boxes that stand in
 * its column, left to right. Rows inside a ruled table are left to {@link Tables}.
 */
public final class UnruledTables {
    static final int MIN_ROWS = 3;
    static final int MIN_COLUMNS = 3;
    /** Rows of one table lie closer than this many median line heights, lower edge to upper edge. */
    static final float ROW_GAP = 1.5f;
    /** A column is set like one when this share of its cells, and at least {@link #MIN_ROWS} of them, share an edge. */
    static final float ALIGNED = 0.5f;
    /** Two edges are one when they are closer than this share of the median line height: about a letter. */
    static final float EDGE = 0.5f;

    private UnruledTables() {
    }

    /**
     * @param rows the page in reading order, each row left to right
     * @param ruled tables already found from rules; their rows are skipped
     */
    public static List<TextTable> find(List<List<TextLine>> rows, List<TextTable> ruled) {
        List<TextLine> all = LineBoxes.flatten(rows);
        if (all.size() < MIN_ROWS * MIN_COLUMNS) {
            return List.of();
        }
        float medianH = LineBoxes.medianHeight(all);
        List<TextTable> tables = new ArrayList<>();
        List<List<TextLine>> run = new ArrayList<>();
        for (List<TextLine> row : rows) {
            boolean cells = row.size() >= 2 && !inside(row, ruled);
            if (cells && !run.isEmpty() && rowTop(row) - rowBottom(run.get(run.size() - 1)) > ROW_GAP * medianH) {
                close(run, tables, medianH);
            }
            if (cells) {
                run.add(row);
            } else {
                close(run, tables, medianH);
            }
        }
        close(run, tables, medianH);
        return tables;
    }

    /** Splits a run of rows into tables, greedily from the top, and empties it. */
    private static void close(List<List<TextLine>> run, List<TextTable> tables, float medianH) {
        int from = 0;
        while (run.size() - from >= MIN_ROWS) {
            int to = from + 1;
            List<float[]> columns = columns(run.subList(from, to));
            // The first rows may have two cells; three columns have to show within MIN_ROWS rows.
            while (to < run.size()) {
                List<float[]> wider = columns(run.subList(from, to + 1));
                if (wider.size() < MIN_COLUMNS && (columns.size() >= MIN_COLUMNS || to - from >= MIN_ROWS)) {
                    break;
                }
                columns = wider;
                to++;
            }
            TextTable table = to - from >= MIN_ROWS ? table(run.subList(from, to), columns, medianH) : null;
            if (table != null) {
                tables.add(table);
                from = to;
            } else {
                from++;
            }
        }
        run.clear();
    }

    /**
     * The strips of x that the boxes of the rows cover, left to right; between two of them no box
     * lies. Each box is narrowed by its padding first, as for a column gutter.
     */
    private static List<float[]> columns(List<List<TextLine>> rows) {
        List<float[]> spans = new ArrayList<>();
        for (List<TextLine> row : rows) {
            for (TextLine line : row) {
                float pad = ColumnGutter.BOX_PAD * (line.bottom() - line.top());
                float left = line.left() + pad;
                float right = line.right() - pad;
                if (right > left) {
                    spans.add(new float[] {left, right});
                }
            }
        }
        spans.sort((a, b) -> Float.compare(a[0], b[0]));
        List<float[]> merged = new ArrayList<>();
        for (float[] span : spans) {
            float[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && span[0] <= last[1]) {
                last[1] = Math.max(last[1], span[1]);
            } else {
                merged.add(span.clone());
            }
        }
        return merged;
    }

    /** The cells of the rows in these columns, or null when fewer than three columns are set like columns. */
    private static TextTable table(List<List<TextLine>> rows, List<float[]> columns, float medianH) {
        if (columns.size() < MIN_COLUMNS) {
            return null;
        }
        int count = columns.size();
        // Left and right edge of every cell, by column.
        List<List<float[]>> edges = new ArrayList<>(count);
        for (int c = 0; c < count; c++) {
            edges.add(new ArrayList<>());
        }
        List<List<String>> cells = new ArrayList<>(rows.size());
        float left = Float.POSITIVE_INFINITY;
        float top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float bottom = Float.NEGATIVE_INFINITY;
        for (List<TextLine> row : rows) {
            String[] texts = new String[count];
            float[][] extent = new float[count][];
            List<TextLine> ordered = new ArrayList<>(row);
            ordered.sort((a, b) -> Float.compare(a.left(), b.left()));
            for (TextLine line : ordered) {
                float centre = (line.left() + line.right()) * 0.5f;
                int column = 0;
                while (column < count - 1 && centre > columns.get(column)[1]) {
                    column++;
                }
                texts[column] = texts[column] == null ? line.text() : texts[column] + " " + line.text();
                if (extent[column] == null) {
                    extent[column] = new float[] {line.left(), line.right()};
                } else {
                    extent[column][1] = Math.max(extent[column][1], line.right());
                }
                left = Math.min(left, line.left());
                top = Math.min(top, line.top());
                right = Math.max(right, line.right());
                bottom = Math.max(bottom, line.bottom());
            }
            List<String> out = new ArrayList<>(count);
            for (int c = 0; c < count; c++) {
                if (extent[c] != null) {
                    edges.get(c).add(extent[c]);
                }
                out.add(texts[c] == null ? "" : texts[c]);
            }
            cells.add(out);
        }
        int set = 0;
        for (List<float[]> column : edges) {
            int shared = Math.max(sharing(column, 0, EDGE * medianH), sharing(column, 1, EDGE * medianH));
            if (shared >= MIN_ROWS && shared >= ALIGNED * column.size()) {
                set++;
            }
        }
        if (set < MIN_COLUMNS) {
            return null;
        }
        Rectangle bounds = new Rectangle((int) Math.floor(left), (int) Math.floor(top),
                (int) Math.ceil(right - left), (int) Math.ceil(bottom - top));
        return new TextTable(cells, bounds, false);
    }

    /** The most cells whose edge {@code side} (0 left, 1 right) lies within {@code slack} of one cell's. */
    private static int sharing(List<float[]> cells, int side, float slack) {
        int most = 0;
        for (float[] anchor : cells) {
            int near = 0;
            for (float[] cell : cells) {
                if (Math.abs(cell[side] - anchor[side]) <= slack) {
                    near++;
                }
            }
            most = Math.max(most, near);
        }
        return most;
    }

    /** Every box of the row lies in one ruled table. */
    private static boolean inside(List<TextLine> row, List<TextTable> ruled) {
        for (TextTable table : ruled) {
            Rectangle bounds = table.bounds();
            boolean all = true;
            for (TextLine line : row) {
                float x = (line.left() + line.right()) * 0.5f;
                float y = (line.top() + line.bottom()) * 0.5f;
                all &= bounds.contains(x, y);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }
}
