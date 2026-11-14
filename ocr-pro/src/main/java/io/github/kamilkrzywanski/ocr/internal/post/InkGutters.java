package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;
import io.github.kamilkrzywanski.ocr.internal.image.Bgr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Column gutters read from the ink of the page. Where the gutter is narrow, the detector draws
 * one box over a line of each column, sometimes of three columns, and on such a page the boxes
 * hide the gutter from {@link ReadingOrder}. Inside each merged box the gutter is still a strip
 * without ink, wider than a word space, and it sits at the same x in many lines. A word space
 * does not line up like that.
 * <p>
 * A strip counts as a gutter when lines have such a gap there and ordinary column lines end just
 * left of it and start just right of it: the more margin lines, the fewer gaps it needs. The last test keeps a
 * form whose label and value were read as one box in one piece.
 */
public final class InkGutters {
    /** A gap is at least this many box heights wide; a word space is about a fifth. */
    private static final float MIN_GAP = 0.4f;
    /** The ends of a box are unclip padding, this many box heights each. */
    private static final float END_PAD = 0.3f;
    /** An ink run this narrow, with blank on both sides, is a vertical rule inside the gap. */
    private static final float RULE = 0.15f;
    /** Gaps whose centres lie within this many median lines of the densest one are one gutter. */
    private static final float CLUSTER = 0.3f;
    private static final int MIN_SUPPORT = 4;
    /**
     * The margins of the columns: at least {@link #MIN_EDGES} lines end within this many median
     * lines of the gutter, and as many start that close to it. A justified word space inside a
     * column has no margin at either side; on the test pages the best such strip had 3 and 1.
     */
    private static final float EDGE_NEAR = 0.5f;
    private static final int MIN_EDGES = 5;
    /**
     * Or: at least this many gaps, a margin of {@link #STRONG_EDGES} lines on one side and
     * {@link #WEAK_EDGES} on the other. A margin of {@link #STRONG_EDGES} lines on both sides
     * needs a single gap. On the three-column 1912 weekly the gutters had 25 gaps
     * and 14/3 and 4/15 edges; a justified page and a receipt had at most 9 gaps.
     */
    private static final int STRONG_SUPPORT = 10;
    private static final int STRONG_EDGES = 10;
    private static final int WEAK_EDGES = 2;
    /**
     * Only a box this many times wider than the typical text line is searched: two or three
     * column lines read as one are. On a page whose lines all have the width of its column,
     * a justified word space that happens to line up with others is left alone.
     */
    private static final float WIDE = 1.0f;
    /** Lines shorter than this many median heights do not set the typical width. */
    private static final float LONG = 4f;
    /** Boxes steeper than this (dy per dx) are left alone. */
    private static final float MAX_SLOPE = 0.05f;
    /** Below this gray range a box has no readable ink contrast. */
    private static final int MIN_CONTRAST = 40;

    private InkGutters() {
    }

    /** For each line to cut, the cut positions as fractions of its length from the left, ascending. */
    public static Map<TextLine, float[]> cuts(Bgr page, List<TextLine> lines) {
        Map<TextLine, float[]> out = new IdentityHashMap<>();
        if (lines.size() < MIN_SUPPORT) {
            return out;
        }
        float medianH = medianHeight(lines);
        float typical = typicalWidth(lines, medianH);
        List<Gap> gaps = new ArrayList<>();
        for (TextLine line : lines) {
            if (right(line) - left(line) >= WIDE * typical) {
                List<Gap> own = gaps(page, line);
                if (own != null) {
                    gaps.addAll(own);
                }
            }
        }
        if (gaps.isEmpty()) {
            return out;
        }
        Map<TextLine, List<Float>> found = new IdentityHashMap<>();
        for (List<Gap> cluster : clusters(gaps, CLUSTER * medianH)) {
            if (isGutter(cluster, lines, medianH)) {
                for (Gap gap : cluster) {
                    found.computeIfAbsent(gap.line, k -> new ArrayList<>()).add(gap.x);
                }
            }
        }
        for (Map.Entry<TextLine, List<Float>> entry : found.entrySet()) {
            TextLine line = entry.getKey();
            float from = left(line);
            float width = right(line) - from;
            float[] t = new float[entry.getValue().size()];
            for (int i = 0; i < t.length; i++) {
                t[i] = (entry.getValue().get(i) - from) / width;
            }
            Arrays.sort(t);
            out.put(line, t);
        }
        return out;
    }

