package org.krzywanski.ocr.image;

import org.krzywanski.ocr.OcrException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Quads, min-area rectangles and perspective crops. Points are pixel coordinates, y down. */
public final class Geometry {
    private Geometry() {
    }

    public static final class Rect {
        public final float[] quad;
        public final double cx;
        public final double cy;
        public final double ux;
        public final double uy;
        public final double vx;
        public final double vy;
        public final double width;
        public final double height;

        Rect(float[] quad, double cx, double cy, double ux, double uy, double vx, double vy, double width, double height) {
            this.quad = quad;
            this.cx = cx;
            this.cy = cy;
            this.ux = ux;
            this.uy = uy;
            this.vx = vx;
            this.vy = vy;
            this.width = width;
            this.height = height;
        }

        public float[] expand(double unclipRatio) {
            double denom = 2.0 * (width + height);
            if (denom <= 1e-6) {
                return quad.clone();
            }
            double distance = width * height * unclipRatio / denom;
            return corners(cx, cy, ux, uy, vx, vy, width + 2 * distance, height + 2 * distance);
        }
    }

    public static Rect minAreaRect(int[] xs, int[] ys) {
        int n = xs.length;
        if (n < 3) {
            return null;
        }
        int[] order = convexHull(xs, ys);
        if (order.length < 3) {
            return null;
        }
        double best = Double.POSITIVE_INFINITY;
        Rect winner = null;
        int h = order.length;
        for (int i = 0; i < h; i++) {
            int i0 = order[i];
            int i1 = order[(i + 1) % h];
            double ex = xs[i1] - xs[i0];
            double ey = ys[i1] - ys[i0];
            double len = Math.hypot(ex, ey);
            if (len < 1e-6) {
                continue;
            }
            ex /= len;
            ey /= len;
            double px = -ey;
            double py = ex;
            double minE = Double.POSITIVE_INFINITY;
            double maxE = Double.NEGATIVE_INFINITY;
            double minP = Double.POSITIVE_INFINITY;
            double maxP = Double.NEGATIVE_INFINITY;
            for (int id : order) {
                double e = xs[id] * ex + ys[id] * ey;
                double p = xs[id] * px + ys[id] * py;
                minE = Math.min(minE, e);
                maxE = Math.max(maxE, e);
                minP = Math.min(minP, p);
                maxP = Math.max(maxP, p);
            }
            double width = maxE - minE;
            double height = maxP - minP;
            double area = width * height;
            if (area < best) {
                best = area;
                double midE = (minE + maxE) / 2.0;
                double midP = (minP + maxP) / 2.0;
                double cx = midE * ex + midP * px;
                double cy = midE * ey + midP * py;
                float[] quad = corners(cx, cy, ex, ey, px, py, width, height);
                winner = new Rect(quad, cx, cy, ex, ey, px, py, width, height);
            }
        }
        return winner;
    }

    /** Paddle {@code get_mini_boxes} order: TL, TR, BR, BL with image y pointing down. */
    public static float[] orderPoints(float[] points) {
        Integer[] id = {0, 1, 2, 3};
        Arrays.sort(id, Comparator.<Integer>comparingDouble(i -> points[i * 2]).thenComparingDouble(i -> points[i * 2 + 1]));
        int l0 = id[0];
        int l1 = id[1];
        int r0 = id[2];
        int r1 = id[3];
        int tl = points[l0 * 2 + 1] <= points[l1 * 2 + 1] ? l0 : l1;
        int bl = tl == l0 ? l1 : l0;
        int tr = points[r0 * 2 + 1] <= points[r1 * 2 + 1] ? r0 : r1;
        int br = tr == r0 ? r1 : r0;
        return new float[]{
                points[tl * 2], points[tl * 2 + 1],
                points[tr * 2], points[tr * 2 + 1],
                points[br * 2], points[br * 2 + 1],
                points[bl * 2], points[bl * 2 + 1]
        };
    }

