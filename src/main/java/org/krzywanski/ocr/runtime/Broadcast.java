package org.krzywanski.ocr.runtime;

import org.krzywanski.ocr.OcrException;

import java.util.Arrays;

final class Broadcast {
    private Broadcast() {
    }

    static int dimAt(int[] dims, int rank, int axis) {
        int offset = rank - dims.length;
        if (axis < offset) {
            return 1;
        }
        return dims[axis - offset];
    }

    static int[] shape(int[] a, int[] b) {
        int rank = Math.max(a.length, b.length);
        int[] out = new int[rank];
        for (int i = 0; i < rank; i++) {
            int ad = dimAt(a, rank, i);
            int bd = dimAt(b, rank, i);
            if (ad != bd && ad != 1 && bd != 1) {
                throw new OcrException("cannot broadcast " + Arrays.toString(a) + " and " + Arrays.toString(b));
            }
            out[i] = Math.max(ad, bd);
        }
        return out;
    }

    /** Stride of {@code src} aligned to the right of {@code outRank}, 0 where the axis is broadcast. */
    static int[] strides(int[] src, int outRank) {
        int[] st = new int[outRank];
        int acc = 1;
        for (int i = outRank - 1; i >= 0; i--) {
            int actual = i - (outRank - src.length);
            if (actual < 0 || src[actual] == 1) {
                st[i] = 0;
            } else {
                st[i] = acc;
                acc *= src[actual];
            }
        }
        return st;
    }

    static int offset(int[] coord, int[] strides) {
        int off = 0;
        for (int i = 0; i < coord.length; i++) {
            off += coord[i] * strides[i];
        }
        return off;
    }

    static boolean sameShape(int[] a, int[] b) {
        return Arrays.equals(a, b);
    }

    /**
     * Length of the contiguous suffix of {@code big} that {@code small} repeats.
     * -1 when the broadcast is not that kind of repeat, including a same-shape pair.
     */
    static int trailingTile(int[] small, int[] big) {
        int inner = 1;
        int si = small.length - 1;
        int bi = big.length - 1;
        while (bi >= 0 && si >= 0 && small[si] == big[bi]) {
            inner *= big[bi];
            si--;
            bi--;
        }
        while (si >= 0) {
            if (small[si] != 1) {
                return -1;
            }
            si--;
        }
        if (inner <= 1 || inner == Tensor.numel(big)) {
            return -1;
        }
        return inner;
    }

    /** True when {@code small} is a per-channel vector for NCHW {@code big}. */
    static boolean nchwChannel(int[] small, int[] big) {
        if (big.length != 4 || Tensor.numel(small) != big[1]) {
            return false;
        }
        int rank = 4;
        for (int i = 0; i < rank; i++) {
            int sd = dimAt(small, rank, i);
            int bd = big[i];
            if (i == 1) {
                if (sd != bd) {
                    return false;
                }
            } else if (sd != 1) {
                return false;
            }
        }
        return true;
    }
}