    /**
     * Gaps around the densest x first: the gap with the most others within {@code reach} takes
     * them, and the rest is grouped again. A chain of gaps each close to the next does not make a
     * gutter wider than {@code reach} on each side.
     */
    /** A cut the boxes suggest is borne out by a gap whose middle lies within this many box heights of it... */
    private static final float CUT_REACH = 1.5f;
    /** ...or by a rule within this many box heights of it: ink from the top of the box to its bottom. */
    private static final float RULE_REACH = 1f;
    private static final float RULE_INK = 0.9f;
    /** One or two lines do not show that a gutter is not there. */
    private static final int DENIERS = 3;

    /**
     * Of the cuts that the boxes alone suggest, those the ink does not speak against.
     * {@link ReadingOrder#cuts} takes a line for two lines of two columns, merged, from where
     * the other lines begin and end; it does not see the page. Two column lines read as one box
     * still have the gutter between them: a strip without ink wider than a word space, or, in
     * a narrow gutter, the rule between the columns, which runs through the whole height of
     * the box as no letter does. When three or more lines are suggested for one gutter and
     * most of them show neither, the gutter is not one and none of them is cut. A one-column
     * letter whose header and signature blocks stand right of the middle was cut down that
     * middle, through its words.
     * <p>
     * Otherwise every suggested line is cut at the suggested place, or, when that place lies on
     * ink, at the middle of the widest gap near it. A box that cannot be looked at (too steep,
     * too short, no contrast) counts as showing the gutter.
     *
     * @param cuts for each line, the fraction of its length, from the left, where it would be cut
     * @return for each line that is cut, the fraction where
     */
    public static Map<TextLine, float[]> confirmed(Bgr page, Map<TextLine, Float> cuts) {
        Map<TextLine, float[]> out = new IdentityHashMap<>();
        if (cuts.isEmpty()) {
            return out;
        }
        List<Suggested> all = new ArrayList<>(cuts.size());
        for (Map.Entry<TextLine, Float> cut : cuts.entrySet()) {
            TextLine line = cut.getKey();
            float left = left(line);
            float width = right(line) - left;
            float x = left + cut.getValue() * width;
            List<Gap> gaps = gaps(page, line);
            if (gaps == null) {
                all.add(new Suggested(line, x, cut.getValue(), true));
                continue;
            }
            float height = bottom(line) - top(line);
            // The widest gap in reach: the gutter is wider than the word spaces beside it.
            Gap best = null;
            for (Gap gap : gaps) {
                if (Math.abs(gap.x - x) <= CUT_REACH * height && (best == null || gap.width > best.width)) {
                    best = gap;
                }
            }
            if (best == null) {
                all.add(new Suggested(line, x, cut.getValue(), rule(page, line, x, RULE_REACH * height)));
            } else {
                // A cut that falls between letters stays where it is: beside a justified line
                // the widest gap may be a word space, and the gutter the narrow one.
                all.add(new Suggested(line, x, inkAt(page, line, x) ? (best.x - left) / width : cut.getValue(), true));
            }
        }
        // The lines of one gutter are suggested at one x; of two gutters, columns apart.
        all.sort((a, b) -> Float.compare(a.x, b.x));
        float near = medianHeight(new ArrayList<>(cuts.keySet()));
        int from = 0;
        for (int i = 1; i <= all.size(); i++) {
            if (i < all.size() && all.get(i).x - all.get(i - 1).x <= near) {
                continue;
            }
            int borne = 0;
            for (int k = from; k < i; k++) {
                if (all.get(k).borne) {
                    borne++;
                }
            }
            if (i - from < DENIERS || borne * 2 >= i - from) {
                for (int k = from; k < i; k++) {
                    out.put(all.get(k).line, new float[] {all.get(k).at});
                }
            }
            from = i;
        }
        return out;
    }

    /** Whether a column within {@code reach} of {@code x} is ink from the top of the box to its bottom. */
    private static boolean rule(Bgr page, TextLine line, float x, float reach) {
        int l = Math.max(0, (int) Math.floor(left(line)));
        int r = Math.min(page.width(), (int) Math.ceil(right(line)));
        int t = Math.max(0, (int) Math.floor(top(line)));
        int b = Math.min(page.height(), (int) Math.ceil(bottom(line)));
        int h = b - t;
        int threshold = threshold(page, l, r, t + h / 4, b - h / 4);
        if (threshold < 0) {
            return false;
        }
        int from = Math.max(l, Math.round(x - reach));
        int to = Math.min(r, Math.round(x + reach));
        for (int column = from; column < to; column++) {
            int ink = 0;
            for (int y = t; y < b; y++) {
                if (gray(page, column, y) < threshold) {
                    ink++;
                }
            }
            if (ink >= RULE_INK * h) {
                return true;
            }
        }
        return false;
    }