    public static float polygonScore(float[] prob, int width, int height, float[] quad) {
        float minX = quad[0], maxX = quad[0], minY = quad[1], maxY = quad[1];
        for (int i = 1; i < 4; i++) {
            minX = Math.min(minX, quad[i * 2]);
            maxX = Math.max(maxX, quad[i * 2]);
            minY = Math.min(minY, quad[i * 2 + 1]);
            maxY = Math.max(maxY, quad[i * 2 + 1]);
        }
        int y0 = Math.max(0, (int) Math.floor(minY));
        int y1 = Math.min(height - 1, (int) Math.ceil(maxY));
        int x0 = Math.max(0, (int) Math.floor(minX));
        int x1 = Math.min(width - 1, (int) Math.ceil(maxX));
        double sum = 0;
        int count = 0;
        double[] hits = new double[8];
        for (int y = y0; y <= y1; y++) {
            double yy = y + 0.5;
            int n = 0;
            for (int e = 0; e < 4; e++) {
                int ne = (e + 1) & 3;
                double ya = quad[e * 2 + 1];
                double yb = quad[ne * 2 + 1];
                if ((ya <= yy && yb > yy) || (yb <= yy && ya > yy)) {
                    double t = (yy - ya) / (yb - ya);
                    hits[n++] = quad[e * 2] + t * (quad[ne * 2] - quad[e * 2]);
                }
            }
            Arrays.sort(hits, 0, n);
            for (int i = 0; i + 1 < n; i += 2) {
                int xa = (int) Math.ceil(hits[i]);
                int xb = (int) Math.floor(hits[i + 1]);
                if (xa < x0) {
                    xa = x0;
                }
                if (xb > x1) {
                    xb = x1;
                }
                for (int x = xa; x <= xb; x++) {
                    sum += prob[y * width + x];
                    count++;
                }
            }
        }
        return count == 0 ? 0f : (float) (sum / count);
    }

