package org.krzywanski.ocr.runtime;

import org.krzywanski.ocr.OcrException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Dispatches GEMM, AXPY and elementwise kernels. Vector calls stay behind {@code vector == true}. */
final class Blas {
    static final long PARALLEL_FLOPS = 300_000L;

    private Blas() {
    }

    static void gemm(
            float[] a, int aOff, int lda,
            float[] b, int bOff, int ldb,
            float[] c, int cOff, int ldc,
            int m, int n, int k,
            boolean vector,
            ExecutorService pool,
            int threads
    ) {
        if (m <= 0 || n <= 0 || k <= 0) {
            return;
        }
        if (pool != null && threads > 1 && (long) m * n * k >= PARALLEL_FLOPS) {
            // A wide activation (N) is read once per worker when rows are split, so the
            // whole map crosses the bus threads times. Column panels read B once.
            // Each output stays on one worker, so the K reduction order matches one thread.
            if (n >= m && n >= threads * 32) {
                int parts = Math.min(threads, n / 32);
                int cols = (n + parts - 1) / parts;
                List<Callable<Void>> jobs = new ArrayList<>();
                for (int p = 0; p < parts; p++) {
                    int n0 = p * cols;
                    int n1 = Math.min(n, n0 + cols);
                    if (n0 >= n1) {
                        break;
                    }
                    final int fn0 = n0;
                    final int width = n1 - n0;
                    jobs.add(() -> {
                        gemmSlice(a, aOff, lda, b, bOff + fn0, ldb, c, cOff + fn0, ldc, m, width, k, vector);
                        return null;
                    });
                }
                await(pool, jobs);
                return;
            }
            if (m >= threads) {
                int parts = Math.min(threads, m);
                int rows = (m + parts - 1) / parts;
                List<Callable<Void>> jobs = new ArrayList<>();
                for (int p = 0; p < parts; p++) {
                    int m0 = p * rows;
                    int m1 = Math.min(m, m0 + rows);
                    if (m0 >= m1) {
                        break;
                    }
                    final int fm0 = m0;
                    final int fm1 = m1;
                    jobs.add(() -> {
                        gemmSlice(a, aOff + fm0 * lda, lda, b, bOff, ldb, c, cOff + fm0 * ldc, ldc,
                                fm1 - fm0, n, k, vector);
                        return null;
                    });
                }
                await(pool, jobs);
                return;
            }
        }
        // One thread re-reads a two-vector panel of B once per four rows. Gathering that
        // panel pays for itself from 32×64×32 up. A worker slice is thinner, and the extra pass lost.
        if (vector && m >= 32 && n >= 64 && k >= 32) {
            VectorKernels.gemmPacked(a, aOff, lda, b, bOff, ldb, c, cOff, ldc, m, n, k);
            return;
        }
        gemmSlice(a, aOff, lda, b, bOff, ldb, c, cOff, ldc, m, n, k, vector);
    }

    private static void gemmSlice(
            float[] a, int aOff, int lda,
            float[] b, int bOff, int ldb,
            float[] c, int cOff, int ldc,
            int m, int n, int k,
            boolean vector
    ) {
        if (vector) {
            VectorKernels.gemm(a, aOff, lda, b, bOff, ldb, c, cOff, ldc, m, n, k);
        } else {
            Gemm.scalar(a, aOff, lda, b, bOff, ldb, c, cOff, ldc, m, n, k);
        }
    }

    static void axpy(float[] in, int inOff, float[] out, int outOff, int n, float scale, boolean vector) {
        if (n <= 0 || scale == 0f) {
            return;
        }
        if (vector) {
            VectorKernels.axpy(in, inOff, out, outOff, n, scale);
            return;
        }
        for (int i = 0; i < n; i++) {
            out[outOff + i] += scale * in[inOff + i];
        }
    }

    static void axpy3(
            float[] in, int i0, int i1, int i2, float[] out, int outOff, int n,
            float s0, float s1, float s2, boolean vector
    ) {
        if (n <= 0 || (s0 == 0f && s1 == 0f && s2 == 0f)) {
            return;
        }
        if (vector) {
            VectorKernels.axpy3(in, i0, i1, i2, out, outOff, n, s0, s1, s2);
            return;
        }
        for (int i = 0; i < n; i++) {
            out[outOff + i] += s0 * in[i0 + i] + s1 * in[i1 + i] + s2 * in[i2 + i];
        }
    }

