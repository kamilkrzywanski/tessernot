package io.github.kamilkrzywanski.ocr.runtime;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.util.Arrays;

final class ShapeOps {
    private ShapeOps() {
    }

    static Tensor identity(Tensor a, Run run) {
        if (a.dtype == Tensor.F32) {
            return ElementOps.copyFloat(a, run);
        }
        long[] out = run.arena.allocI(a.numel());
        System.arraycopy(a.i, 0, out, 0, out.length);
        return Tensor.pooledI64(a.dims, out);
    }

    static Tensor shape(Tensor a, Run run) {
        long[] dims = new long[a.rank()];
        for (int i = 0; i < dims.length; i++) {
            dims[i] = a.dims[i];
        }
        return Tensor.i64(new int[]{dims.length}, dims);
    }

    static Tensor reshape(Tensor data, Tensor shape, int allowZero, Run run) {
        long[] spec = shape.i;
        int[] out = new int[spec.length];
        int infer = -1;
        long known = 1;
        for (int i = 0; i < spec.length; i++) {
            long v = spec[i];
            if (v == 0 && allowZero == 0) {
                if (i >= data.rank()) {
                    throw new OcrException("reshape 0 past input rank");
                }
                out[i] = data.dims[i];
            } else if (v == -1) {
                if (infer >= 0) {
                    throw new OcrException("reshape has two -1 dims");
                }
                infer = i;
                continue;
            } else {
                out[i] = (int) v;
            }
            known *= out[i];
        }
        int n = data.numel();
        if (infer >= 0) {
            if (known == 0) {
                throw new OcrException("reshape cannot infer a zero product");
            }
            if (n % known != 0) {
                throw new OcrException("reshape size mismatch");
            }
            out[infer] = (int) (n / known);
        } else if (known != n) {
            throw new OcrException("reshape numel " + known + " != " + n);
        }
        return copySame(data, out, run);
    }

    static Tensor transpose(Tensor a, int[] perm, Run run) {
        int rank = a.rank();
        if (perm == null) {
            perm = new int[rank];
            for (int i = 0; i < rank; i++) {
                perm[i] = rank - 1 - i;
            }
        }
        if (perm.length != rank) {
            throw new OcrException("transpose perm rank");
        }
        int[] outDims = new int[rank];
        for (int i = 0; i < rank; i++) {
            outDims[i] = a.dims[perm[i]];
        }
        int n = a.numel();
        int[] inCoord = new int[rank];
        int[] outCoord = new int[rank];
        int[] inStride = Broadcast.strides(a.dims, rank);
        int[] outStride = Broadcast.strides(outDims, rank);
        if (a.dtype == Tensor.F32) {
            float[] out = run.arena.allocF(n);
            for (int p = 0; p < n; p++) {
                ElementOps.unravel(p, a.dims, inCoord);
                for (int i = 0; i < rank; i++) {
                    outCoord[i] = inCoord[perm[i]];
                }
                out[Broadcast.offset(outCoord, outStride)] = a.f[Broadcast.offset(inCoord, inStride)];
            }
            return Tensor.pooledF32(outDims, out);
        }
        long[] out = run.arena.allocI(n);
        for (int p = 0; p < n; p++) {
            ElementOps.unravel(p, a.dims, inCoord);
            for (int i = 0; i < rank; i++) {
                outCoord[i] = inCoord[perm[i]];
            }
            out[Broadcast.offset(outCoord, outStride)] = a.i[Broadcast.offset(inCoord, inStride)];
        }
        return Tensor.pooledI64(outDims, out);
    }