    public static Bgr warp(Bgr image, float[] quad, int dw, int dh) {
        double[] homography = homography(dw, dh, quad);
        if (homography == null) {
            return null;
        }
        if (dw == 0 || dh == 0) {
            return new Bgr(new byte[0], dw, dh);
        }
        int sw = image.width();
        int sh = image.height();
        byte[] src = image.data();
        byte[] out = new byte[Images.packedBytes(dw, dh)];
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                double den = homography[6] * x + homography[7] * y + homography[8];
                if (Math.abs(den) < 1e-8) {
                    continue;
                }
                float sx = (float) ((homography[0] * x + homography[1] * y + homography[2]) / den);
                float sy = (float) ((homography[3] * x + homography[4] * y + homography[5]) / den);
                int o = (y * dw + x) * 3;
                for (int c = 0; c < 3; c++) {
                    out[o + c] = (byte) Math.round(sample(src, sw, sh, sx, sy, c));
                }
            }
        }
        return new Bgr(out, dw, dh);
    }

    public static double distance(float x0, float y0, float x1, float y1) {
        double dx = x0 - x1;
        double dy = y0 - y1;
        return Math.hypot(dx, dy);
    }

    private static float[] corners(double cx, double cy, double ux, double uy, double vx, double vy, double width, double height) {
        double hw = width / 2.0;
        double hh = height / 2.0;
        float[] raw = new float[8];
        double[] sx = {-1, 1, 1, -1};
        double[] sy = {-1, -1, 1, 1};
        for (int i = 0; i < 4; i++) {
            raw[i * 2] = (float) (cx + sx[i] * hw * ux + sy[i] * hh * vx);
            raw[i * 2 + 1] = (float) (cy + sx[i] * hw * uy + sy[i] * hh * vy);
        }
        return orderPoints(raw);
    }

    private static int[] convexHull(int[] xs, int[] ys) {
        int n = xs.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> xs[a] == xs[b] ? Integer.compare(ys[a], ys[b]) : Integer.compare(xs[a], xs[b]));
        List<Integer> unique = new ArrayList<>();
        for (int id : idx) {
            if (!unique.isEmpty()) {
                int prev = unique.get(unique.size() - 1);
                if (xs[prev] == xs[id] && ys[prev] == ys[id]) {
                    continue;
                }
            }
            unique.add(id);
        }
        if (unique.size() < 3) {
            int[] out = new int[unique.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = unique.get(i);
            }
            return out;
        }
        List<Integer> lower = new ArrayList<>();
        List<Integer> upper = new ArrayList<>();
        for (int id : unique) {
            while (lower.size() >= 2 && cross(xs, ys, lower.get(lower.size() - 2), lower.get(lower.size() - 1), id) <= 0) {
                lower.remove(lower.size() - 1);
            }
            lower.add(id);
        }
        for (int i = unique.size() - 1; i >= 0; i--) {
            int id = unique.get(i);
            while (upper.size() >= 2 && cross(xs, ys, upper.get(upper.size() - 2), upper.get(upper.size() - 1), id) <= 0) {
                upper.remove(upper.size() - 1);
            }
            upper.add(id);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        lower.addAll(upper);
        int[] out = new int[lower.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = lower.get(i);
        }
        return out;
    }

    private static long cross(int[] xs, int[] ys, int o, int a, int b) {
        return (long) (xs[a] - xs[o]) * (ys[b] - ys[o]) - (long) (ys[a] - ys[o]) * (xs[b] - xs[o]);
    }

    private static double[] homography(int dw, int dh, float[] quad) {
        double[] xs = {0, dw, dw, 0};
        double[] ys = {0, 0, dh, dh};
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = xs[i];
            double y = ys[i];
            double u = quad[i * 2];
            double v = quad[i * 2 + 1];
            a[i * 2][0] = x;
            a[i * 2][1] = y;
            a[i * 2][2] = 1;
            a[i * 2][6] = -x * u;
            a[i * 2][7] = -y * u;
            a[i * 2][8] = u;
            a[i * 2 + 1][3] = x;
            a[i * 2 + 1][4] = y;
            a[i * 2 + 1][5] = 1;
            a[i * 2 + 1][6] = -x * v;
            a[i * 2 + 1][7] = -y * v;
            a[i * 2 + 1][8] = v;
        }
        for (int col = 0; col < 8; col++) {
            int pivot = col;
            for (int row = col + 1; row < 8; row++) {
                if (Math.abs(a[row][col]) > Math.abs(a[pivot][col])) {
                    pivot = row;
                }
            }
            double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            double div = a[col][col];
            if (Math.abs(div) < 1e-12) {
                return null;
            }
            for (int c = col; c < 9; c++) {
                a[col][c] /= div;
            }
            for (int row = 0; row < 8; row++) {
                if (row == col) {
                    continue;
                }
                double factor = a[row][col];
                if (factor == 0) {
                    continue;
                }
                for (int c = col; c < 9; c++) {
                    a[row][c] -= factor * a[col][c];
                }
            }
        }
        double[] h = new double[9];
        for (int i = 0; i < 8; i++) {
            h[i] = a[i][8];
        }
        h[8] = 1;
        return h;
    }

    private static float sample(byte[] src, int w, int h, float sx, float sy, int c) {
        int x0 = (int) Math.floor(sx);
        int y0 = (int) Math.floor(sy);
        float fx = sx - x0;
        float fy = sy - y0;
        float v00 = at(src, w, h, x0, y0, c);
        float v10 = at(src, w, h, x0 + 1, y0, c);
        float v01 = at(src, w, h, x0, y0 + 1, c);
        float v11 = at(src, w, h, x0 + 1, y0 + 1, c);
        return (1f - fx) * (1f - fy) * v00 + fx * (1f - fy) * v10 + (1f - fx) * fy * v01 + fx * fy * v11;
    }

    private static float at(byte[] src, int w, int h, int x, int y, int c) {
        if (w <= 0 || h <= 0) {
            return 0f;
        }
        if (x < 0) {
            x = 0;
        } else if (x >= w) {
            x = w - 1;
        }
        if (y < 0) {
            y = 0;
        } else if (y >= h) {
            y = h - 1;
        }
        return src[(y * w + x) * 3 + c] & 0xff;
    }

    static void requireQuad(float[] quad) {
        if (quad == null || quad.length != 8) {
            throw new OcrException("quad must have 8 values");
        }
    }
}
