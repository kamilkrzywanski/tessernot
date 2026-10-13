package io.github.kamilkrzywanski.ocr.internal.runtime;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.util.Arrays;

final class ElementOps {
    private ElementOps() {
    }

    static Tensor binary(Tensor a, Tensor b, int op, Run run) {
        if (a.dtype != b.dtype) {
            throw new OcrException("binary dtype mismatch " + a.shapeString() + " vs " + b.shapeString());
        }
        int[] shape = Broadcast.shape(a.dims, b.dims);
        if (a.dtype == Tensor.I64) {
            return binaryLong(a, b, op, shape, run);
        }
        int n = Tensor.numel(shape);
        float[] out = run.arena.allocF(n);
        if (Broadcast.sameShape(a.dims, b.dims)) {
            Blas.binary(a.f, 0, b.f, 0, out, 0, n, op, run.vector, run.pool, run.threads);
        } else if (b.numel() == 1 && n == a.numel()) {
            Blas.scalarRight(a.f, 0, b.f[0], out, 0, n, op, run.vector, run.pool, run.threads);
        } else if (a.numel() == 1 && n == b.numel()) {
            Blas.scalarLeft(a.f[0], b.f, 0, out, 0, n, op, run.vector, run.pool, run.threads);
        } else if (Broadcast.nchwChannel(b.dims, a.dims) && Arrays.equals(shape, a.dims)) {
            channel(a.f, b.f, out, a.dims, op, false, run.vector);
        } else if (Broadcast.nchwChannel(a.dims, b.dims) && Arrays.equals(shape, b.dims)) {
            channel(b.f, a.f, out, b.dims, op, true, run.vector);
        } else if (!trailingTile(a, b, out, shape, op, run)) {
            generalFloat(a.f, a.dims, b.f, b.dims, out, shape, op);
        }
        return Tensor.pooledF32(shape, out);
    }

    /**
     * CTC head bias is a vector of 18710 added onto every time step.
     * The general index walk spent about 20 ms on one long line doing that add.
     */
    private static boolean trailingTile(Tensor a, Tensor b, float[] out, int[] shape, int op, Run run) {
        if (Arrays.equals(shape, a.dims)) {
            int tile = Broadcast.trailingTile(b.dims, a.dims);
            if (tile == b.numel()) {
                Blas.tiled(a.f, b.f, out, a.numel(), tile, op, false, run.vector);
                return true;
            }
        }
        if (Arrays.equals(shape, b.dims)) {
            int tile = Broadcast.trailingTile(a.dims, b.dims);
            if (tile == a.numel()) {
                Blas.tiled(b.f, a.f, out, b.numel(), tile, op, true, run.vector);
                return true;
            }
        }
        return false;
    }

    private static void channel(float[] big, float[] channel, float[] out, int[] dims, int op, boolean channelOnLeft, boolean vector) {
        int batch = dims[0];
        int channels = dims[1];
        int hw = dims[2] * dims[3];
        for (int n = 0; n < batch; n++) {
            for (int c = 0; c < channels; c++) {
                int off = (n * channels + c) * hw;
                if (channelOnLeft) {
                    Blas.scalarLeft(channel[c], big, off, out, off, hw, op, vector, null, 1);
                } else {
                    Blas.scalarRight(big, off, channel[c], out, off, hw, op, vector, null, 1);
                }
            }
        }
    }

    private static void generalFloat(float[] a, int[] ad, float[] b, int[] bd, float[] out, int[] shape, int op) {
        int n = Tensor.numel(shape);
        int rank = shape.length;
        int[] coord = new int[rank];
        int[] as = Broadcast.strides(ad, rank);
        int[] bs = Broadcast.strides(bd, rank);
        for (int p = 0; p < n; p++) {
            unravel(p, shape, coord);
            float av = a[Broadcast.offset(coord, as)];
            float bv = b[Broadcast.offset(coord, bs)];
            out[p] = Blas.scalar(av, bv, op);
        }
    }

    private static Tensor binaryLong(Tensor a, Tensor b, int op, int[] shape, Run run) {
        int n = Tensor.numel(shape);
        long[] out = run.arena.allocI(n);
        int rank = shape.length;
        int[] coord = new int[rank];
        int[] as = Broadcast.strides(a.dims, rank);
        int[] bs = Broadcast.strides(b.dims, rank);
        for (int p = 0; p < n; p++) {
            unravel(p, shape, coord);
            long av = a.i[Broadcast.offset(coord, as)];
            long bv = b.i[Broadcast.offset(coord, bs)];
            out[p] = switch (op) {
                case 0 -> av + bv;
                case 1 -> av - bv;
                case 2 -> av * bv;
                default -> bv == 0 ? 0 : av / bv;
            };
        }
        return Tensor.pooledI64(shape, out);
    }

