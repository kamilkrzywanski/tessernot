package org.krzywanski.ocr;

import org.krzywanski.ocr.image.Bgr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A printed Ż whose caron has melted into the top bar still shows a light gap in that bar.
 * A 5×5 dot above such a Z is enough for the recognizer to emit ż. The dot is not painted
 * on a solid bar, and the caller keeps the new line only when that diacritic is the only change.
 */
final class NotchedZ {
    private NotchedZ() {
    }

    /**
     * The crop with one dot above every notched {@code Z}, or null when none aligns.
     * {@code raw} is the first read of this crop, before the lexicon.
     */
    static Bgr paint(Bgr crop, String raw) {
        if (crop == null || raw == null || raw.indexOf('Z') < 0) {
            return null;
        }
        List<int[]> runs = runs(crop);
        int glyphs = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) != ' ') {
                glyphs++;
            }
        }
        if (glyphs != runs.size() || glyphs == 0) {
            return null;
        }
        List<int[]> targets = new ArrayList<>();
        int run = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ' ') {
                continue;
            }
            if (c == 'Z' && valley(crop, runs.get(run)[0], runs.get(run)[1])) {
                targets.add(runs.get(run));
            }
            run++;
        }
        if (targets.isEmpty()) {
            return null;
        }
        int extra = crop.height() > 80 ? 24 : 16;
        int w = crop.width();
        int h = crop.height();
        byte[] out = new byte[w * (h + extra) * 3];
        Arrays.fill(out, (byte) 255);
        byte[] in = crop.data();
        for (int y = 0; y < h; y++) {
            System.arraycopy(in, y * w * 3, out, (y + extra) * w * 3, w * 3);
        }
        for (int[] span : targets) {
            int top = glyphTop(crop, span[0], span[1]);
            if (top < 0) {
                continue;
            }
            int cx = (span[0] + span[1]) / 2;
            int cy = extra + top - 8;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    ink(out, w, h + extra, cx + dx, cy + dy);
                }
            }
        }
        return new Bgr(out, w, h + extra);
    }

    /**
     * The first line, with an uppercase Z allowed to take ż or ź from the second.
     * Any other difference keeps the first line.
     */
    static String keep(String base, String next) {
        if (base == null || next == null || base.length() != next.length()) {
            return null;
        }
        StringBuilder out = new StringBuilder(base.length());
        boolean changed = false;
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            char d = next.charAt(i);
            if (c == d) {
                out.append(c);
                continue;
            }
            if (c == 'Z' && (d == 'Ż' || d == 'ż' || d == 'Ź' || d == 'ź')) {
                out.append('Ż');
                changed = true;
                continue;
            }
            return null;
        }
        return changed ? out.toString() : null;
    }

    /** Light gap inside the first dark row, with ink on both sides. */
    private static boolean valley(Bgr src, int x0, int x1) {
        int top = glyphTop(src, x0, x1);
        if (top < 0) {
            return false;
        }
        byte[] data = src.data();
        int w = src.width();
        for (int row = top; row <= Math.min(src.height() - 1, top + 1); row++) {
            int left = -1;
            int right = -1;
            for (int x = x0; x <= x1; x++) {
                if (luma(data, (row * w + x) * 3) < 110) {
                    if (left < 0) {
                        left = x;
                    }
                    right = x;
                }
            }
            if (right - left < 8) {
                continue;
            }
            int gap = 0;
            int best = 0;
            for (int x = left; x <= right; x++) {
                if (luma(data, (row * w + x) * 3) > 140) {
                    gap++;
                    best = Math.max(best, gap);
                } else {
                    gap = 0;
                }
            }
            if (best >= 2) {
                return true;
            }
        }
        return false;
    }

    private static List<int[]> runs(Bgr src) {
        int w = src.width();
        int h = src.height();
        byte[] data = src.data();
        int quiet = Math.max(2, h / 40);
        boolean[] ink = new boolean[w];
        for (int x = 0; x < w; x++) {
            int count = 0;
            for (int y = 0; y < h; y++) {
                if (luma(data, (y * w + x) * 3) < 150) {
                    count++;
                }
            }
            ink[x] = count >= quiet;
        }
        List<int[]> runs = new ArrayList<>();
        int x = 0;
        while (x < w) {
            while (x < w && !ink[x]) {
                x++;
            }
            if (x >= w) {
                break;
            }
            int start = x;
            while (x < w && ink[x]) {
                x++;
            }
            if (x + 1 < w && !ink[x] && ink[x + 1] && x - start > 4) {
                ink[x] = true;
                continue;
            }
            if (x - start >= 8) {
                runs.add(new int[] {start, x - 1});
            }
        }
        return runs;
    }

    private static int glyphTop(Bgr src, int x0, int x1) {
        byte[] data = src.data();
        int w = src.width();
        for (int y = 0; y < src.height(); y++) {
            for (int x = x0; x <= x1; x++) {
                if (luma(data, (y * w + x) * 3) < 160) {
                    return y;
                }
            }
        }
        return -1;
    }

    private static void ink(byte[] out, int w, int h, int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return;
        }
        int o = (y * w + x) * 3;
        out[o] = out[o + 1] = out[o + 2] = 0;
    }

    private static int luma(byte[] data, int i) {
        return ((data[i + 2] & 255) * 54 + (data[i + 1] & 255) * 183 + (data[i] & 255) * 19) >> 8;
    }
}
