package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.maxRight;
import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.median;
import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.medianWidth;
import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.minLeft;

/**
 * Whether the two sides of a gutter are columns to read one after the other. Text columns share a
 * margin, stand alone on their rows and fill their width. A form with scattered fields stays in
 * rows, a right-aligned price stays on the item's row, and a rail of list numbers or of amounts
 * is no column.
 */
final class ColumnTests {
    /** Narrower than this fraction of the median line is word spacing, not a column gutter. */
    static final float MIN_GUTTER = 0.8f;
    /** Right edges this close, in fractions of the median line, are a price rail. */
    static final float RAIL_EDGE = 0.8f;
    /** Left edges within this many median lines count as one margin; it covers a paragraph indent. */
    static final float ALIGN = 1.5f;
    /**
     * With a gutter of at least {@link #MIN_GUTTER}, a side of this many lines or more must read like
     * a column: most left edges on one margin, most rows with one box on that side.
     * The fare block of the train ticket has 0.38 and 0.56; the invoice parties have 0.80 and 0.75.
     */
    static final int FORM_LINES = 4;
    static final float FORM_ALIGN = 0.6f;
    static final float FORM_SINGLE = 0.65f;
    /**
     * A text column: this many lines, nearly all on one margin, alone on their rows, and spanning
     * most of the column width. A narrower gutter is accepted only between two such columns.
     */
    static final int NARROW_LINES = 5;
    static final float NARROW_ALIGN = 0.8f;
    static final float NARROW_SINGLE = 0.8f;
    static final float NARROW_FILL = 0.6f;
    /** Between text columns that no box crosses, lines may fill this little: a column of verse. */
    static final float OPEN_FILL = 0.3f;
    /**
     * A side whose lines are narrower than this share of the lines beside it is a rail of list
     * numbers or bullets, not a column: "2." to "6." next to the items of the 1921 manual.
     */
    static final float MARKER_SHARE = 0.25f;
    /** A side where at least this share of boxes are mostly digits is a column of values, not text. */
    static final float VALUE_SHARE = 0.6f;
    /** Text columns of one page are set to similar widths; item names and prices are not. */
    static final float WIDTH_SHARE = 0.5f;
    /** Boxes at least this share of the page wide may be merged lines when the gutter does not show. */
    static final float WIDE_SHARE = 0.75f;

    private ColumnTests() {
    }

