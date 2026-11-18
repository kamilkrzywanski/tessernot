package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.flatten;
import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.maxRight;
import static io.github.kamilkrzywanski.ocr.internal.post.LineBoxes.minLeft;

/**
 * Lines the detector merged across the gutter of two text columns. Read as two lines, they let
 * the columns be read one after the other. A title across the columns, a line of a full-width
 * paragraph and a running head stay whole.
 */
final class MergedLines {
    /**
     * A box across a text-column gutter is two merged lines when it is no taller than this many
     * median lines, starts on the left column's margin, and holds this many median lines of text
     * on each side of the gutter.
     */
    static final float MERGED_HEIGHT = 1.5f;
    static final float MERGED_SIDE = 2f;

    private MergedLines() {
    }

    /**
     * Crossing rows that are two column lines read as one box. A candidate has the height of a text
     * line, starts on the left column's margin, runs well into both columns and ends inside the
     * right one. It counts when the columns go on right above or below it, directly or through
     * another such row.
     */
    static void mergedLines(List<ColumnSection> sections, ColumnGutter gutter, float medianH, Map<TextLine, Float> found) {
        float margin = Float.POSITIVE_INFINITY;
        float end = Float.NEGATIVE_INFINITY;
        for (ColumnSection section : sections) {
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
                merged[i] = box != null && box.right() <= end + ColumnTests.ALIGN * medianH ? box : null;
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
        float cut = gutter.mid();
        for (int i = 0; i < n; i++) {
            if (accepted[i]) {
                found.put(merged[i], cut);
            }
        }
    }

    /** The only box of the row over the gutter, when it looks like two column lines in one. */
    static TextLine mergedBox(List<TextLine> row, ColumnGutter gutter, float margin, float medianH) {
        TextLine merged = null;
        for (TextLine line : row) {
            if (gutter.reaches(line)) {
                if (merged != null) {
                    return null;
                }
                merged = line;
            }
        }
        return merged != null && mergedShape(merged, gutter, margin, medianH) ? merged : null;
    }

    /** A text line's height, starting on the left column's margin, with text well into both columns. */
    static boolean mergedShape(TextLine line, ColumnGutter gutter, float margin, float medianH) {
        float height = line.bottom() - line.top();
        if (height > MERGED_HEIGHT * medianH) {
            return false;
        }
        float pad = ColumnGutter.BOX_PAD * height;
        return line.left() <= margin + ColumnTests.ALIGN * medianH
                && line.left() + pad < gutter.from() - MERGED_SIDE * medianH
                && line.right() - pad > gutter.to() + MERGED_SIDE * medianH;
    }

    /**
     * When the detector merged so many lines that the gutter does not show, it is looked for among
     * the boxes narrower than {@link ColumnTests#WIDE_SHARE} of the block. If those form two text columns,
     * every box over the gutter with the shape of a merged line, ending inside the right column,
     * with a column line right above or below it is merged.
     */
    static void widelyMerged(List<TextLine> boxes, float medianH, Map<TextLine, Float> found) {
        if (boxes.size() < 2 * ColumnTests.NARROW_LINES) {
            return;
        }
        float span = maxRight(boxes) - minLeft(boxes);
        List<TextLine> narrow = new ArrayList<>();
        boolean anyWide = false;
        for (TextLine box : boxes) {
            if (box.right() - box.left() >= ColumnTests.WIDE_SHARE * span) {
                anyWide = true;
            } else {
                narrow.add(box);
            }
        }
        if (!anyWide || narrow.size() < 2 * ColumnTests.NARROW_LINES) {
            return;
        }
        List<List<TextLine>> rows = LineRows.seedRows(narrow);
        ColumnGutter gutter = ColumnGutter.find(rows, medianH, true, ColumnGutter.CROSSING);
        if (gutter == null) {
            return;
        }
        float mid = gutter.mid();
        List<TextLine> leftBoxes = new ArrayList<>();
        List<TextLine> rightBoxes = new ArrayList<>();
        for (TextLine box : narrow) {
            if (!gutter.reaches(box)) {
                ((box.left() + box.right()) * 0.5f < mid ? leftBoxes : rightBoxes).add(box);
            }
        }
        if (!ColumnTests.textSplit(leftBoxes, rightBoxes, rows, medianH, 0, gutter.clean())) {
            return;
        }
        float margin = minLeft(leftBoxes);
        float end = maxRight(rightBoxes) + ColumnTests.ALIGN * medianH;
        List<TextLine> candidates = new ArrayList<>();
        for (TextLine box : boxes) {
            if (!found.containsKey(box) && gutter.reaches(box) && mergedShape(box, gutter, margin, medianH)
                    && box.right() <= end) {
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
    static boolean nextTo(TextLine box, List<TextLine> column, float medianH) {
        for (TextLine line : column) {
            float blank = Math.max(line.top() - box.bottom(), box.top() - line.bottom());
            if (blank <= LineRows.ZONE_GAP * medianH && Math.abs(line.top() - box.top()) <= 2f * medianH) {
                return true;
            }
        }
        return false;
    }

    /** Section {@code j} is split into columns, or an accepted merged row, with no blank between it and {@code i}. */
    static boolean continues(List<ColumnSection> sections, boolean[] accepted, int i, int j, float medianH) {
        if (j < 0 || j >= sections.size() || !sections.get(j).split && !accepted[j]) {
            return false;
        }
        ColumnSection a = sections.get(Math.min(i, j));
        ColumnSection b = sections.get(Math.max(i, j));
        return b.top() - a.bottom() <= LineRows.ZONE_GAP * medianH;
    }
}