    /**
     * One pass of a 3×3 window. {@code origin} addresses the top-left tap of output pixel 0.
     * The three input rows are {@code inW} apart and each is read at columns 0, 1 and 2.
     */
    static void axpy9(
            float[] in, int origin, int inW, float[] out, int outOff, int n,
            float[] w, int wOff, boolean vector
    ) {
        if (n <= 0) {
            return;
        }
        if (vector) {
            VectorKernels.axpy9(in, origin, inW, out, outOff, n, w, wOff);
            return;
        }
        for (int i = 0; i < n; i++) {
            float acc = out[outOff + i];
            int p = origin + i;
            acc += w[wOff] * in[p] + w[wOff + 1] * in[p + 1] + w[wOff + 2] * in[p + 2];
            p += inW;
            acc += w[wOff + 3] * in[p] + w[wOff + 4] * in[p + 1] + w[wOff + 5] * in[p + 2];
            p += inW;
            acc += w[wOff + 6] * in[p] + w[wOff + 7] * in[p + 1] + w[wOff + 8] * in[p + 2];
            out[outOff + i] = acc;
        }
    }

    /**
     * Interior of a 3×3 stride-2 convolution after an even/odd pack.
     * Scalar tail matches {@link VectorKernels#axpyPacked3}.
     */
    static void axpyPacked3(
            float[] in, int row0, int row1, int row2, int evenCount,
            float[] out, int outOff, int n, float[] w, int wOff, boolean vector
    ) {
        if (n <= 0) {
            return;
        }
        if (vector) {
            VectorKernels.axpyPacked3(in, row0, row1, row2, evenCount, out, outOff, n, w, wOff);
            return;
        }
        for (int i = 0; i < n; i++) {
            float acc = out[outOff + i];
            acc += w[wOff] * in[row0 + evenCount + i] + w[wOff + 1] * in[row0 + 1 + i]
                    + w[wOff + 2] * in[row0 + evenCount + 1 + i];
            acc += w[wOff + 3] * in[row1 + evenCount + i] + w[wOff + 4] * in[row1 + 1 + i]
                    + w[wOff + 5] * in[row1 + evenCount + 1 + i];
            acc += w[wOff + 6] * in[row2 + evenCount + i] + w[wOff + 7] * in[row2 + 1 + i]
                    + w[wOff + 8] * in[row2 + evenCount + 1 + i];
            out[outOff + i] = acc;
        }
    }

    /** Split elementwise work only when one pass is large enough to hide the fork. */
    static final int PARALLEL_ELEMENTS = 1_000_000;

    /** op: 0 add, 1 sub, 2 mul, 3 div. */
    static void binary(
            float[] a, int aOff, float[] b, int bOff, float[] out, int outOff, int n, int op, boolean vector,
            ExecutorService pool, int threads
    ) {
        if (n >= PARALLEL_ELEMENTS && parallelRange(n, pool, threads, (from, until) ->
                binary(a, aOff + from, b, bOff + from, out, outOff + from, until - from, op, vector, null, 1))) {
            return;
        }
        if (vector) {
            VectorKernels.binary(a, aOff, b, bOff, out, outOff, n, op);
            return;
        }
        for (int i = 0; i < n; i++) {
            out[outOff + i] = scalar(a[aOff + i], b[bOff + i], op);
        }
    }

    /** out = op(values, scalar). */
    static void scalarRight(
            float[] values, int inOff, float scalar, float[] out, int outOff, int n, int op, boolean vector,
            ExecutorService pool, int threads
    ) {
        if (n >= PARALLEL_ELEMENTS && parallelRange(n, pool, threads, (from, until) ->
                scalarRight(values, inOff + from, scalar, out, outOff + from, until - from, op, vector, null, 1))) {
            return;
        }
        if (vector) {
            VectorKernels.scalarBroadcast(values, inOff, scalar, out, outOff, n, op);
            return;
        }
        for (int i = 0; i < n; i++) {
            out[outOff + i] = scalar(values[inOff + i], scalar, op);
        }
    }

