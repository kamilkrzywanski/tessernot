package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.image.Bgr;
import io.github.kamilkrzywanski.ocr.internal.post.DbPost;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A page that was scanned on its side or upside down: how the engine tells, and how the result
 * goes back to the pixels of the picture it was given.
 * <p>
 * The engine reads single lines at any quarter turn, but rows, columns and tables are found from
 * where the lines lie, and only make sense on an upright page. So the page is turned first:
 * a quarter turn when most of its text stands in boxes on their short side, then half a turn
 * when most of its long lines read upside down to the line classifier, each judged by its
 * middle. A page that is then still askew by more than a line height from top to bottom is
 * turned level, by the angle of its lines. After reading, every quad goes back into the
 * coordinates of the original picture, with its corners still in the order of the text: top
 * left of the line first.
 */
final class PageTurn {
    /** A box this many times as long one way as the other lies or stands; anything squarer says nothing. */
    static final float LONG = 1.5f;
    /** A line that votes on upside down is at least this many times as long as it is high. */
    static final float VOTER = 3f;
    /** At most this many lines vote, the longest first. */
    static final int VOTERS = 15;
    /** Fewer voters than this decide nothing. */
    static final int MIN_VOTERS = 3;
    /**
     * A voter shows the classifier its middle, at most this many times as long as it is high.
     * The classifier squeezes every crop to twice its height: a whole line of forty heights is a
     * smear to it, and on a page of such lines it answered "upright" whichever way they lay.
     */
    static final int WINDOW = 8;

    private PageTurn() {
    }

    /** Most of the text, by the length of its boxes, stands on its short side. */
    static boolean sideways(List<DbPost.Box> boxes) {
        float standing = 0f;
        float lying = 0f;
        for (DbPost.Box box : boxes) {
            float width = width(box.quad());
            float height = height(box.quad());
            if (height >= LONG * width) {
                standing += height;
            } else if (width >= LONG * height) {
                lying += width;
            }
        }
        return standing > lying;
    }

    /** The lines that vote on upside down: long lying boxes, the longest first. */
    static List<DbPost.Box> voters(List<DbPost.Box> boxes) {
        List<DbPost.Box> long_ = new ArrayList<>();
        for (DbPost.Box box : boxes) {
            if (width(box.quad()) >= VOTER * height(box.quad())) {
                long_.add(box);
            }
        }
        long_.sort(Comparator.comparingDouble((DbPost.Box box) -> -width(box.quad())));
        return long_.size() > VOTERS ? new ArrayList<>(long_.subList(0, VOTERS)) : long_;
    }

    /** The middle of a line crop, {@link #WINDOW} heights long; the crop itself when it is shorter. */
    static Bgr window(Bgr crop) {
        int width = WINDOW * crop.height();
        if (crop.width() <= width) {
            return crop;
        }
        int from = (crop.width() - width) / 2;
        byte[] data = new byte[width * crop.height() * 3];
        for (int y = 0; y < crop.height(); y++) {
            System.arraycopy(crop.data(), (y * crop.width() + from) * 3, data, y * width * 3, width * 3);
        }
        return new Bgr(data, width, crop.height());
    }

    private static float width(float[] q) {
        return (float) Math.max(Math.hypot(q[2] - q[0], q[3] - q[1]), Math.hypot(q[4] - q[6], q[5] - q[7]));
    }

    private static float height(float[] q) {
        return (float) Math.max(Math.hypot(q[6] - q[0], q[7] - q[1]), Math.hypot(q[4] - q[2], q[5] - q[3]));
    }

    /**
     * A point of the turned page in the picture it came from. {@code turns} counts the quarter
     * turns counter-clockwise the page was given; {@code width} and {@code height} are those of
     * the original picture.
     */
    static float[] back(float x, float y, int turns, int width, int height) {
        return switch (turns & 3) {
            case 1 -> new float[] {width - y, x};
            case 2 -> new float[] {width - x, height - y};
            case 3 -> new float[] {y, height - x};
            default -> new float[] {x, y};
        };
    }