    /** Whether the column at {@code x} has ink in the middle half of the box's height. */
    private static boolean inkAt(Bgr page, TextLine line, float x) {
        int l = Math.max(0, (int) Math.floor(left(line)));
        int r = Math.min(page.width(), (int) Math.ceil(right(line)));
        int t = Math.max(0, (int) Math.floor(top(line)));
        int b = Math.min(page.height(), (int) Math.ceil(bottom(line)));
        int h = b - t;
        int threshold = threshold(page, l, r, t + h / 4, b - h / 4);
        int column = Math.max(l, Math.min(r - 1, Math.round(x)));
        for (int y = t + h / 4; y < b - h / 4; y++) {
            if (gray(page, column, y) < threshold) {
                return true;
            }
        }
        return false;
    }

    /** The gray that parts ink from paper in the rows {@code y0} to {@code y1} of a box, or -1 without contrast. */
    private static int threshold(Bgr page, int l, int r, int y0, int y1) {
        int[] hist = new int[256];
        for (int y = y0; y < y1; y++) {
            for (int x = l; x < r; x++) {
                hist[gray(page, x, y)]++;
            }
        }
        int total = (y1 - y0) * (r - l);
        int p10 = percentile(hist, total, 0.10);
        int p90 = percentile(hist, total, 0.90);
        return p90 - p10 < MIN_CONTRAST ? -1 : (p10 + p90) / 2;
    }

    /** A line the boxes would cut at {@code x}; {@code at} is where, and {@code borne} whether the ink shows a gutter. */
    private record Suggested(TextLine line, float x, float at, boolean borne) {
    }

    private static List<List<Gap>> clusters(List<Gap> gaps, float reach) {
        List<List<Gap>> out = new ArrayList<>();
        List<Gap> left = new ArrayList<>(gaps);
        while (!left.isEmpty()) {
            Gap best = null;
            int bestCount = -1;
            for (Gap a : left) {
                int count = 0;
                for (Gap b : left) {
                    if (Math.abs(a.x - b.x) <= reach) {
                        count++;
                    }
                }
                if (count > bestCount) {
                    bestCount = count;
                    best = a;
                }
            }
            List<Gap> cluster = new ArrayList<>();
            for (int i = left.size() - 1; i >= 0; i--) {
                if (Math.abs(left.get(i).x - best.x) <= reach) {
                    cluster.add(left.remove(i));
                }
            }
            out.add(cluster);
        }
        return out;
    }

    private static boolean isGutter(List<Gap> cluster, List<TextLine> lines, float medianH) {
        long distinct = cluster.stream().map(g -> g.line).distinct().count();
        float[] xs = new float[cluster.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = cluster.get(i).x;
        }
        Arrays.sort(xs);
        float x = xs[xs.length / 2];
        int leftEnds = 0;
        int rightStarts = 0;
        for (TextLine line : lines) {
            float l = left(line);
            float r = right(line);
            // A box keeps unclip padding, so its edge may lie just past the middle of a narrow gutter.
            if (Math.abs(r - x) <= EDGE_NEAR * medianH && l < x - 2 * medianH) {
                leftEnds++;
            }
            if (Math.abs(l - x) <= EDGE_NEAR * medianH && r > x + 2 * medianH) {
                rightStarts++;
            }
        }
        if (leftEnds >= STRONG_EDGES && rightStarts >= STRONG_EDGES) {
            // Columns this plain need no more than one merged line to show the gutter.
            return true;
        }
        if (distinct < MIN_SUPPORT) {
            return false;
        }
        if (leftEnds >= MIN_EDGES && rightStarts >= MIN_EDGES) {
            return true;
        }
        // Where most lines of one column are merged, its margin shows only on the other side.
        return distinct >= STRONG_SUPPORT
                && Math.max(leftEnds, rightStarts) >= STRONG_EDGES
                && Math.min(leftEnds, rightStarts) >= WEAK_EDGES;
    }

