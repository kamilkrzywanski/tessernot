package org.krzywanski.ocr.post;

import org.krzywanski.ocr.OcrException;
import org.krzywanski.ocr.image.Geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Differentiable-binarization box extraction.
 * A 2x2 dilation uses the OpenCV default anchor {@code (0, 0)}, so foreground grows toward the top-left.
 * The fast score is the mean probability inside the min-area rectangle, taken before the unclip expansion.
 */
public final class DbPost {
    public record Box(float[] quad, float score) {
        public Box {
            quad = quad == null ? new float[8] : quad.clone();
        }

        public float centerX() {
            return (quad[0] + quad[2] + quad[4] + quad[6]) * 0.25f;
        }

        public float centerY() {
            return (quad[1] + quad[3] + quad[5] + quad[7]) * 0.25f;
        }
    }

    private static final int MIN_SIDE = 3;

    private DbPost() {
    }

    public static void asProbability(float[] map) {
        if (map.length == 0) {
            return;
        }
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (float v : map) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (min < 0f || max > 1.2f) {
            for (int i = 0; i < map.length; i++) {
                map[i] = sigmoid(map[i]);
            }
        }
    }

    public static List<Box> find(
            float[] prob,
            int width,
            int height,
            float thresh,
            float boxThresh,
            float unclipRatio,
            boolean dilate,
            int maxCandidates
    ) {
        if (prob == null) {
            throw new OcrException("bad detection map " + width + "x" + height);
        }
        // A wrapped int product used to look like an empty map and return no boxes.
        int pixels = mapLength(width, height, prob.length);
        byte[] mask = new byte[pixels];
        for (int i = 0; i < mask.length; i++) {
            if (prob[i] > thresh) {
                mask[i] = 1;
            }
        }
        if (dilate) {
            mask = dilate2x2(mask, width, height);
        }
        // 8-connected scanline. mask is 0 background, 1 unvisited, 2 already owned by a component.
        // A span seed expands across a whole horizontal run, so the queue holds spans rather than pixels.
        SpanQueue queue = new SpanQueue();
        int[] xs = new int[64];
        int[] ys = new int[64];
        List<Box> boxes = new ArrayList<>();
        int considered = 0;
        for (int start = 0; start < mask.length && considered < maxCandidates; start++) {
            if (mask[start] != 1) {
                continue;
            }
            considered++;
            queue.n = 0;
            queue.add(start % width, start / width);
            int count = 0;
            int head = 0;
            while (head < queue.n) {
                int sx = queue.xs[head];
                int sy = queue.ys[head];
                head++;
                int row = sy * width;
                if (mask[row + sx] != 1) {
                    continue;
                }
                int left = sx;
                while (left > 0 && mask[row + left - 1] == 1) {
                    left--;
                }
                int right = sx;
                while (right + 1 < width && mask[row + right + 1] == 1) {
                    right++;
                }
                for (int x = left; x <= right; x++) {
                    mask[row + x] = 2;
                }
                boolean edgeY = sy == 0 || sy == height - 1;
                for (int x = left; x <= right; x++) {
                    // edgeY is checked first so the vertical loads stay inside the mask.
                    boolean boundary = x == left || x == right || edgeY
                            || mask[row - width + x] == 0
                            || mask[row + width + x] == 0;
                    if (!boundary) {
                        continue;
                    }
                    if (count == xs.length) {
                        xs = Arrays.copyOf(xs, xs.length * 2);
                        ys = Arrays.copyOf(ys, ys.length * 2);
                    }
                    xs[count] = x;
                    ys[count] = sy;
                    count++;
                }
                if (sy > 0) {
                    pushSpans(mask, queue, sy - 1, left, right, width);
                }
                if (sy + 1 < height) {
                    pushSpans(mask, queue, sy + 1, left, right, width);
                }
            }
            if (count < 3) {
                continue;
            }
            Geometry.Rect rect = Geometry.minAreaRect(Arrays.copyOf(xs, count), Arrays.copyOf(ys, count));
            if (rect == null || Math.min(rect.width, rect.height) < MIN_SIDE) {
                continue;
            }
            float score = Geometry.polygonScore(prob, width, height, rect.quad);
            if (!(score >= boxThresh)) {
                continue;
            }
            double distance = rect.width * rect.height * unclipRatio / (2.0 * (rect.width + rect.height));
            double grownW = rect.width + 2 * distance;
            double grownH = rect.height + 2 * distance;
            if (Math.min(grownW, grownH) < MIN_SIDE + 2) {
                continue;
            }
            boxes.add(new Box(rect.expand(unclipRatio), score));
        }
        return boxes;
    }

    /**
     * Pixel count that fits in {@code values}.
     * The product is computed in {@code long}, so a wrapped {@code int} is not an empty map.
     */
    public static int mapLength(int width, int height, int values) {
        if (width < 0 || height < 0 || values < 0) {
            throw new OcrException("bad detection map " + width + "x" + height);
        }
        long pixels = (long) width * (long) height;
        if (pixels > values) {
            throw new OcrException("bad detection map " + width + "x" + height);
        }
        return (int) pixels;
    }

    /** Seeds on row {@code y} whose run touches {@code [left - 1, right + 1]} (8-connectivity). */
    private static void pushSpans(byte[] mask, SpanQueue queue, int y, int left, int right, int width) {
        int row = y * width;
        int x = Math.max(0, left - 1);
        int end = Math.min(width - 1, right + 1);
        while (x <= end) {
            while (x <= end && mask[row + x] != 1) {
                x++;
            }
            if (x > end) {
                break;
            }
            queue.add(x, y);
            while (x <= end && mask[row + x] != 0) {
                x++;
            }
        }
    }

    private static final class SpanQueue {
        private int[] xs = new int[256];
        private int[] ys = new int[256];
        private int n;

        void add(int x, int y) {
            if (n == xs.length) {
                xs = Arrays.copyOf(xs, n * 2);
                ys = Arrays.copyOf(ys, n * 2);
            }
            xs[n] = x;
            ys[n] = y;
            n++;
        }
    }

    /** {@code dst(x,y) = max(src(x,y), src(x+1,y), src(x,y+1), src(x+1,y+1))} with constant-0 borders. */
    static byte[] dilate2x2(byte[] src, int width, int height) {
        byte[] dst = new byte[src.length];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            int next = y + 1 < height ? row + width : -1;
            for (int x = 0; x < width; x++) {
                int v = src[row + x];
                if (x + 1 < width) {
                    v = Math.max(v, src[row + x + 1]);
                }
                if (next >= 0) {
                    v = Math.max(v, src[next + x]);
                    if (x + 1 < width) {
                        v = Math.max(v, src[next + x + 1]);
                    }
                }
                dst[row + x] = (byte) v;
            }
        }
        return dst;
    }

    private static float sigmoid(float x) {
        if (x >= 0f) {
            float z = (float) Math.exp(-x);
            return 1f / (1f + z);
        }
        float z = (float) Math.exp(x);
        return z / (1f + z);
    }
}