    /** Where a point of the page as it was read lies in the picture as it was given. */
    interface Back {
        float[] at(float x, float y);
    }

    /** The line with its quad in the original picture, corners still in the order of the text. */
    static TextLine back(TextLine line, Back back) {
        float[] quad = line.quad();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float[] p = back.at(quad[i * 2], quad[i * 2 + 1]);
            quad[i * 2] = p[0];
            quad[i * 2 + 1] = p[1];
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(minY);
        List<TextWord> words = new ArrayList<>(line.words().size());
        for (TextWord word : line.words()) {
            float[] corners = word.quad();
            for (int i = 0; i < 4; i++) {
                float[] p = back.at(corners[i * 2], corners[i * 2 + 1]);
                corners[i * 2] = p[0];
                corners[i * 2 + 1] = p[1];
            }
            words.add(new TextWord(word.text(), corners));
        }
        return new TextLine(line.text(), line.score(), line.detScore(), quad,
                new Rectangle(x, y, Math.max(1, (int) Math.ceil(maxX) - x), Math.max(1, (int) Math.ceil(maxY) - y)), words);
    }

    /** The table with the rectangle around its four corners in the original picture. */
    static TextTable back(TextTable table, Back back) {
        Rectangle b = table.bounds();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            float[] p = back.at(b.x + ((corner & 1) == 0 ? 0 : b.width), b.y + (corner < 2 ? 0 : b.height));
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(minY);
        return new TextTable(table.cells(), new Rectangle(x, y, (int) Math.ceil(maxX) - x, (int) Math.ceil(maxY) - y),
                table.ruled());
    }

    /**
     * By how much a small page with small print is enlarged before it is read: 1 for almost
     * every page. When the median line box is lower than a third of the height the recognizer
     * reads at, and the page is smaller than the detector's limit, the page is enlarged up to
     * that limit, at most twice. The detector then sees letters large enough to find.
     * <p>
     * Measured on the 66 pages of dev and test at half their resolution: the edits went from
     * 15,473 to 8,167, six pages better and none worse; as given, no page changed. The bound of
     * a third comes from the same pages enlarged by hand: where the median box was under 16 px
     * it took 11,159 edits to 3,872, from 16 px up it changed nothing (4,402 and 4,375). A page
     * already at the limit gains nothing, the detector would scale it down again: there a
     * higher {@code detLimitSideLen} is the answer.
     *
     * @param lineHeight the height lines are scaled to for the recognizer
     */
    static float enlarge(List<DbPost.Box> boxes, int width, int height, int detLimit, int lineHeight) {
        if (boxes.isEmpty()) {
            return 1f;
        }
        float[] heights = new float[boxes.size()];
        for (int i = 0; i < heights.length; i++) {
            float[] q = boxes.get(i).quad();
            heights[i] = Math.min(width(q), height(q));
        }
        Arrays.sort(heights);
        if (heights[heights.length / 2] >= lineHeight / 3f) {
            return 1f;
        }
        float scale = Math.min(2f, detLimit / (float) Math.max(width, height));
        return scale >= MIN_ENLARGE ? scale : 1f;
    }

    /** Enlarging by less than this is not worth a second detection. */
    static final float MIN_ENLARGE = 1.25f;