    static Tensor squeeze(Tensor a, long[] axes, Run run) {
        int rank = a.rank();
        boolean[] drop = new boolean[rank];
        if (axes == null) {
            for (int i = 0; i < rank; i++) {
                drop[i] = a.dims[i] == 1;
            }
        } else {
            for (long axis : axes) {
                int ax = (int) (axis < 0 ? axis + rank : axis);
                if (ax < 0 || ax >= rank || a.dims[ax] != 1) {
                    throw new OcrException("squeeze axis " + axis + " of " + Arrays.toString(a.dims));
                }
                drop[ax] = true;
            }
        }
        int n = 0;
        for (boolean d : drop) {
            if (!d) {
                n++;
            }
        }
        int[] out = new int[n];
        int j = 0;
        for (int i = 0; i < rank; i++) {
            if (!drop[i]) {
                out[j++] = a.dims[i];
            }
        }
        return copySame(a, out, run);
    }

    static Tensor unsqueeze(Tensor a, long[] axes, Run run) {
        int outRank = a.rank() + axes.length;
        boolean[] insert = new boolean[outRank];
        for (long axis : axes) {
            int ax = (int) (axis < 0 ? axis + outRank : axis);
            if (ax < 0 || ax >= outRank || insert[ax]) {
                throw new OcrException("unsqueeze axis " + axis);
            }
            insert[ax] = true;
        }
        int[] out = new int[outRank];
        int j = 0;
        for (int i = 0; i < outRank; i++) {
            out[i] = insert[i] ? 1 : a.dims[j++];
        }
        return copySame(a, out, run);
    }

    static Tensor concat(Tensor[] parts, int axis, Run run) {
        if (parts.length == 0) {
            throw new OcrException("concat without inputs");
        }
        int rank = parts[0].rank();
        if (axis < 0) {
            axis += rank;
        }
        if (axis < 0 || axis >= rank) {
            throw new OcrException("concat axis " + axis);
        }
        int[] outDims = parts[0].dims.clone();
        outDims[axis] = 0;
        for (Tensor p : parts) {
            if (p.rank() != rank || p.dtype != parts[0].dtype) {
                throw new OcrException("concat shape/dtype");
            }
            for (int d = 0; d < rank; d++) {
                if (d != axis && p.dims[d] != parts[0].dims[d]) {
                    throw new OcrException("concat dim mismatch");
                }
            }
            outDims[axis] += p.dims[axis];
        }
        int outer = ElementOps.product(outDims, 0, axis);
        int after = ElementOps.product(outDims, axis + 1, rank);
        if (parts[0].dtype == Tensor.F32) {
            float[] out = run.arena.allocF(Tensor.numel(outDims));
            for (int o = 0; o < outer; o++) {
                int cursor = 0;
                for (Tensor p : parts) {
                    int chunk = p.dims[axis] * after;
                    System.arraycopy(p.f, o * chunk, out, (o * outDims[axis] + cursor) * after, chunk);
                    cursor += p.dims[axis];
                }
            }
            return Tensor.pooledF32(outDims, out);
        }
        long[] out = run.arena.allocI(Tensor.numel(outDims));
        for (int o = 0; o < outer; o++) {
            int cursor = 0;
            for (Tensor p : parts) {
                int chunk = p.dims[axis] * after;
                System.arraycopy(p.i, o * chunk, out, (o * outDims[axis] + cursor) * after, chunk);
                cursor += p.dims[axis];
            }
        }
        return Tensor.pooledI64(outDims, out);
    }