    /** Whether the two sides of a section are columns to read one after the other. */
    static boolean columnsHold(List<TextLine> leftBoxes, List<TextLine> rightBoxes,
                                       List<List<TextLine>> rows, float medianH) {
        if (leftBoxes.size() < 2 || rightBoxes.size() < 2
                || !beside(leftBoxes, rightBoxes) || rowsOfCells(leftBoxes, rightBoxes)) {
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
    static boolean textSplit(List<TextLine> leftBoxes, List<TextLine> rightBoxes,
                                     List<List<TextLine>> rows, float medianH, int depth, boolean clean) {
        if (leftBoxes.size() < NARROW_LINES || rightBoxes.size() < NARROW_LINES || !beside(leftBoxes, rightBoxes)) {
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

    /**
     * Columns stand beside each other: at least this share of the lines of one side have a line
     * of the other side at their height. The letterhead at the top left of a letter and the
     * date and the addressee further down on the right are on two sides of a gutter and not
     * beside each other; read as columns, the greeting under both came before the date.
     */
    static final float BESIDE = 0.5f;

    static boolean beside(List<TextLine> leftBoxes, List<TextLine> rightBoxes) {
        return Math.max(facing(leftBoxes, rightBoxes), facing(rightBoxes, leftBoxes)) >= BESIDE;
    }

    /**
     * Share of the lines of {@code side} with a line of {@code other} over at least half the
     * lower of the two heights. Fragments, which the engine drops from its result, are on
     * neither side: a stamp read as a few unsure characters beside a letterhead does not make
     * the block across the gutter its neighbour.
     */
    static float facing(List<TextLine> side, List<TextLine> other) {
        int lines = 0;
        int facing = 0;
        for (TextLine line : side) {
            if (weak(line)) {
                continue;
            }
            lines++;
            for (TextLine across : other) {
                float shared = Math.min(line.bottom(), across.bottom()) - Math.max(line.top(), across.top());
                if (!weak(across)
                        && shared >= LineRows.ROW_OVERLAP * Math.min(line.bottom() - line.top(), across.bottom() - across.top())) {
                    facing++;
                    break;
                }
            }
        }
        return lines == 0 ? 0f : facing / (float) lines;
    }

    /** A fragment that was read: with recognition off every line has an empty text and none is weak. */
    private static boolean weak(TextLine line) {
        return !line.text().isEmpty() && Fragments.is(line);
    }

    /**
     * Two small blocks side by side are the cells of rows, not blocks to read one after the
     * other, when their lines stand on the same baselines: more than this share of the lines
     * of the side with fewer lines have a line across the gutter whose top and bottom lie
     * within {@link #BASELINE} of a line height of their own. Fields of a form, a label and
     * its value, a name and a title, the address and the telephone numbers of a footer are set
     * like that. Two blocks set each for itself, a note beside a paragraph or two parts of a
     * letterhead, have their own line pitch and their lines pass each other.
     */
    static final float SHARED_ROWS = 0.5f;
    static final float BASELINE = 0.25f;

    static boolean rowsOfCells(List<TextLine> leftBoxes, List<TextLine> rightBoxes) {
        List<TextLine> fewer = leftBoxes.size() <= rightBoxes.size() ? leftBoxes : rightBoxes;
        List<TextLine> more = fewer == leftBoxes ? rightBoxes : leftBoxes;
        int lines = 0;
        int shared = 0;
        for (TextLine line : fewer) {
            if (weak(line)) {
                continue;
            }
            lines++;
            float near = BASELINE * (line.bottom() - line.top());
            for (TextLine across : more) {
                if (!weak(across)
                        && Math.abs(line.top() - across.top()) <= near && Math.abs(line.bottom() - across.bottom()) <= near) {
                    shared++;
                    break;
                }
            }
        }
        return shared > SHARED_ROWS * lines;
    }

    /** One text column, or a side that splits again into text columns. */
    static boolean columnLike(List<TextLine> side, List<List<TextLine>> rows, float medianH, int depth,
                                      float minFill) {
        if (textSide(side, rows, medianH, minFill)) {
            return true;
        }
        if (depth + 1 >= ReadingOrder.MAX_DEPTH || side.size() < 2 * NARROW_LINES) {
            return false;
        }
        if (splitsInto(side, rows, medianH, depth)) {
            return true;
        }
        // Lines merged over a narrow gutter hide it; the narrower boxes still show it.
        float span = maxRight(side) - minLeft(side);
        List<TextLine> narrow = new ArrayList<>();
        for (TextLine line : side) {
            if (line.right() - line.left() < WIDE_SHARE * span) {
                narrow.add(line);
            }
        }
        return narrow.size() < side.size() && narrow.size() >= 2 * NARROW_LINES
                && splitsInto(narrow, rows, medianH, depth);
    }

    /** The gutter of {@code boxes} splits them into two text columns. */
    static boolean splitsInto(List<TextLine> boxes, List<List<TextLine>> rows, float medianH, int depth) {
        ColumnGutter gutter = ColumnGutter.find(LineRows.seedRows(boxes), medianH, true, ColumnGutter.CROSSING);
        if (gutter == null) {
            return false;
        }
        float mid = gutter.mid();
        List<TextLine> leftBoxes = new ArrayList<>();
        List<TextLine> rightBoxes = new ArrayList<>();
        for (TextLine line : boxes) {
            if (gutter.crossing(line, medianH)) {
                // A line over the gutter, a heading or two lines the detector merged, is of
                // neither side: on the side of its centre it would pass for that column's width.
                continue;
            }
            if ((line.left() + line.right()) * 0.5f < mid) {
                leftBoxes.add(line);
            } else {
                rightBoxes.add(line);
            }
        }
        return textSplit(leftBoxes, rightBoxes, rows, medianH, depth + 1, gutter.clean());
    }

    static boolean formSide(List<TextLine> side, List<List<TextLine>> rows, float medianH) {
        return side.size() < FORM_LINES
                || alignedShare(side, medianH) >= FORM_ALIGN && singleShare(side, rows) >= FORM_SINGLE;
    }

    static boolean textSide(List<TextLine> side, List<List<TextLine>> rows, float medianH) {
        return textSide(side, rows, medianH, NARROW_FILL);
    }

    static boolean textSide(List<TextLine> side, List<List<TextLine>> rows, float medianH, float minFill) {
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
    static float singleLineShare(List<TextLine> side, List<List<TextLine>> rows, float medianH) {
        Set<TextLine> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(side);
        int touched = 0;
        int single = 0;
        for (List<TextLine> row : rows) {
            int pieces = 0;
            float end = Float.NEGATIVE_INFINITY;
            for (TextLine line : row) {
                if (set.contains(line)) {
                    if (line.left() - end > MIN_GUTTER * medianH) {
                        pieces++;
                    }
                    end = Math.max(end, line.right());
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
    static float marginShare(List<TextLine> side, float medianH) {
        float reach = ALIGN * medianH;
        float[] lefts = new float[side.size()];
        float[] rights = new float[side.size()];
        for (int i = 0; i < lefts.length; i++) {
            lefts[i] = side.get(i).left();
            rights[i] = side.get(i).right();
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
    static float densest(float[] values, float reach, float taken) {
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
    static float alignedShare(List<TextLine> side, float medianH) {
        int best = 0;
        for (TextLine a : side) {
            int near = 0;
            for (TextLine b : side) {
                if (Math.abs(a.left() - b.left()) <= ALIGN * medianH) {
                    near++;
                }
            }
            best = Math.max(best, near);
        }
        return best / (float) side.size();
    }

    /** Share of the rows touching this side that hold exactly one of its boxes. */
    static float singleShare(List<TextLine> side, List<List<TextLine>> rows) {
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

    /** Median line width as a share of the side's width. */
    static float fill(List<TextLine> side) {
        float span = maxRight(side) - minLeft(side);
        if (span <= 0f) {
            return 0f;
        }
        float[] shares = new float[side.size()];
        for (int i = 0; i < shares.length; i++) {
            shares[i] = (side.get(i).right() - side.get(i).left()) / span;
        }
        return median(shares);
    }

    /**
     * Most boxes of the side are numbers (at least three digits, more digits than letters): amounts,
     * quantities, rates, dates. Such a side belongs to
     * the rows of the labels beside it, wherever its edges fall; a total printed larger than the
     * other amounts must not turn the totals of a receipt into two columns.
     */
    static boolean values(List<TextLine> side) {
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
    static boolean valueRail(List<TextLine> right, List<TextLine> leftSide, float gutter, float medianH) {
        float minRight = Float.POSITIVE_INFINITY;
        float maxRight = Float.NEGATIVE_INFINITY;
        float minLeft = Float.POSITIVE_INFINITY;
        float maxLeft = Float.NEGATIVE_INFINITY;
        float[] widths = new float[right.size()];
        for (int i = 0; i < right.size(); i++) {
            TextLine line = right.get(i);
            minRight = Math.min(minRight, line.right());
            maxRight = Math.max(maxRight, line.right());
            minLeft = Math.min(minLeft, line.left());
            maxLeft = Math.max(maxLeft, line.left());
            widths[i] = line.right() - line.left();
        }
        float rightRange = maxRight - minRight;
        boolean aligned = rightRange <= RAIL_EDGE * medianH;
        boolean raggedLeft = maxLeft - minLeft > rightRange + 0.5f * medianH;
        float valueWidth = median(widths);
        boolean wideHole = gutter >= valueWidth;
        float[] names = new float[leftSide.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = leftSide.get(i).right() - leftSide.get(i).left();
        }
        boolean shortValues = valueWidth < 0.5f * median(names);
        return aligned && (raggedLeft && shortValues || wideHole);
    }
}