    /** Blank strips inside one box, wide enough to be a gutter; null when the box cannot be looked at. */
    private static List<Gap> gaps(Bgr page, TextLine line) {
        List<Gap> out = new ArrayList<>();
        float[] q = line.quad();
        float dx = q[2] - q[0];
        if (dx <= 0f || Math.abs(q[3] - q[1]) > MAX_SLOPE * dx) {
            return null;
        }
        int l = Math.max(0, (int) Math.floor(left(line)));
        int r = Math.min(page.width(), (int) Math.ceil(right(line)));
        int t = Math.max(0, (int) Math.floor(top(line)));
        int b = Math.min(page.height(), (int) Math.ceil(bottom(line)));
        int h = b - t;
        if (h < 4 || r - l < 4 * h) {
            return null;
        }
        int y0 = t + h / 4;
        int y1 = b - h / 4;
        int threshold = threshold(page, l, r, y0, y1);
        if (threshold < 0) {
            return null;
        }
        boolean[] ink = new boolean[r - l];
        for (int x = l; x < r; x++) {
            for (int y = y0; y < y1; y++) {
                if (gray(page, x, y) < threshold) {
                    ink[x - l] = true;
                    break;
                }
            }
        }
        int pad = Math.round(END_PAD * h);
        int from = pad;
        int to = ink.length - pad;
        int ruleMax = Math.max(1, Math.round(RULE * h));
        int x = from;
        while (x < to) {
            if (ink[x]) {
                x++;
                continue;
            }
            int gapStart = x;
            int gapEnd = blankEnd(ink, x, to);
            // A thin rule with a real blank on both sides belongs to the gap; a narrow letter
            // between two word spaces ("a i b") has too little blank around it.
            int lastBlank = gapEnd - gapStart;
            while (gapEnd < to && lastBlank >= ruleMax) {
                int inkEnd = gapEnd;
                while (inkEnd < to && ink[inkEnd]) {
                    inkEnd++;
                }
                if (inkEnd - gapEnd > ruleMax || inkEnd >= to) {
                    break;
                }
                int nextEnd = blankEnd(ink, inkEnd, to);
                if (nextEnd - inkEnd < ruleMax) {
                    break;
                }
                lastBlank = nextEnd - inkEnd;
                gapEnd = nextEnd;
            }
            if (gapEnd - gapStart >= MIN_GAP * h && gapStart > from && gapEnd < to) {
                out.add(new Gap(line, l + (gapStart + gapEnd) * 0.5f, gapEnd - gapStart));
            }
            x = gapEnd;
        }
        return out;
    }

    private static int blankEnd(boolean[] ink, int x, int to) {
        while (x < to && !ink[x]) {
            x++;
        }
        return x;
    }

    private static int gray(Bgr page, int x, int y) {
        int i = (y * page.width() + x) * 3;
        byte[] d = page.data();
        return ((d[i] & 0xff) + (d[i + 1] & 0xff) + (d[i + 2] & 0xff)) / 3;
    }

    private static int percentile(int[] hist, int total, double share) {
        long need = Math.round(total * share);
        long seen = 0;
        for (int v = 0; v < hist.length; v++) {
            seen += hist[v];
            if (seen >= need) {
                return v;
            }
        }
        return 255;
    }

    /** Median width of the lines at least {@link #LONG} median heights long; infinite when none is. */
    private static float typicalWidth(List<TextLine> lines, float medianH) {
        List<Float> widths = new ArrayList<>();
        for (TextLine line : lines) {
            float w = right(line) - left(line);
            if (w >= LONG * medianH) {
                widths.add(w);
            }
        }
        if (widths.isEmpty()) {
            return Float.POSITIVE_INFINITY;
        }
        widths.sort(null);
        return widths.get(widths.size() / 2);
    }

    private static float medianHeight(List<TextLine> lines) {
        float[] h = new float[lines.size()];
        for (int i = 0; i < h.length; i++) {
            h[i] = bottom(lines.get(i)) - top(lines.get(i));
        }
        Arrays.sort(h);
        return h[h.length / 2];
    }

    private static float left(TextLine line) {
        return line.left();
    }

    private static float right(TextLine line) {
        return line.right();
    }

    private static float top(TextLine line) {
        return line.top();
    }

    private static float bottom(TextLine line) {
        return line.bottom();
    }

    private record Gap(TextLine line, float x, float width) {
    }
}