    /** Median angle of the long boxes, in radians, as {@code PageSkew} takes it from lines; 0 without a long box. */
    static double skew(List<DbPost.Box> boxes) {
        double[] angles = new double[boxes.size()];
        int count = 0;
        for (DbPost.Box box : boxes) {
            float[] q = box.quad();
            float dx = (q[2] - q[0] + q[4] - q[6]) * 0.5f;
            float dy = (q[3] - q[1] + q[5] - q[7]) * 0.5f;
            if (dx > 0f && dx >= VOTER * height(q)) {
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

    /**
     * Whether a page of this height, with its lines at {@code angle}, is levelled before it is
     * read: when a vertical gutter wanders sideways by more than a line height from the top of
     * the page to its bottom. Below that the boxes are levelled by {@code PageSkew} alone, as
     * before; above it the steps that look at the pixels for a gutter or a rule, which they take
     * to be upright, lose it. An angle beyond {@link #MAX_SKEW} is not skew.
     */
    static boolean wanders(double angle, int pageHeight, List<DbPost.Box> boxes) {
        if (boxes.isEmpty() || Math.abs(angle) > MAX_SKEW) {
            return false;
        }
        float[] heights = new float[boxes.size()];
        for (int i = 0; i < heights.length; i++) {
            float[] q = boxes.get(i).quad();
            heights[i] = Math.min(width(q), height(q));
        }
        Arrays.sort(heights);
        return pageHeight * Math.abs(Math.tan(angle)) > heights[heights.length / 2];
    }

    /**
     * 45 degrees. Further round, the boxes of the lines stand rather than lie, and the page is
     * first turned a quarter; what is left is again under 45 degrees.
     */
    static final double MAX_SKEW = Math.PI / 4;

    /**
     * A page turned level: its content turned by {@code -angle} about its centre onto a canvas
     * large enough to hold all of it.
     *
     * @param cos cosine of the turn
     * @param sin sine of the turn
     * @param width width of the page before
     * @param height height of the page before
     * @param levelWidth width of the levelled canvas
     * @param levelHeight height of the levelled canvas
     */
    record Level(double cos, double sin, int width, int height, int levelWidth, int levelHeight) {
        /** For a page whose lines run at {@code angle}. */
        static Level of(int width, int height, double angle) {
            double cos = Math.cos(-angle);
            double sin = Math.sin(-angle);
            int levelWidth = (int) Math.ceil(Math.abs(width * cos) + Math.abs(height * sin));
            int levelHeight = (int) Math.ceil(Math.abs(width * sin) + Math.abs(height * cos));
            return new Level(cos, sin, width, height, levelWidth, levelHeight);
        }

        /** A point of the levelled canvas in the page before. */
        float[] back(float x, float y) {
            double dx = x - levelWidth * 0.5;
            double dy = y - levelHeight * 0.5;
            return new float[] {(float) (cos * dx + sin * dy + width * 0.5), (float) (-sin * dx + cos * dy + height * 0.5)};
        }

        /**
         * A quad of the levelled page, in pixel indices, as the quad of the same pixels on the
         * page before: what {@code Images.turn} read them from. A pixel lies at its index plus
         * a half on the canvas that {@link #back} maps.
         *
         * @param flipped whether the levelled page got half a turn after it was levelled
         */
        float[] before(float[] quad, boolean flipped) {
            float[] out = new float[quad.length];
            for (int i = 0; i < quad.length; i += 2) {
                float x = flipped ? levelWidth - 1 - quad[i] : quad[i];
                float y = flipped ? levelHeight - 1 - quad[i + 1] : quad[i + 1];
                float[] at = back(x + 0.5f, y + 0.5f);
                out[i] = at[0] - 0.5f;
                out[i + 1] = at[1] - 0.5f;
            }
            return out;
        }

        /** A point of the page before on the levelled canvas. */
        float[] forward(float x, float y) {
            double dx = x - width * 0.5;
            double dy = y - height * 0.5;
            return new float[] {(float) (cos * dx - sin * dy + levelWidth * 0.5), (float) (sin * dx + cos * dy + levelHeight * 0.5)};
        }
    }

    /** A fan is looked for among at least this many long lines. */
    static final int FAN_VOTERS = 8;
    /** The lines run to a point at least this many page widths from the middle of the page; nearer, it is not a page seen from the side. */
    static final double FAN_NEAREST = 1.5;
    /** The slopes follow the fan when half of them lie within this share of its whole spread. */
    static final double FAN_FIT = 0.25;

    /**
     * A page photographed from the side: its lines are not parallel, they run to one point far
     * off the page, and their slope changes evenly from the top line to the bottom one. One
     * angle does not level such a page. The slope of the long boxes is fitted against their
     * height on the page (the median of the slopes between pairs of boxes, so that single
     * boxes do not move it); the page is a fan when, from the top line to the bottom one, the
     * slope changes by enough to move the end of a line of median length by more than a line
     * height, and when the boxes follow the fit.
     *
     * <p>
     * A page photographed from below or from above is the same thing turned by a quarter: its
     * lines stay level and its margins and gutters run to a point far above or below it, see
     * {@link #marginsMeet}. Either way, or both, the page is laid flat by one map.
     *
     * @return the fan, or null for a page whose lines run side by side and whose margins do too
     */
    static Fan fan(List<DbPost.Box> boxes, int width, int height) {
        double distance = linesMeet(boxes, width, height);
        double fall = marginsMeet(boxes, width, height);
        if (Double.isInfinite(distance) && Double.isInfinite(fall)) {
            return null;
        }
        return Fan.of(width, height, distance, fall, boxes, FAN_MARGIN * medianLineHeight(boxes));
    }

    private static float medianLineHeight(List<DbPost.Box> boxes) {
        float[] heights = new float[boxes.size()];
        for (int i = 0; i < heights.length; i++) {
            float[] q = boxes.get(i).quad();
            heights[i] = Math.min(width(q), height(q));
        }
        Arrays.sort(heights);
        return heights.length == 0 ? 0f : heights[heights.length / 2];
    }

    /**
     * How far from the middle of the page, along x, the lines of the page meet; infinite when
     * they run side by side.
     */
    static double linesMeet(List<DbPost.Box> boxes, int width, int height) {
        int count = 0;
        double[] slope = new double[boxes.size()];
        double[] cy = new double[boxes.size()];
        double[] length = new double[boxes.size()];
        double[] tall = new double[boxes.size()];
        for (DbPost.Box box : boxes) {
            float[] q = box.quad();
            float dx = (q[2] - q[0] + q[4] - q[6]) * 0.5f;
            float dy = (q[3] - q[1] + q[5] - q[7]) * 0.5f;
            if (dx > 0f && dx >= VOTER * height(q)) {
                slope[count] = dy / dx;
                // Where the line crosses the middle of the page: there the slopes of a fan
                // change evenly with the height, wherever on its line a box lies.
                cy[count] = (q[1] + q[3] + q[5] + q[7]) * 0.25
                        + slope[count] * (width * 0.5 - (q[0] + q[2] + q[4] + q[6]) * 0.25);
                length[count] = dx;
                tall[count] = Math.min(width(q), height(q));
                count++;
            }
        }
        if (count < FAN_VOTERS) {
            return Double.POSITIVE_INFINITY;
        }
        double lineHeight = median(tall, count);
        // Slopes between pairs of lines well apart: lines close together say nothing about a fan.
        double[] pairs = new double[count * (count - 1) / 2];
        int n = 0;
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                double apart = cy[j] - cy[i];
                if (Math.abs(apart) > 2 * lineHeight) {
                    pairs[n++] = (slope[j] - slope[i]) / apart;
                }
            }
        }
        if (n < FAN_VOTERS) {
            return Double.POSITIVE_INFINITY;
        }
        double b = median(pairs, n);
        double[] rest = new double[count];
        for (int i = 0; i < count; i++) {
            rest[i] = slope[i] - b * cy[i];
        }
        double a = median(rest, count);
        double top = Double.POSITIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            top = Math.min(top, cy[i]);
            bottom = Math.max(bottom, cy[i]);
            rest[i] = Math.abs(slope[i] - a - b * cy[i]);
        }
        double spread = Math.abs(b) * (bottom - top);
        if (spread * median(length, count) <= lineHeight || median(rest, count) > FAN_FIT * spread) {
            return Double.POSITIVE_INFINITY;
        }
        // The line that crosses the middle of the page at height y has the slope a + b y there:
        // all of them meet 1 / b to the left of the middle, where y drops out.
        if (1 / Math.abs(b) < FAN_NEAREST * width) {
            return Double.POSITIVE_INFINITY;
        }
        return -1 / b;
    }

