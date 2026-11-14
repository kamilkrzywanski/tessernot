package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.image.Bgr;
import io.github.kamilkrzywanski.ocr.internal.post.DbPost;

import java.awt.Rectangle;
import java.util.ArrayList;
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
 * middle. After reading, every
 * quad goes back into the coordinates of the original picture, with its corners still in the
 * order of the text: top left of the line first.
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

    /** The line with its quad in the original picture, corners still in the order of the text. */
    static TextLine back(TextLine line, int turns, int width, int height) {
        float[] quad = line.quad();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float[] p = back(quad[i * 2], quad[i * 2 + 1], turns, width, height);
            quad[i * 2] = p[0];
            quad[i * 2 + 1] = p[1];
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(minY);
        return new TextLine(line.text(), line.score(), line.detScore(), quad,
                new Rectangle(x, y, Math.max(1, (int) Math.ceil(maxX) - x), Math.max(1, (int) Math.ceil(maxY) - y)));
    }

    /** The table with its rectangle in the original picture. */
    static TextTable back(TextTable table, int turns, int width, int height) {
        Rectangle b = table.bounds();
        float[] a = back(b.x, b.y, turns, width, height);
        float[] c = back(b.x + b.width, b.y + b.height, turns, width, height);
        int x = (int) Math.floor(Math.min(a[0], c[0]));
        int y = (int) Math.floor(Math.min(a[1], c[1]));
        int w = (int) Math.ceil(Math.max(a[0], c[0])) - x;
        int h = (int) Math.ceil(Math.max(a[1], c[1])) - y;
        return new TextTable(table.cells(), new Rectangle(x, y, w, h), table.ruled());
    }

    /** Degrees clockwise that set the original picture upright, for a page turned {@code turns} quarters counter-clockwise. */
    static int rotation(int turns) {
        return ((4 - (turns & 3)) & 3) * 90;
    }
}