    static Tensor slice(Tensor data, long[] starts, long[] ends, long[] axes, long[] steps, Run run) {
        int rank = data.rank();
        if (axes == null) {
            axes = new long[starts.length];
            for (int i = 0; i < starts.length; i++) {
                axes[i] = i;
            }
        }
        if (steps == null) {
            steps = new long[starts.length];
            Arrays.fill(steps, 1);
        }
        int[] start = new int[rank];
        int[] end = data.dims.clone();
        int[] step = new int[rank];
        Arrays.fill(step, 1);
        int[] outDims = data.dims.clone();
        for (int i = 0; i < axes.length; i++) {
            int axis = (int) axes[i];
            if (axis < 0) {
                axis += rank;
            }
            int dim = data.dims[axis];
            int st = (int) clampIndex(starts[i], dim, false, steps[i]);
            int en = (int) clampIndex(ends[i], dim, true, steps[i]);
            int sp = (int) steps[i];
            if (sp == 0) {
                throw new OcrException("slice step 0");
            }
            int len = sp > 0 ? (st >= en ? 0 : divUp(en - st, sp)) : (st <= en ? 0 : divUp(st - en, -sp));
            start[axis] = st;
            end[axis] = en;
            step[axis] = sp;
            outDims[axis] = len;
        }
        int n = Tensor.numel(outDims);
        int[] coord = new int[rank];
        int[] inStride = Broadcast.strides(data.dims, rank);
        if (data.dtype == Tensor.F32) {
            float[] out = run.arena.allocF(n);
            for (int p = 0; p < n; p++) {
                ElementOps.unravel(p, outDims, coord);
                int src = 0;
                for (int d = 0; d < rank; d++) {
                    src += (start[d] + coord[d] * step[d]) * inStride[d];
                }
                out[p] = data.f[src];
            }
            return Tensor.pooledF32(outDims, out);
        }
        long[] out = run.arena.allocI(n);
        for (int p = 0; p < n; p++) {
            ElementOps.unravel(p, outDims, coord);
            int src = 0;
            for (int d = 0; d < rank; d++) {
                src += (start[d] + coord[d] * step[d]) * inStride[d];
            }
            out[p] = data.i[src];
        }
        return Tensor.pooledI64(outDims, out);
    }

    private static long clampIndex(long v, int dim, boolean end, long step) {
        if (v < 0) {
            if (v < Long.MIN_VALUE / 2) {
                v = step < 0 && end ? -1 : 0;
            } else {
                v += dim;
            }
        }
        if (step < 0) {
            long lo = end ? -1 : 0;
            long hi = dim - 1L;
            if (v < lo) {
                v = lo;
            }
            if (v > hi) {
                v = hi;
            }
        } else if (v < 0) {
            v = 0;
        } else if (v > dim) {
            v = dim;
        }
        return v;
    }

    private static int divUp(int num, int den) {
        return (num + den - 1) / den;
    }

    static Tensor flatten(Tensor a, int axis, Run run) {
        if (axis < 0) {
            axis += a.rank();
        }
        int left = ElementOps.product(a.dims, 0, axis);
        int right = ElementOps.product(a.dims, axis, a.rank());
        return copySame(a, new int[]{left, right}, run);
    }

    static Tensor cast(Tensor a, int to, Run run) {
        boolean toFloat = to == 1 || to == 10 || to == 11 || to == 16;
        if (toFloat) {
            int n = a.numel();
            float[] out = run.arena.allocF(n);
            if (a.f != null) {
                System.arraycopy(a.f, 0, out, 0, n);
            } else {
                for (int i = 0; i < n; i++) {
                    out[i] = a.i[i];
                }
            }
            return Tensor.pooledF32(a.dims, out);
        }
        long[] out = run.arena.allocI(a.numel());
        if (a.i != null) {
            if (to == 9) {
                for (int i = 0; i < out.length; i++) {
                    out[i] = a.i[i] == 0 ? 0 : 1;
                }
            } else {
                System.arraycopy(a.i, 0, out, 0, out.length);
            }
        } else {
            for (int i = 0; i < out.length; i++) {
                float v = a.f[i];
                if (to == 9) {
                    out[i] = v == 0f ? 0 : 1;
                } else {
                    out[i] = (long) v;
                }
            }
        }
        return Tensor.pooledI64(a.dims, out);
    }

    private static Tensor copySame(Tensor data, int[] dims, Run run) {
        if (data.dtype == Tensor.F32) {
            int n = data.numel();
            float[] out = run.arena.allocF(n);
            System.arraycopy(data.f, 0, out, 0, n);
            return Tensor.pooledF32(dims, out);
        }
        long[] out = run.arena.allocI(data.numel());
        System.arraycopy(data.i, 0, out, 0, out.length);
        return Tensor.pooledI64(dims, out);
    }
}