    /** A margin is followed down at least this many lines... */
    static final int MARGIN_LINES = 6;
    /** ...over at least this many line heights... */
    static final float MARGIN_SPAN = 8f;
    /** ...from one line to the next within this many line heights down and this many across. */
    static final float MARGIN_STEP = 3f;
    static final float MARGIN_NEAR = 0.4f;
    /** The ends of its lines lie on the margin: half of them within this share of a line height. */
    static final float MARGIN_FIT = 0.15f;
    /** A lean that grows across the page shows on three margins; two may differ for any reason. */
    static final int MARGINS = 3;

    /**
     * How far from the middle of the page, along y, its margins meet; infinite when they run
     * side by side. A page photographed from below keeps its lines level, but its left margins
     * lean one way and its right margins the other, the more the further out they are: a gutter
     * is no longer a vertical strip. The margins are found from the boxes: the left ends, and
     * the right ends, of long lines that follow one another down the page on one straight
     * line. The lean of each margin is fitted against where it stands across the page (the
     * median over pairs of margins), and the page is taken for seen from below when the lean
     * changes, from the leftmost margin to the rightmost, by enough to move a margin by more
     * than a line height over the height of the page, and the margins follow the fit.
     */
    static double marginsMeet(List<DbPost.Box> boxes, int width, int height) {
        float lineHeight = medianLineHeight(boxes);
        if (boxes.size() < FAN_VOTERS || lineHeight <= 0f) {
            return Double.POSITIVE_INFINITY;
        }
        List<double[]> margins = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            List<double[]> ends = new ArrayList<>();
            for (DbPost.Box box : boxes) {
                float[] q = box.quad();
                float dx = (q[2] - q[0] + q[4] - q[6]) * 0.5f;
                if (dx >= VOTER * height(q)) {
                    double x = side == 0 ? (q[0] + q[6]) * 0.5 : (q[2] + q[4]) * 0.5;
                    double y = side == 0 ? (q[1] + q[7]) * 0.5 : (q[3] + q[5]) * 0.5;
                    ends.add(new double[] {x, y});
                }
            }
            ends.sort((a, b) -> Double.compare(a[1], b[1]));
            margins.addAll(marginsOf(ends, lineHeight, height));
        }
        if (margins.size() < MARGINS) {
            return Double.POSITIVE_INFINITY;
        }
        double leftmost = Double.POSITIVE_INFINITY;
        double rightmost = Double.NEGATIVE_INFINITY;
        for (double[] margin : margins) {
            leftmost = Math.min(leftmost, margin[0]);
            rightmost = Math.max(rightmost, margin[0]);
        }
        // Lean against place: the slopes between pairs of margins that stand well apart.
        double[] pairs = new double[margins.size() * (margins.size() - 1) / 2];
        int n = 0;
        for (int i = 0; i < margins.size(); i++) {
            for (int j = i + 1; j < margins.size(); j++) {
                double apart = margins.get(j)[0] - margins.get(i)[0];
                if (Math.abs(apart) >= 0.1 * width) {
                    pairs[n++] = (margins.get(j)[1] - margins.get(i)[1]) / apart;
                }
            }
        }
        if (n == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double c = median(pairs, n);
        double[] rest = new double[margins.size()];
        for (int i = 0; i < rest.length; i++) {
            rest[i] = margins.get(i)[1] - c * margins.get(i)[0];
        }
        double common = median(rest, rest.length);
        for (int i = 0; i < rest.length; i++) {
            rest[i] = Math.abs(margins.get(i)[1] - common - c * margins.get(i)[0]);
        }
        double spread = Math.abs(c) * (rightmost - leftmost);
        if (spread * height <= lineHeight || median(rest, rest.length) > FAN_FIT * spread) {
            return Double.POSITIVE_INFINITY;
        }
        if (1 / Math.abs(c) < FAN_NEAREST * height) {
            return Double.POSITIVE_INFINITY;
        }
        // The margin at x leans by common + c x: all of them meet 1 / c above the middle.
        return -1 / c;
    }

