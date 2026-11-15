package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;

import java.util.ArrayList;
import java.util.List;

import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.rowBottom;
import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.rowTop;

/**
 * Rows between two rows that cross the gutter: read as they are, or left column then right column.
 * A row with a box over the gutter, such as a title across both columns, is a section of its own
 * and stays whole.
 */
final class ColumnSection {
    /** A picture set across the columns leaves a blank band at least this many median lines high. */
    private static final float FIGURE_GAP = 3f;
    /** The caption of such a picture has at most this many rows. */
    private static final int CAPTION_ROWS = 3;

    final List<List<TextLine>> rows;
    /** The two sides are columns: {@link #left} is read first, then {@link #right}. */
    final boolean split;
    /** One row with a box over the gutter. */
    final boolean crossing;
    final List<List<TextLine>> left;
    final List<List<TextLine>> right;
    /** Vertical words beside this section, or beside one of its columns. */
    final List<TextLine> extras = new ArrayList<>();
    final List<TextLine> leftExtras = new ArrayList<>();
    final List<TextLine> rightExtras = new ArrayList<>();

    private ColumnSection(List<List<TextLine>> rows, boolean split, boolean crossing,
                          List<List<TextLine>> left, List<List<TextLine>> right) {
        this.rows = rows;
        this.split = split;
        this.crossing = crossing;
        this.left = left;
        this.right = right;
    }

    static ColumnSection whole(List<TextLine> row) {
        List<List<TextLine>> rows = new ArrayList<>();
        rows.add(row);
        return new ColumnSection(rows, false, true, List.of(), List.of());
    }

    static ColumnSection of(List<List<TextLine>> rows, float mid, Splitter splitter) {
        List<TextLine> leftBoxes = new ArrayList<>();
        List<TextLine> rightBoxes = new ArrayList<>();
        List<List<TextLine>> leftRows = new ArrayList<>();
        List<List<TextLine>> rightRows = new ArrayList<>();
        for (List<TextLine> row : rows) {
            List<TextLine> onLeft = new ArrayList<>();
            List<TextLine> onRight = new ArrayList<>();
            for (TextLine line : row) {
                if ((line.left() + line.right()) * 0.5f < mid) {
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
            return new ColumnSection(rows, true, false, leftRows, rightRows);
        }
        return new ColumnSection(rows, false, false, List.of(), List.of());
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

    /** A row with a box over the gutter (a title across both columns) closes a section and stays whole. */
    static List<ColumnSection> sections(List<List<TextLine>> rows, ColumnGutter gutter, float medianH,
                                        Splitter splitter) {
        float mid = gutter.mid();
        List<ColumnSection> sections = new ArrayList<>();
        List<List<TextLine>> open = new ArrayList<>();
        for (List<TextLine> row : rows) {
            if (gutter.crosses(row, medianH)) {
                if (!open.isEmpty()) {
                    sections.add(of(open, mid, splitter));
                    open = new ArrayList<>();
                }
                sections.add(whole(row));
            } else {
                open.add(row);
            }
        }
        if (!open.isEmpty()) {
            sections.add(of(open, mid, splitter));
        }
        return sections;
    }

    /**
     * The index of the section in which the columns of section {@code i} go on below a picture set
     * across them, or -1. A title across the columns ends a band of columns; a picture does not when
     * the text runs on past it. Here it does: only the rows of a caption stand between the two
     * sections, the picture leaves a tall blank band above or below them, and in both columns a
     * sentence is cut by the picture ({@link ColumnTests#runsOn}).
     */
    static int pastFigure(List<ColumnSection> sections, int i, float medianH) {
        ColumnSection above = sections.get(i);
        int j = i + 1;
        while (j < sections.size() && sections.get(j).crossing && j - i <= CAPTION_ROWS) {
            j++;
        }
        if (!above.split || j == i + 1 || j >= sections.size() || !sections.get(j).split) {
            return -1;
        }
        ColumnSection below = sections.get(j);
        float gap = Math.max(sections.get(i + 1).top() - above.bottom(), below.top() - sections.get(j - 1).bottom());
        if (gap < FIGURE_GAP * medianH) {
            return -1;
        }
        return ColumnTests.runsOn(last(above.left), first(below.left))
                && ColumnTests.runsOn(last(above.right), first(below.right)) ? j : -1;
    }

    private static TextLine first(List<List<TextLine>> rows) {
        return rows.get(0).get(0);
    }

    private static TextLine last(List<List<TextLine>> rows) {
        List<TextLine> row = rows.get(rows.size() - 1);
        return row.get(row.size() - 1);
    }

    /** Vertical words go to the section they overlap most, on the side of their centre; the rest are returned. */
    static List<TextLine> place(List<TextLine> vertical, List<ColumnSection> sections, float mid) {
        List<TextLine> unplaced = new ArrayList<>();
        for (TextLine word : vertical) {
            ColumnSection best = null;
            float bestOverlap = 0f;
            for (ColumnSection section : sections) {
                float overlap = Math.min(word.bottom(), section.bottom()) - Math.max(word.top(), section.top());
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = section;
                }
            }
            if (best == null) {
                unplaced.add(word);
            } else if (!best.split) {
                best.extras.add(word);
            } else if ((word.left() + word.right()) * 0.5f < mid) {
                best.leftExtras.add(word);
            } else {
                best.rightExtras.add(word);
            }
        }
        return unplaced;
    }

    /** Decides whether the left and right boxes of a section are columns. */
    interface Splitter {
        boolean split(List<TextLine> leftBoxes, List<TextLine> rightBoxes);
    }
}