    static Tensor pow(Tensor a, Tensor b, Run run) {
        if (a.f == null || b.f == null) {
            throw new OcrException("Pow expects float");
        }
        if (b.numel() == 1 && b.f[0] == 2f && Broadcast.shape(a.dims, b.dims).length == a.rank()
                && Arrays.equals(Broadcast.shape(a.dims, b.dims), a.dims)) {
            int n = a.numel();
            float[] out = run.arena.allocF(n);
            Blas.binary(a.f, 0, a.f, 0, out, 0, n, 2, run.vector, run.pool, run.threads);
            return Tensor.pooledF32(a.dims, out);
        }
        int[] shape = Broadcast.shape(a.dims, b.dims);
        int n = Tensor.numel(shape);
        float[] out = run.arena.allocF(n);
        if (b.numel() == 1 && Arrays.equals(shape, a.dims)) {
            float e = b.f[0];
            for (int i = 0; i < n; i++) {
                out[i] = (float) Math.pow(a.f[i], e);
            }
            return Tensor.pooledF32(shape, out);
        }
        int rank = shape.length;
        int[] coord = new int[rank];
        int[] as = Broadcast.strides(a.dims, rank);
        int[] bs = Broadcast.strides(b.dims, rank);
        for (int p = 0; p < n; p++) {
            unravel(p, shape, coord);
            out[p] = (float) Math.pow(a.f[Broadcast.offset(coord, as)], b.f[Broadcast.offset(coord, bs)]);
        }
        return Tensor.pooledF32(shape, out);
    }

    static Tensor sqrt(Tensor a, Run run) {
        int n = a.numel();
        float[] out = run.arena.allocF(n);
        for (int i = 0; i < n; i++) {
            out[i] = (float) Math.sqrt(a.f[i]);
        }
        return Tensor.pooledF32(a.dims, out);
    }

    static Tensor erf(Tensor a, Run run) {
        int n = a.numel();
        float[] out = run.arena.allocF(n);
        if (run.vector && VectorSupport.available()) {
            VectorKernels.erf(a.f, out, n);
        } else {
            for (int i = 0; i < n; i++) {
                out[i] = erf(a.f[i]);
            }
        }
        return Tensor.pooledF32(a.dims, out);
    }

    /**
     * Abramowitz and Stegun 7.1.26. The absolute error stays near 1.5e-7,
     * which is enough for the GELU that PP-OCRv6 builds out of {@code Erf}.
     * The vector path uses the same coefficients in float; it stays within
     * about 2e-5 of this double result.
     */
    static float erf(float value) {
        double x = value;
        double sign = x < 0d ? -1d : 1d;
        double ax = Math.abs(x);
        double t = 1d / (1d + 0.3275911d * ax);
        double poly = (((((((((1.061405429d * t) - 1.453152027d) * t) + 1.421413741d) * t)
                - 0.284496736d) * t) + 0.254829592d) * t);
        return (float) (sign * (1d - poly * Math.exp(-ax * ax)));
    }

    static Tensor unary(Tensor a, String act, float alpha, float beta, Run run) {
        int n = a.numel();
        float[] out = run.arena.allocF(n);
        System.arraycopy(a.f, 0, out, 0, n);
        Blas.activate(out, 0, n, act, alpha, beta, run.vector, run.pool, run.threads);
        return Tensor.pooledF32(a.dims, out);
    }

    static Tensor softmax(Tensor a, int axis, boolean coalesce, Run run) {
        int rank = a.rank();
        if (axis < 0) {
            axis += rank;
        }
        if (axis < 0 || axis >= rank) {
            throw new OcrException("softmax axis " + axis + " for rank " + rank);
        }
        float[] out = run.arena.allocF(a.numel());
        if (coalesce) {
            int outer = product(a.dims, 0, axis);
            int inner = product(a.dims, axis, rank);
            for (int o = 0; o < outer; o++) {
                int base = o * inner;
                softmaxContiguous(a.f, out, base, inner, 1);
            }
        } else {
            int before = product(a.dims, 0, axis);
            int mid = a.dims[axis];
            int after = product(a.dims, axis + 1, rank);
            for (int b = 0; b < before; b++) {
                for (int t = 0; t < after; t++) {
                    int base = (b * mid) * after + t;
                    softmaxContiguous(a.f, out, base, mid, after);
                }
            }
        }
        return Tensor.pooledF32(a.dims, out);
    }