    /**
     * The margins among {@code ends}, the left or the right ends of lines sorted down the page:
     * each as where it crosses the middle height of the page and its lean, x per y.
     */
    private static List<double[]> marginsOf(List<double[]> ends, float lineHeight, int height) {
        List<double[]> margins = new ArrayList<>();
        boolean[] used = new boolean[ends.size()];
        for (int first = 0; first < ends.size(); first++) {
            if (used[first]) {
                continue;
            }
            List<double[]> chain = new ArrayList<>();
            chain.add(ends.get(first));
            used[first] = true;
            int last = first;
            while (true) {
                double[] from = ends.get(last);
                // Where the chain is heading, once it has a few points.
                double heading = 0;
                if (chain.size() >= 4) {
                    double[] back = chain.get(chain.size() - 4);
                    if (from[1] > back[1]) {
                        heading = (from[0] - back[0]) / (from[1] - back[1]);
                    }
                }
                int next = -1;
                for (int j = last + 1; j < ends.size(); j++) {
                    if (used[j]) {
                        continue;
                    }
                    double down = ends.get(j)[1] - from[1];
                    if (down < 0.5 * lineHeight) {
                        continue;
                    }
                    if (down > MARGIN_STEP * lineHeight) {
                        break;
                    }
                    if (Math.abs(ends.get(j)[0] - (from[0] + heading * down)) <= MARGIN_NEAR * lineHeight) {
                        next = j;
                        break;
                    }
                }
                if (next < 0) {
                    break;
                }
                chain.add(ends.get(next));
                used[next] = true;
                last = next;
            }
            double span = chain.get(chain.size() - 1)[1] - chain.get(0)[1];
            if (chain.size() < MARGIN_LINES || span < MARGIN_SPAN * lineHeight) {
                continue;
            }
            // Least squares: x against y.
            double meanX = 0;
            double meanY = 0;
            for (double[] point : chain) {
                meanX += point[0] / chain.size();
                meanY += point[1] / chain.size();
            }
            double up = 0;
            double down = 0;
            for (double[] point : chain) {
                up += (point[1] - meanY) * (point[0] - meanX);
                down += (point[1] - meanY) * (point[1] - meanY);
            }
            double lean = up / down;
            double[] off = new double[chain.size()];
            for (int i = 0; i < off.length; i++) {
                off[i] = Math.abs(chain.get(i)[0] - meanX - lean * (chain.get(i)[1] - meanY));
            }
            if (median(off, off.length) <= MARGIN_FIT * lineHeight) {
                margins.add(new double[] {meanX + lean * (height * 0.5 - meanY), lean});
            }
        }
        return margins;
    }