    /** {@code small} repeats as a contiguous suffix of {@code big}. {@code tileOnLeft} selects op order. */
    static void tiled(
            float[] big, float[] tile, float[] out, int n, int tileLen, int op, boolean tileOnLeft, boolean vector
    ) {
        if (vector && VectorSupport.available()) {
            VectorKernels.binaryTiled(big, tile, out, n, tileLen, op, tileOnLeft);
            return;
        }
        for (int i = 0; i < n; i++) {
            float t = tile[i % tileLen];
            float v = big[i];
            out[i] = scalar(tileOnLeft ? t : v, tileOnLeft ? v : t, op);
        }
    }

    /** out = op(scalar, values). */
    static void scalarLeft(
            float scalar, float[] values, int inOff, float[] out, int outOff, int n, int op, boolean vector,
            ExecutorService pool, int threads
    ) {
        if (n >= PARALLEL_ELEMENTS && parallelRange(n, pool, threads, (from, until) ->
                scalarLeft(scalar, values, inOff + from, out, outOff + from, until - from, op, vector, null, 1))) {
            return;
        }
        if (vector) {
            VectorKernels.scalarLeft(scalar, values, inOff, out, outOff, n, op);
            return;
        }
        for (int i = 0; i < n; i++) {
            out[outOff + i] = scalar(scalar, values[inOff + i], op);
        }
    }

    static void activate(
            float[] y, int off, int n, String act, float alpha, float beta, boolean vector,
            ExecutorService pool, int threads
    ) {
        activate(y, off, n, act, alpha, beta, 1f, 0f, vector, pool, threads);
    }

    /**
     * In-place activation, then {@code y * outScale + outShift}.
     * Scale 1 and shift 0 keep the plain kernel, including its vector tail.
     */
    static void activate(
            float[] y, int off, int n, String act, float alpha, float beta,
            float outScale, float outShift, boolean vector,
            ExecutorService pool, int threads
    ) {
        boolean affine = outScale != 1f || outShift != 0f;
        if (n <= 0 || (act == null && !affine)) {
            return;
        }
        if (n >= PARALLEL_ELEMENTS && parallelRange(n, pool, threads, (from, until) ->
                activate(y, off + from, until - from, act, alpha, beta, outScale, outShift, vector, null, 1))) {
            return;
        }
        if (!affine) {
            if (vector) {
                VectorKernels.activate(y, off, n, act, alpha, beta);
                return;
            }
            for (int i = 0; i < n; i++) {
                y[off + i] = Activations.apply(y[off + i], act, alpha, beta);
            }
            return;
        }
        if (vector) {
            VectorKernels.activateAffine(y, off, n, act, alpha, beta, outScale, outShift);
            return;
        }
        for (int i = 0; i < n; i++) {
            float v = act == null ? y[off + i] : Activations.apply(y[off + i], act, alpha, beta);
            y[off + i] = v * outScale + outShift;
        }
    }

    private interface RangeTask {
        void run(int from, int until);
    }

    /** Returns false when the work stays on the caller. Each element is still written by one thread. */
    private static boolean parallelRange(int n, ExecutorService pool, int threads, RangeTask task) {
        if (pool == null || threads < 2 || n < PARALLEL_ELEMENTS) {
            return false;
        }
        int parts = Math.min(threads, n / 65_536);
        if (parts < 2) {
            return false;
        }
        int span = (n + parts - 1) / parts;
        // Keep every chunk except the last a multiple of 16 so the vector tail stays on the same elements.
        span = (span + 15) & ~15;
        if (span >= n) {
            return false;
        }
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int p = 0; p < parts; p++) {
            int a = p * span;
            int b = Math.min(n, a + span);
            if (a >= b) {
                break;
            }
            final int fa = a;
            final int fb = b;
            jobs.add(() -> {
                task.run(fa, fb);
                return null;
            });
        }
        await(pool, jobs);
        return true;
    }

    static float scalar(float a, float b, int op) {
        return switch (op) {
            case 0 -> a + b;
            case 1 -> a - b;
            case 2 -> a * b;
            default -> a / b;
        };
    }

    static void await(ExecutorService pool, List<Callable<Void>> jobs) {
        try {
            for (Future<Void> future : pool.invokeAll(jobs)) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new OcrException(cause.getMessage(), cause);
        }
    }
}