    private static void softmaxContiguous(float[] in, float[] out, int base, int count, int stride) {
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            max = Math.max(max, in[base + i * stride]);
        }
        double sum = 0;
        for (int i = 0; i < count; i++) {
            double e = Math.exp(in[base + i * stride] - max);
            out[base + i * stride] = (float) e;
            sum += e;
        }
        float inv = (float) (1.0 / sum);
        for (int i = 0; i < count; i++) {
            out[base + i * stride] *= inv;
        }
    }

    static Tensor reduceMean(Tensor a, int[] axes, boolean keepdims, boolean noop, Run run) {
        int rank = a.rank();
        boolean[] reduced = new boolean[rank];
        if (axes == null) {
            if (noop) {
                return copyFloat(a, run);
            }
            Arrays.fill(reduced, true);
        } else {
            for (int axis : axes) {
                int ax = axis < 0 ? axis + rank : axis;
                if (ax < 0 || ax >= rank) {
                    throw new OcrException("ReduceMean axis " + axis);
                }
                reduced[ax] = true;
            }
        }
        if (axes != null && axes.length == 1) {
            int ax = axes[0] < 0 ? axes[0] + rank : axes[0];
            return reduceOne(a, ax, keepdims, run);
        }
        int[] outDims = keepDims(a.dims, reduced, keepdims);
        int nOut = Tensor.numel(outDims);
        double[] acc = new double[nOut];
        int count = 1;
        for (int d = 0; d < rank; d++) {
            if (reduced[d]) {
                count *= a.dims[d];
            }
        }
        int[] stride = new int[rank];
        int accStride = 1;
        for (int d = rank - 1; d >= 0; d--) {
            if (reduced[d]) {
                stride[d] = 0;
            } else {
                stride[d] = accStride;
                accStride *= a.dims[d];
            }
        }
        int[] coord = new int[rank];
        for (int p = 0; p < a.numel(); p++) {
            unravel(p, a.dims, coord);
            int oi = 0;
            for (int d = 0; d < rank; d++) {
                oi += coord[d] * stride[d];
            }
            acc[oi] += a.f[p];
        }
        float[] out = run.arena.allocF(nOut);
        float inv = count == 0 ? 0f : 1f / count;
        for (int i = 0; i < nOut; i++) {
            out[i] = (float) (acc[i] * inv);
        }
        if (!keepdims && outDims.length == 0) {
            return Tensor.pooledF32(new int[0], out);
        }
        return Tensor.pooledF32(outDims, out);
    }

    private static Tensor reduceOne(Tensor a, int axis, boolean keepdims, Run run) {
        int before = product(a.dims, 0, axis);
        int mid = a.dims[axis];
        int after = product(a.dims, axis + 1, a.rank());
        int[] outDims;
        if (keepdims) {
            outDims = a.dims.clone();
            outDims[axis] = 1;
        } else {
            outDims = new int[a.rank() - 1];
            int j = 0;
            for (int d = 0; d < a.rank(); d++) {
                if (d != axis) {
                    outDims[j++] = a.dims[d];
                }
            }
        }
        float[] out = run.arena.allocF(Tensor.numel(outDims));
        for (int b = 0; b < before; b++) {
            for (int t = 0; t < after; t++) {
                double sum = 0;
                for (int m = 0; m < mid; m++) {
                    sum += a.f[(b * mid + m) * after + t];
                }
                out[b * after + t] = (float) (sum / mid);
            }
        }
        return Tensor.pooledF32(outDims, out);
    }

    private static int[] keepDims(int[] dims, boolean[] reduced, boolean keep) {
        if (keep) {
            int[] out = dims.clone();
            for (int i = 0; i < out.length; i++) {
                if (reduced[i]) {
                    out[i] = 1;
                }
            }
            return out;
        }
        int n = 0;
        for (boolean r : reduced) {
            if (!r) {
                n++;
            }
        }
        int[] out = new int[n];
        int j = 0;
        for (int i = 0; i < dims.length; i++) {
            if (!reduced[i]) {
                out[j++] = dims[i];
            }
        }
        return out;
    }

    static Tensor copyFloat(Tensor a, Run run) {
        int n = a.numel();
        float[] out = run.arena.allocF(n);
        System.arraycopy(a.f, 0, out, 0, n);
        return Tensor.pooledF32(a.dims, out);
    }

    static int product(int[] dims, int from, int to) {
        int n = 1;
        for (int i = from; i < to; i++) {
            n *= dims[i];
        }
        return n;
    }

    static void unravel(int index, int[] dims, int[] coord) {
        for (int d = dims.length - 1; d >= 0; d--) {
            int dim = dims[d];
            coord[d] = dim == 0 ? 0 : index % dim;
            if (dim != 0) {
                index /= dim;
            }
        }
    }
}
