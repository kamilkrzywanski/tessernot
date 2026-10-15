package io.github.kamilkrzywanski.ocr.internal.runtime;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.util.Arrays;

/**
 * Ranked tensor. Float payloads use {@link #F32}. Integer and bool payloads are stored as {@link #I64}.
 * An empty {@code dims} array is a scalar (numel 1). A zero in {@code dims} makes the tensor empty.
 */
public final class Tensor {
    public static final int F32 = 1;
    public static final int I64 = 7;

    public final int dtype;
    public final int[] dims;
    public final float[] f;
    public final long[] i;
    /** True when {@code f} was rented from an {@link Arena} and may be recycled after the last consumer. */
    public boolean pooled;

    private Tensor(int dtype, int[] dims, float[] f, long[] i, boolean pooled) {
        this.dtype = dtype;
        this.dims = dims.clone();
        this.f = f;
        this.i = i;
        this.pooled = pooled;
        int n = numel(this.dims);
        if (dtype == F32) {
            // Pooled floats may be a power-of-two bucket larger than the logical length.
            boolean fits = f != null && (pooled ? f.length >= n : f.length == n);
            if (!fits) {
                throw new OcrException("float tensor length " + (f == null ? -1 : f.length) + " != " + n);
            }
        } else if (i == null || i.length != n) {
            throw new OcrException("int64 tensor length " + (i == null ? -1 : i.length) + " != " + n);
        }
    }

    public static Tensor f32(int[] dims, float[] data) {
        return new Tensor(F32, dims, data, null, false);
    }

    public static Tensor i64(int[] dims, long[] data) {
        return new Tensor(I64, dims, null, data, false);
    }

    public static Tensor pooledF32(int[] dims, float[] data) {
        return new Tensor(F32, dims, data, null, true);
    }

    public static Tensor pooledI64(int[] dims, long[] data) {
        return new Tensor(I64, dims, null, data, true);
    }

    public int rank() {
        return dims.length;
    }

    public static int numel(int[] dims) {
        if (dims.length == 0) {
            return 1;
        }
        long n = 1;
        for (int d : dims) {
            if (d < 0) {
                throw new OcrException("negative dimension " + d);
            }
            n *= d;
            if (n > Integer.MAX_VALUE) {
                throw new OcrException("tensor too large");
            }
        }
        return (int) n;
    }

    public int numel() {
        return numel(dims);
    }

    public Tensor copy() {
        if (dtype == F32) {
            int n = numel();
            float[] data = f.length == n ? f.clone() : Arrays.copyOf(f, n);
            return f32(dims, data);
        }
        return i64(dims, i.clone());
    }

    public String shapeString() {
        return (dtype == F32 ? "f32" : "i64") + Arrays.toString(dims);
    }
}
