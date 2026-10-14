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
 * A strip counts as a gutter when at least {@link #MIN_SUPPORT} lines have such a gap there and
 * ordinary column lines end just left of it and start just right of it. The last test keeps a
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
     * {@link #WEAK_EDGES} on the other. On the three-column 1912 weekly the gutters had 25 gaps
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
                gaps.addAll(gaps(page, line));
            }
        }
        if (gaps.size() < MIN_SUPPORT) {
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
        if (distinct < MIN_SUPPORT) {
            return false;
        }
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
        if (leftEnds >= MIN_EDGES && rightStarts >= MIN_EDGES) {
            return true;
        }
        // Where most lines of one column are merged, its margin shows only on the other side.
        return distinct >= STRONG_SUPPORT
                && Math.max(leftEnds, rightStarts) >= STRONG_EDGES
                && Math.min(leftEnds, rightStarts) >= WEAK_EDGES;
    }

    /** Blank strips inside one box, wide enough to be a gutter. */
    private static List<Gap> gaps(Bgr page, TextLine line) {
        List<Gap> out = new ArrayList<>();
        float[] q = line.quad();
        float dx = q[2] - q[0];
        if (dx <= 0f || Math.abs(q[3] - q[1]) > MAX_SLOPE * dx) {
            return out;
        }
        int l = Math.max(0, (int) Math.floor(left(line)));
        int r = Math.min(page.width(), (int) Math.ceil(right(line)));
        int t = Math.max(0, (int) Math.floor(top(line)));
        int b = Math.min(page.height(), (int) Math.ceil(bottom(line)));
        int h = b - t;
        if (h < 4 || r - l < 4 * h) {
            return out;
        }
        int y0 = t + h / 4;
        int y1 = b - h / 4;
        int[] hist = new int[256];
        for (int y = y0; y < y1; y++) {
            for (int x = l; x < r; x++) {
                hist[gray(page, x, y)]++;
            }
        }
        int total = (y1 - y0) * (r - l);
        int p10 = percentile(hist, total, 0.10);
        int p90 = percentile(hist, total, 0.90);
        if (p90 - p10 < MIN_CONTRAST) {
            return out;
        }
        int threshold = (p10 + p90) / 2;
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
                out.add(new Gap(line, l + (gapStart + gapEnd) * 0.5f));
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
        return ReadingOrder.left(line);
    }

    private static float right(TextLine line) {
        return ReadingOrder.right(line);
    }

    private static float top(TextLine line) {
        return ReadingOrder.top(line);
    }

    private static float bottom(TextLine line) {
        return ReadingOrder.bottom(line);
    }

    private record Gap(TextLine line, float x) {
    }
}