    /**
     * The flat page is cut to the text, with this many line heights around it. A page seen from
     * the side fills less of its picture than one seen from above, and on a canvas that held the
     * whole picture it would be smaller still: the detector, which scales the canvas to its
     * limit, then drew the lines of two columns as one (a three-column weekly: 1,015 edits on
     * the whole canvas, 56 cut to its text).
     */
    static final float FAN_MARGIN = 3f;

    private static double median(double[] values, int count) {
        double[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        return (count & 1) == 1 ? sorted[count / 2] : (sorted[count / 2 - 1] + sorted[count / 2]) * 0.5;
    }

    /**
     * A page whose lines run to one point, or whose margins do, or both, laid flat: the map that
     * sends those points to infinity, so that lines and margins come out parallel, with the
     * scale of the page kept at its middle. The flat page holds the text of the page and a
     * margin, see {@link #FAN_MARGIN}. Its lines still share one slope, that of the line through
     * the middle; levelling takes it out.
     *
     * @param distance from the middle of the page to the point the lines run to, along x,
     *     signed; infinite when they run side by side
     * @param fall from the middle of the page to the point the margins run to, along y, signed;
     *     infinite when they run side by side
     * @param width width of the page before
     * @param height height of the page before
     * @param left x of the flat page's left edge, from the middle of the page
     * @param top y of the flat page's top edge, from the middle of the page
     * @param flatWidth width of the flat page
     * @param flatHeight height of the flat page
     */
    record Fan(double distance, double fall, int width, int height, double left, double top, int flatWidth, int flatHeight) {
        /**
         * For a page of this size; the flat page holds {@code boxes} with {@code margin} around
         * them, and nothing beyond the picture.
         */
        static Fan of(int width, int height, double distance, double fall, List<DbPost.Box> boxes, float margin) {
            double[] frame = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int corner = 0; corner < 4; corner++) {
                widen(frame, (corner & 1) == 0 ? 0 : width, (corner & 2) == 0 ? 0 : height, width, height, distance, fall);
            }
            double[] text = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (DbPost.Box box : boxes) {
                float[] q = box.quad();
                for (int i = 0; i < 8; i += 2) {
                    widen(text, q[i], q[i + 1], width, height, distance, fall);
                }
            }
            if (boxes.isEmpty()) {
                text = frame;
            }
            double left = Math.max(frame[0], text[0] - margin);
            double right = Math.min(frame[1], text[1] + margin);
            double top = Math.max(frame[2], text[2] - margin);
            double bottom = Math.min(frame[3], text[3] + margin);
            return new Fan(distance, fall, width, height, left, top, (int) Math.ceil(right - left), (int) Math.ceil(bottom - top));
        }

        /** Takes the point {@code (x, y)} of the page into the bounds {@code u0, u1, v0, v1} of the flat page, from its middle. */
        private static void widen(double[] bounds, double x, double y, int width, int height, double distance, double fall) {
            double dx = x - width * 0.5;
            double dy = y - height * 0.5;
            double den = 1 - dx / distance - dy / fall;
            bounds[0] = Math.min(bounds[0], dx / den);
            bounds[1] = Math.max(bounds[1], dx / den);
            bounds[2] = Math.min(bounds[2], dy / den);
            bounds[3] = Math.max(bounds[3], dy / den);
        }

        /** A point of the flat page in the page before. */
        float[] back(float x, float y) {
            double u = x + left;
            double v = y + top;
            double den = 1 + u / distance + v / fall;
            return new float[] {(float) (u / den + width * 0.5), (float) (v / den + height * 0.5)};
        }

        /** A point of the page before on the flat page. */
        float[] forward(float x, float y) {
            double dx = x - width * 0.5;
            double dy = y - height * 0.5;
            double den = 1 - dx / distance - dy / fall;
            return new float[] {(float) (dx / den - left), (float) (dy / den - top)};
        }

        /** A quad of the flat page, in pixel indices, as the quad of the same pixels on the page before. */
        float[] before(float[] quad) {
            float[] out = new float[quad.length];
            for (int i = 0; i < quad.length; i += 2) {
                float[] at = back(quad[i] + 0.5f, quad[i + 1] + 0.5f);
                out[i] = at[0] - 0.5f;
                out[i + 1] = at[1] - 0.5f;
            }
            return out;
        }
    }

    /** Degrees clockwise that set the original picture upright, for a page turned {@code turns} quarters counter-clockwise. */
    static int rotation(int turns) {
        return ((4 - (turns & 3)) & 3) * 90;
    }
}
