package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.maxRight;
import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.medianCount;
import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.minLeft;

/**
 * The gutter of a block: the emptiest vertical strip once each box is narrowed by its padding, so
 * columns whose boxes touch on a low-resolution scan still separate. Also whether a line crosses
 * that strip, as a title over both columns does.
 *
 * @param from the first x of the strip
 * @param to the last x of the strip, inclusive
 * @param over how many boxes still lie over the strip
 */
record ColumnGutter(float from, float to, int over) {
    /** A row mix at or above this many boxes is a grid and stays left to right. */
    static final float GRID_ROW = 3f;
    /**
     * Detection boxes are padded by unclip. This fraction of a box height is taken off each side
     * before looking for a gutter. On the 691 px newspaper the padded columns touch at x = 343.
     */
    static final float BOX_PAD = 0.25f;
    /** Boxes that may still cross the gutter, as a share of the block (a title, two merged lines). */
    static final float CROSSING = 0.1f;
    /**
     * Text columns may have more: a title of several lines, a closing sentence and a signature
     * across the page. Each section between them must still hold two text columns.
     */
    static final float TEXT_CROSSING = 0.35f;
    /**
     * A line this many median lines long that reaches past the gutter on one side by less than
     * {@link #CROSS_SIDE} of its width, and less than {@link #INTRUDE} median lines, picked up a
     * letter of the other column; it does not cross.
     */
    static final float LONG_LINE = 4f;
    static final float CROSS_SIDE = 0.1f;
    static final float INTRUDE = 1.5f;

    /** The x in the middle of the strip: a box belongs to the side its centre is on. */
    float mid() {
        return (from + to) * 0.5f;
    }

    /** No box lies over the strip. */
    boolean clean() {
        return over == 0;
    }

    /** The narrowed box reaches into the gutter. */
    boolean reaches(TextLine line) {
        float pad = BOX_PAD * (line.bottom() - line.top());
        return line.left() + pad <= to && line.right() - pad >= from;
    }

    /**
     * The emptiest vertical strip between at least two boxes on each side, after each box loses
     * its padding. Null for a small block, a block that no strip separates, or a grid unless
     * {@code grid} allows one (text columns check their sides instead). At most {@code crossing}
     * of the boxes may still cross the strip.
     */
    static ColumnGutter find(List<List<TextLine>> zone, float medianH, boolean grid, float crossing) {
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
            float pad = BOX_PAD * (box.bottom() - box.top());
            lefts[i] = box.left() + pad - zoneLeft;
            rights[i] = box.right() - pad - zoneLeft;
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
        return new ColumnGutter(zoneLeft + bestFrom, zoneLeft + bestTo, least);
    }

    /** Values strictly below {@code x} in a sorted array. */
    static int countBelow(float[] sorted, float x) {
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
    static int countAbove(float[] sorted, float x) {
        int i = Arrays.binarySearch(sorted, x);
        if (i < 0) {
            return sorted.length - (-i - 1);
        }
        while (i < sorted.length - 1 && sorted[i + 1] == x) {
            i++;
        }
        return sorted.length - i - 1;
    }

    /**
     * A row crosses when one of its boxes does. A box that runs into the next box of its row is
     * first cut where that box begins: the text there is the neighbour's. A short line at the end
     * of the left column, whose box the detector drew a little too long, then stays in its column
     * although the padded box lies over a narrow gutter; a title or a page number with nothing
     * beside it still crosses.
     */
    boolean crosses(List<TextLine> row, float medianH) {
        for (TextLine line : row) {
            float left = line.left();
            float right = line.right();
            for (TextLine other : row) {
                if (other == line) {
                    continue;
                }
                if (other.left() > line.left() && other.left() < line.right() && other.right() > line.right()) {
                    right = Math.min(right, other.left());
                }
                if (other.right() < line.right() && other.right() > line.left() && other.left() < line.left()) {
                    left = Math.max(left, other.right());
                }
            }
            if (crossing(left, right, line.bottom() - line.top(), medianH)) {
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
    boolean crossing(TextLine line, float medianH) {
        return crossing(line.left(), line.right(), line.bottom() - line.top(), medianH);
    }

    /** {@link #crossing(TextLine, float)} for a box from {@code left} to {@code right}, {@code height} high. */
    private boolean crossing(float left, float right, float height, float medianH) {
        float pad = BOX_PAD * height;
        float start = left + pad;
        float end = right - pad;
        if (start > to || end < from) {
            return false;
        }
        float width = end - start;
        return width < LONG_LINE * medianH
                || Math.min(from - start, end - to) >= Math.min(CROSS_SIDE * width, INTRUDE * medianH);
    }
}
