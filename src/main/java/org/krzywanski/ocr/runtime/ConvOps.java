package org.krzywanski.ocr.runtime;

import org.krzywanski.ocr.OcrException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

final class ConvOps {
    private ConvOps() {
    }

    static Tensor conv(Node node, Tensor x, Tensor w, Tensor bias, Run run) {
        if (x.rank() != 4 || w.rank() != 4) {
            throw new OcrException("Conv expects NCHW input and OIHW weight");
        }
        int batch = x.dims[0];
        int channels = x.dims[1];
        int inH = x.dims[2];
        int inW = x.dims[3];
        int group = (int) node.lng("group", 1);
        int m = w.dims[0];
        int icpg = w.dims[1];
        int kH = w.dims[2];
        int kW = w.dims[3];
        if (group <= 0 || channels != icpg * group || m % group != 0) {
            throw new OcrException("Conv group/weight mismatch M=" + m + " C=" + channels + " group=" + group);
        }
        if (bias != null && bias.numel() != m) {
            throw new OcrException("Conv bias " + bias.numel() + " != " + m);
        }
        int[] pads = pads(node, inH, inW, kH, kW);
        int sH = spatial(node, "strides", 0, 1);
        int sW = spatial(node, "strides", 1, 1);
        int dH = spatial(node, "dilations", 0, 1);
        int dW = spatial(node, "dilations", 1, 1);
        int outH = convOut(inH, kH, sH, dH, pads[0], pads[2]);
        int outW = convOut(inW, kW, sW, dW, pads[1], pads[3]);
        if (outH <= 0 || outW <= 0) {
            throw new OcrException("Conv non-positive output " + outH + "x" + outW);
        }
        int nOut = batch * m * outH * outW;
        float[] out = run.arena.allocF(nOut);
        boolean pointwise = kH == 1 && kW == 1 && sH == 1 && sW == 1 && dH == 1 && dW == 1
                && pads[0] == 0 && pads[1] == 0 && pads[2] == 0 && pads[3] == 0;
        if (pointwise) {
            pointwise(x.f, w.f, bias == null ? null : bias.f, out, batch, channels, inH * inW, m, group, run);
        } else {
            direct(x.f, w.f, bias == null ? null : bias.f, out, batch, channels, inH, inW, m, group, icpg,
                    kH, kW, sH, sW, dH, dW, pads[0], pads[1], outH, outW, run);
        }
        epilogue(node, out, nOut, run);
        return Tensor.pooledF32(new int[]{batch, m, outH, outW}, out);
    }

    static Tensor convTranspose(Node node, Tensor x, Tensor w, Tensor bias, Run run) {
        if (x.rank() != 4 || w.rank() != 4) {
            throw new OcrException("ConvTranspose expects 4D tensors");
        }
        int batch = x.dims[0];
        int channels = x.dims[1];
        int inH = x.dims[2];
        int inW = x.dims[3];
        int group = (int) node.lng("group", 1);
        int cIn = w.dims[0];
        int ocpg = w.dims[1];
        int kH = w.dims[2];
        int kW = w.dims[3];
        if (cIn != channels || channels % group != 0) {
            throw new OcrException("ConvTranspose weight channels " + cIn + " != " + channels);
        }
        int m = ocpg * group;
        if (bias != null && bias.numel() != m) {
            throw new OcrException("ConvTranspose bias mismatch");
        }
        int[] pads = pads(node, inH, inW, kH, kW);
        int sH = spatial(node, "strides", 0, 1);
        int sW = spatial(node, "strides", 1, 1);
        int dH = spatial(node, "dilations", 0, 1);
        int dW = spatial(node, "dilations", 1, 1);
        int opH = spatial(node, "output_padding", 0, 0);
        int opW = spatial(node, "output_padding", 1, 0);
        int outH = sH * (inH - 1) + opH + (dH * (kH - 1) + 1) - pads[0] - pads[2];
        int outW = sW * (inW - 1) + opW + (dW * (kW - 1) + 1) - pads[1] - pads[3];
        if (outH <= 0 || outW <= 0) {
            throw new OcrException("ConvTranspose non-positive output " + outH + "x" + outW);
        }
        int spatial = outH * outW;
        int nOut = batch * m * spatial;
        float[] out = run.arena.allocF(nOut);
        if (bias == null) {
            Arrays.fill(out, 0, nOut, 0f);
        } else {
            for (int n = 0; n < batch; n++) {
                for (int oc = 0; oc < m; oc++) {
                    int off = (n * m + oc) * spatial;
                    Arrays.fill(out, off, off + spatial, bias.f[oc]);
                }
            }
        }
        int icpg = channels / group;
        boolean tiled = sH == kH && sW == kW && dH == 1 && dW == 1
                && opH == 0 && opW == 0
                && pads[0] == 0 && pads[1] == 0 && pads[2] == 0 && pads[3] == 0
                && outH == inH * sH && outW == inW * sW;
        if (tiled) {
            transposeTiled(x.f, w.f, out, batch, channels, inH, inW, m, icpg, ocpg,
                    kH, kW, sH, sW, outH, outW, run);
            epilogue(node, out, nOut, run);
            return Tensor.pooledF32(new int[]{batch, m, outH, outW}, out);
        }
        for (int n = 0; n < batch; n++) {
            for (int ic = 0; ic < channels; ic++) {
                int g = ic / icpg;
                int inBase = ((n * channels + ic) * inH) * inW;
                for (int ocLocal = 0; ocLocal < ocpg; ocLocal++) {
                    int oc = g * ocpg + ocLocal;
                    int outBase = (n * m + oc) * spatial;
                    for (int kh = 0; kh < kH; kh++) {
                        for (int kw = 0; kw < kW; kw++) {
                            float wv = w.f[(((ic * ocpg + ocLocal) * kH + kh) * kW) + kw];
                            for (int ih = 0; ih < inH; ih++) {
                                int oh0 = ih * sH - pads[0] + kh * dH;
                                int inRow = inBase + ih * inW;
                                int outRowBase = outBase + oh0 * outW;
                                for (int iw = 0; iw < inW; iw++) {
                                    int oh = oh0;
                                    int ow = iw * sW - pads[1] + kw * dW;
                                    if (oh >= 0 && ow >= 0 && oh < outH && ow < outW) {
                                        out[outRowBase + ow] += x.f[inRow + iw] * wv;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        epilogue(node, out, nOut, run);
        return Tensor.pooledF32(new int[]{batch, m, outH, outW}, out);
    }

    /** In-place activation, then the optional scalar affine stored on the node. */
    private static void epilogue(Node node, float[] out, int n, Run run) {
        Blas.activate(out, 0, n, node.activation, node.alpha, node.beta,
                node.flt("out_scale", 1f), node.flt("out_shift", 0f),
                run.vector, run.pool, run.threads);
    }

    /**
     * Kernel equals stride and there is no padding, so each input pixel owns a private output tile.
     * Writes do not overlap. Splitting output channels keeps one accumulator order per element.
     */
    private static void transposeTiled(
            float[] in, float[] w, float[] out,
            int batch, int channels, int inH, int inW, int m, int icpg, int ocpg,
            int kH, int kW, int sH, int sW, int outH, int outW, Run run
    ) {
        long work = (long) batch * channels * ocpg * kH * kW * inH * inW;
        int tasks = batch * m;
        boolean parallel = run.pool != null && run.threads > 1 && work >= Blas.PARALLEL_FLOPS;
        if (parallel && tasks >= 2) {
            int parts = Math.min(run.threads, tasks);
            int span = (tasks + parts - 1) / parts;
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int p = 0; p < parts; p++) {
                int a = p * span;
                int b = Math.min(tasks, a + span);
                if (a >= b) {
                    break;
                }
                final int fa = a;
                final int fb = b;
                jobs.add(() -> {
                    for (int task = fa; task < fb; task++) {
                        transposeTile(in, w, out, task / m, task % m, 0, inH, channels, inH, inW, m, icpg, ocpg,
                                kH, kW, sH, sW, outH, outW);
                    }
                    return null;
                });
            }
            Blas.await(run.pool, jobs);
            return;
        }
        if (parallel && inH >= 2) {
            int parts = Math.min(run.threads, inH);
            int span = (inH + parts - 1) / parts;
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int p = 0; p < parts; p++) {
                int a = p * span;
                int b = Math.min(inH, a + span);
                if (a >= b) {
                    break;
                }
                final int fa = a;
                final int fb = b;
                jobs.add(() -> {
                    for (int n = 0; n < batch; n++) {
                        for (int oc = 0; oc < m; oc++) {
                            transposeTile(in, w, out, n, oc, fa, fb, channels, inH, inW, m, icpg, ocpg,
                                    kH, kW, sH, sW, outH, outW);
                        }
                    }
                    return null;
                });
            }
            Blas.await(run.pool, jobs);
            return;
        }
        for (int n = 0; n < batch; n++) {
            for (int oc = 0; oc < m; oc++) {
                transposeTile(in, w, out, n, oc, 0, inH, channels, inH, inW, m, icpg, ocpg,
                        kH, kW, sH, sW, outH, outW);
            }
        }
    }

    private static void transposeTile(
            float[] in, float[] w, float[] out,
            int n, int oc, int ih0, int ih1, int channels, int inH, int inW, int m, int icpg, int ocpg,
            int kH, int kW, int sH, int sW, int outH, int outW
    ) {
        int g = oc / ocpg;
        int ocLocal = oc - g * ocpg;
        int ic0 = g * icpg;
        int spatial = outH * outW;
        int outBase = (n * m + oc) * spatial;
        boolean pair = kH == 2 && kW == 2 && sH == 2 && sW == 2;
        for (int ic = 0; ic < icpg; ic++) {
            int inBase = ((n * channels + ic0 + ic) * inH) * inW;
            int wBase = ((ic0 + ic) * ocpg + ocLocal) * kH * kW;
            if (pair) {
                transposePair(in, w, out, inBase, wBase, outBase, ih0, ih1, inW, outW);
                continue;
            }
            for (int kh = 0; kh < kH; kh++) {
                for (int kw = 0; kw < kW; kw++) {
                    float wv = w[wBase + kh * kW + kw];
                    if (wv == 0f) {
                        continue;
                    }
                    for (int ih = ih0; ih < ih1; ih++) {
                        int inRow = inBase + ih * inW;
                        int outRow = outBase + (ih * sH + kh) * outW + kw;
                        for (int iw = 0; iw < inW; iw++) {
                            out[outRow + iw * sW] += wv * in[inRow + iw];
                        }
                    }
                }
            }
        }
    }

    /** Stride-2 kernel-2 tile: each input writes an adjacent pair, so the stores stay contiguous. */
    private static void transposePair(
            float[] in, float[] w, float[] out, int inBase, int wBase, int outBase,
            int ih0, int ih1, int inW, int outW
    ) {
        for (int kh = 0; kh < 2; kh++) {
            float w0 = w[wBase + kh * 2];
            float w1 = w[wBase + kh * 2 + 1];
            if (w0 == 0f && w1 == 0f) {
                continue;
            }
            for (int ih = ih0; ih < ih1; ih++) {
                int inRow = inBase + ih * inW;
                int outRow = outBase + (ih * 2 + kh) * outW;
                int iw = 0;
                for (; iw + 1 < inW; iw += 2) {
                    float v0 = in[inRow + iw];
                    float v1 = in[inRow + iw + 1];
                    int o = outRow + iw * 2;
                    out[o] += w0 * v0;
                    out[o + 1] += w1 * v0;
                    out[o + 2] += w0 * v1;
                    out[o + 3] += w1 * v1;
                }
                if (iw < inW) {
                    float v = in[inRow + iw];
                    out[outRow + iw * 2] += w0 * v;
                    out[outRow + iw * 2 + 1] += w1 * v;
                }
            }
        }
    }

    private static void pointwise(
            float[] in, float[] w, float[] bias, float[] out,
            int batch, int channels, int spatial, int m, int group, Run run
    ) {
        int ocpg = m / group;
        int icpg = channels / group;
        for (int n = 0; n < batch; n++) {
            for (int g = 0; g < group; g++) {
                int m0 = g * ocpg;
                for (int oc = 0; oc < ocpg; oc++) {
                    float b = bias == null ? 0f : bias[m0 + oc];
                    int off = (n * m + m0 + oc) * spatial;
                    Arrays.fill(out, off, off + spatial, b);
                }
                if (run.quantize) {
                    Gemm.int8(w, m0 * icpg, icpg,
                            in, (n * channels + g * icpg) * spatial, spatial,
                            out, (n * m + m0) * spatial, spatial,
                            ocpg, spatial, icpg);
                } else {
                    Blas.gemm(w, m0 * icpg, icpg,
                            in, (n * channels + g * icpg) * spatial, spatial,
                            out, (n * m + m0) * spatial, spatial,
                            ocpg, spatial, icpg,
                            run.vector, run.pool, run.threads);
                }
            }
        }
    }

    private static void direct(
            float[] in, float[] w, float[] bias, float[] out,
            int batch, int channels, int inH, int inW, int m, int group, int icpg,
            int kH, int kW, int sH, int sW, int dH, int dW, int padT, int padL, int outH, int outW, Run run
    ) {
        if (run.vector && packableStride2(kH, kW, sH, sW, dH, dW, padT, padL, inH, inW, outH, outW)) {
            directPackedStride2(in, w, bias, out, batch, channels, inH, inW, m, group, icpg, outH, outW, run);
            return;
        }
        long work = (long) m * icpg * kH * kW * outH * (long) outW;
        boolean parallel = run.pool != null && run.threads > 1 && work >= Blas.PARALLEL_FLOPS;
        boolean splitChannels = parallel && m >= run.threads;
        boolean splitRows = parallel && !splitChannels && outH >= 2;
        for (int n = 0; n < batch; n++) {
            if (!splitChannels && !splitRows) {
                directRange(in, w, bias, out, n, 0, m, 0, outH, channels, inH, inW, m, group, icpg,
                        kH, kW, sH, sW, dH, dW, padT, padL, outH, outW, run.vector);
                continue;
            }
            List<Callable<Void>> jobs = new ArrayList<>();
            ExecutorService pool = run.pool;
            final int nn = n;
            if (splitChannels) {
                int parts = Math.min(run.threads, m);
                int rows = (m + parts - 1) / parts;
                for (int p = 0; p < parts; p++) {
                    int a = p * rows;
                    int b = Math.min(m, a + rows);
                    if (a >= b) {
                        break;
                    }
                    final int fa = a;
                    final int fb = b;
                    jobs.add(() -> {
                        directRange(in, w, bias, out, nn, fa, fb, 0, outH, channels, inH, inW, m, group, icpg,
                                kH, kW, sH, sW, dH, dW, padT, padL, outH, outW, run.vector);
                        return null;
                    });
                }
            } else {
                int parts = Math.min(run.threads, outH);
                int span = (outH + parts - 1) / parts;
                for (int p = 0; p < parts; p++) {
                    int a = p * span;
                    int b = Math.min(outH, a + span);
                    if (a >= b) {
                        break;
                    }
                    final int fa = a;
                    final int fb = b;
                    jobs.add(() -> {
                        directRange(in, w, bias, out, nn, 0, m, fa, fb, channels, inH, inW, m, group, icpg,
                                kH, kW, sH, sW, dH, dW, padT, padL, outH, outW, run.vector);
                        return null;
                    });
                }
            }
            Blas.await(pool, jobs);
        }
    }

    /**
     * 3×3 stride 2 with pad 1 reads every other input pixel. Packing each row into evens then odds
     * turns the interior window into one contiguous pass. x = 0 and any partial row stay on the scalar taps.
     */
    private static boolean packableStride2(
            int kH, int kW, int sH, int sW, int dH, int dW, int padT, int padL,
            int inH, int inW, int outH, int outW
    ) {
        if (kH != 3 || kW != 3 || sH != 2 || sW != 2 || dH != 1 || dW != 1 || padT != 1 || padL != 1) {
            return false;
        }
        int even = (inW + 1) / 2;
        int odd = inW / 2;
        int nFast = Math.min(outW, Math.min(even, odd)) - 1;
        int ohFast = Math.min(outH, (inH - 2) / 2 + 1);
        return nFast >= 1 && ohFast > 1;
    }

    private static void directPackedStride2(
            float[] in, float[] w, float[] bias, float[] out,
            int batch, int channels, int inH, int inW, int m, int group, int icpg, int outH, int outW, Run run
    ) {
        int image = channels * inH * inW;
        int evenCount = (inW + 1) / 2;
        int oddCount = inW / 2;
        int nFast = Math.min(outW, Math.min(evenCount, oddCount)) - 1;
        int ohFast = Math.min(outH, (inH - 2) / 2 + 1);
        int ocpg = m / group;
        float[] pack = run.arena.allocF(image);
        try {
            for (int n = 0; n < batch; n++) {
                final int nn = n;
                int inBase = nn * image;
                split(run, channels * inH, (from, until) -> {
                    for (int row = from; row < until; row++) {
                        packRow(in, inBase + row * inW, pack, row * inW, inW, evenCount);
                    }
                });
                int fastRows = ohFast - 1;
                split(run, fastRows, (from, until) -> {
                    for (int k = from; k < until; k++) {
                        int oh = k + 1;
                        int ih0 = oh * 2 - 1;
                        for (int oc = 0; oc < m; oc++) {
                            int outRow = ((nn * m + oc) * outH + oh) * outW;
                            Arrays.fill(out, outRow, outRow + outW, bias == null ? 0f : bias[oc]);
                            int icBase = (oc / ocpg) * icpg;
                            int wOc = oc * icpg * 9;
                            for (int ic = 0; ic < icpg; ic++) {
                                int wChannel = wOc + ic * 9;
                                if (weightsZero(w, wChannel)) {
                                    continue;
                                }
                                int row0 = ((icBase + ic) * inH + ih0) * inW;
                                Blas.axpyPacked3(pack, row0, row0 + inW, row0 + 2 * inW, evenCount,
                                        out, outRow + 1, nFast, w, wChannel, true);
                            }
                        }
                    }
                });
                for (int oc = 0; oc < m; oc++) {
                    int plane = (n * m + oc) * outH * outW;
                    float b0 = bias == null ? 0f : bias[oc];
                    Arrays.fill(out, plane, plane + outW, b0);
                    if (ohFast < outH) {
                        Arrays.fill(out, plane + ohFast * outW, plane + outH * outW, b0);
                    }
                    int icBase = (oc / ocpg) * icpg;
                    for (int ic = 0; ic < icpg; ic++) {
                        int inChannel = inBase + ((icBase + ic) * inH) * inW;
                        addStride2Edges(in, inChannel, w, oc * icpg * 9 + ic * 9, out, plane,
                                inH, inW, outH, outW, ohFast, nFast);
                    }
                }
            }
        } finally {
            run.arena.freeF(pack);
        }
    }

    private static void packRow(float[] in, int src, float[] pack, int dst, int inW, int evenCount) {
        for (int i = 0; i < evenCount; i++) {
            pack[dst + i] = in[src + (i << 1)];
        }
        int oddDst = dst + evenCount;
        int oddCount = inW >>> 1;
        for (int i = 0; i < oddCount; i++) {
            pack[oddDst + i] = in[src + (i << 1) + 1];
        }
    }

    private static boolean weightsZero(float[] w, int off) {
        for (int i = 0; i < 9; i++) {
            if (w[off + i] != 0f) {
                return false;
            }
        }
        return true;
    }

    /** Columns and rows that the packed interior does not own. */
    private static void addStride2Edges(
            float[] in, int inChannel, float[] w, int wChannel, float[] out, int plane,
            int inH, int inW, int outH, int outW, int ohFast, int nFast
    ) {
        int fastOwEnd = 1 + nFast;
        for (int kh = 0; kh < 3; kh++) {
            for (int kw = 0; kw < 3; kw++) {
                float wv = w[wChannel + kh * 3 + kw];
                if (wv == 0f) {
                    continue;
                }
                for (int oh = 0; oh < outH; oh++) {
                    int ih = oh * 2 + kh - 1;
                    if (ih < 0 || ih >= inH) {
                        continue;
                    }
                    boolean partial = oh == 0 || oh >= ohFast;
                    int inRow = inChannel + ih * inW;
                    int outRow = plane + oh * outW;
                    addStride2Tap(in, inRow, inW, out, outRow, 0, partial ? outW : 1, kw, wv);
                    if (!partial && fastOwEnd < outW) {
                        addStride2Tap(in, inRow, inW, out, outRow, fastOwEnd, outW, kw, wv);
                    }
                }
            }
        }
    }

    private static void addStride2Tap(
            float[] in, int inRow, int inW, float[] out, int outRow, int ow0, int ow1, int kw, float wv
    ) {
        for (int ow = ow0; ow < ow1; ow++) {
            int iw = ow * 2 + kw - 1;
            if (iw >= 0 && iw < inW) {
                out[outRow + ow] += wv * in[inRow + iw];
            }
        }
    }

    private interface RowTask {
        void run(int from, int until);
    }

    private static void split(Run run, int n, RowTask task) {
        if (n <= 0) {
            return;
        }
        if (run.pool == null || run.threads < 2 || n < 2) {
            task.run(0, n);
            return;
        }
        int parts = Math.min(run.threads, n);
        int span = (n + parts - 1) / parts;
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int p = 0; p < parts; p++) {
            int a = p * span;
            int b = Math.min(n, a + span);
            if (a >= b) {
                break;
            }
            int fa = a;
            int fb = b;
            jobs.add(() -> {
                task.run(fa, fb);
                return null;
            });
        }
        Blas.await(run.pool, jobs);
    }

    private static void directRange(
            float[] in, float[] w, float[] bias, float[] out,
            int n, int oc0, int oc1, int oh0, int oh1, int channels, int inH, int inW, int m, int group, int icpg,
            int kH, int kW, int sH, int sW, int dH, int dW, int padT, int padL, int outH, int outW, boolean vector
    ) {
        int spatial = outH * outW;
        int ocpg = m / group;
        boolean width3 = kW == 3 && sW == 1 && dW == 1;
        for (int oc = oc0; oc < oc1; oc++) {
            int plane = (n * m + oc) * spatial;
            float b = bias == null ? 0f : bias[oc];
            Arrays.fill(out, plane + oh0 * outW, plane + oh1 * outW, b);
            int g = oc / ocpg;
            int icBase = g * icpg;
            for (int ic = 0; ic < icpg; ic++) {
                int inChannel = ((n * channels + icBase + ic) * inH) * inW;
                int wChannel = (oc * icpg + ic) * kH * kW;
                if (width3) {
                    convWidth3(in, w, out, inChannel, wChannel, plane, kH, sH, dH, padT, padL,
                            inH, inW, outW, oh0, oh1, vector);
                    continue;
                }
                for (int kh = 0; kh < kH; kh++) {
                    for (int kw = 0; kw < kW; kw++) {
                        float wv = w[wChannel + kh * kW + kw];
                        if (wv == 0f) {
                            continue;
                        }
                        for (int oh = oh0; oh < oh1; oh++) {
                            int ih = oh * sH + kh * dH - padT;
                            if (ih < 0 || ih >= inH) {
                                continue;
                            }
                            int inRow = inChannel + ih * inW;
                            int outRow = plane + oh * outW;
                            if (sW == 1 && dW == 1) {
                                int owStart = Math.max(0, padL - kw);
                                int owEnd = Math.min(outW, inW - kw + padL);
                                if (owEnd > owStart) {
                                    int iw0 = owStart + kw - padL;
                                    Blas.axpy(in, inRow + iw0, out, outRow + owStart, owEnd - owStart, wv, vector);
                                }
                            } else {
                                accumulateStrided(in, inRow, inW, out, outRow, outW, kw, sW, dW, padL, wv);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Width-3, stride-1, dilation-1 taps share one pass over the interior of the row. */
    private static void convWidth3(
            float[] in, float[] w, float[] out, int inChannel, int wChannel, int plane,
            int kH, int sH, int dH, int padT, int padL, int inH, int inW, int outW,
            int oh0, int oh1, boolean vector
    ) {
        if (vector && kH == 3 && sH == 1 && dH == 1) {
            int fused0 = Math.max(oh0, padT);
            int fused1 = Math.min(oh1, inH + padT - 2);
            if (fused1 > fused0) {
                if (oh0 < fused0) {
                    convWidth3Bands(in, w, out, inChannel, wChannel, plane, kH, sH, dH, padT, padL,
                            inH, inW, outW, oh0, fused0, vector);
                }
                for (int oh = fused0; oh < fused1; oh++) {
                    addWidth9(in, inChannel + (oh - padT) * inW, inW, out, plane + oh * outW, outW,
                            padL, w, wChannel, vector);
                }
                if (fused1 < oh1) {
                    convWidth3Bands(in, w, out, inChannel, wChannel, plane, kH, sH, dH, padT, padL,
                            inH, inW, outW, fused1, oh1, vector);
                }
                return;
            }
        }
        convWidth3Bands(in, w, out, inChannel, wChannel, plane, kH, sH, dH, padT, padL,
                inH, inW, outW, oh0, oh1, vector);
    }

    private static void convWidth3Bands(
            float[] in, float[] w, float[] out, int inChannel, int wChannel, int plane,
            int kH, int sH, int dH, int padT, int padL, int inH, int inW, int outW,
            int oh0, int oh1, boolean vector
    ) {
        for (int kh = 0; kh < kH; kh++) {
            float w0 = w[wChannel + kh * 3];
            float w1 = w[wChannel + kh * 3 + 1];
            float w2 = w[wChannel + kh * 3 + 2];
            if (w0 == 0f && w1 == 0f && w2 == 0f) {
                continue;
            }
            for (int oh = oh0; oh < oh1; oh++) {
                int ih = oh * sH + kh * dH - padT;
                if (ih < 0 || ih >= inH) {
                    continue;
                }
                addWidth3(in, inChannel + ih * inW, inW, out, plane + oh * outW, outW, padL, w0, w1, w2, vector);
            }
        }
    }

    /** Interior rows of a 3×3: three input rows update the output row once. */
    private static void addWidth9(
            float[] in, int row0, int inW, float[] out, int outRow, int outW, int padL,
            float[] w, int wOff, boolean vector
    ) {
        int start = 0;
        int end = outW;
        for (int kw = 0; kw < 3; kw++) {
            int ow0 = Math.max(0, padL - kw);
            int ow1 = Math.min(outW, inW - kw + padL);
            if (ow0 > start) {
                start = ow0;
            }
            if (ow1 < end) {
                end = ow1;
            }
        }
        if (end > start) {
            Blas.axpy9(in, row0 + (start - padL), inW, out, outRow + start, end - start, w, wOff, vector);
        }
        int edgeEnd = Math.min(outW, Math.max(end, start));
        for (int kh = 0; kh < 3; kh++) {
            int inRow = row0 + kh * inW;
            float w0 = w[wOff + kh * 3];
            float w1 = w[wOff + kh * 3 + 1];
            float w2 = w[wOff + kh * 3 + 2];
            for (int ow = 0; ow < start; ow++) {
                addWidth3At(in, inRow, inW, out, outRow, ow, padL, w0, w1, w2);
            }
            for (int ow = edgeEnd; ow < outW; ow++) {
                addWidth3At(in, inRow, inW, out, outRow, ow, padL, w0, w1, w2);
            }
        }
    }

    private static void addWidth3(
            float[] in, int inRow, int inW, float[] out, int outRow, int outW, int padL,
            float w0, float w1, float w2, boolean vector
    ) {
        int start = 0;
        int end = outW;
        for (int kw = 0; kw < 3; kw++) {
            int ow0 = Math.max(0, padL - kw);
            int ow1 = Math.min(outW, inW - kw + padL);
            if (ow0 > start) {
                start = ow0;
            }
            if (ow1 < end) {
                end = ow1;
            }
        }
        if (end > start) {
            int iw0 = start - padL;
            Blas.axpy3(in, inRow + iw0, inRow + iw0 + 1, inRow + iw0 + 2,
                    out, outRow + start, end - start, w0, w1, w2, vector);
        }
        int edgeEnd = Math.min(outW, Math.max(end, start));
        for (int ow = 0; ow < start; ow++) {
            addWidth3At(in, inRow, inW, out, outRow, ow, padL, w0, w1, w2);
        }
        for (int ow = edgeEnd; ow < outW; ow++) {
            addWidth3At(in, inRow, inW, out, outRow, ow, padL, w0, w1, w2);
        }
    }

    private static void addWidth3At(
            float[] in, int inRow, int inW, float[] out, int outRow, int ow, int padL,
            float w0, float w1, float w2
    ) {
        int iw0 = ow - padL;
        float sum = 0f;
        if (iw0 >= 0 && iw0 < inW) {
            sum += w0 * in[inRow + iw0];
        }
        if (iw0 + 1 >= 0 && iw0 + 1 < inW) {
            sum += w1 * in[inRow + iw0 + 1];
        }
        if (iw0 + 2 >= 0 && iw0 + 2 < inW) {
            sum += w2 * in[inRow + iw0 + 2];
        }
        out[outRow + ow] += sum;
    }

    private static void accumulateStrided(
            float[] in, int inRow, int inW, float[] out, int outRow, int outW,
            int kw, int sW, int dW, int padL, float wv
    ) {
        int step = sW;
        int iwBase = kw * dW - padL;
        int ow0;
        int ow1;
        if (iwBase >= 0) {
            ow0 = 0;
        } else {
            ow0 = (-iwBase + step - 1) / step;
        }
        if (iwBase >= inW) {
            return;
        }
        ow1 = (inW - 1 - iwBase) / step + 1;
        if (ow0 < 0) {
            ow0 = 0;
        }
        if (ow1 > outW) {
            ow1 = outW;
        }
        int inOff = inRow + iwBase;
        for (int ow = ow0; ow < ow1; ow++) {
            out[outRow + ow] += wv * in[inOff + ow * step];
        }
    }

    private static int convOut(int in, int k, int stride, int dil, int pad0, int pad1) {
        return (in + pad0 + pad1 - dil * (k - 1) - 1) / stride + 1;
    }

    private static int spatial(Node node, String key, int index, int def) {
        long[] v = node.longs(key, null);
        if (v == null || v.length == 0) {
            return def;
        }
        if (v.length == 1) {
            return (int) v[0];
        }
        return (int) v[Math.min(index, v.length - 1)];
    }

    private static int[] pads(Node node, int inH, int inW, int kH, int kW) {
        String auto = node.str("auto_pad", "NOTSET");
        if ("VALID".equals(auto)) {
            return new int[]{0, 0, 0, 0};
        }
        if ("SAME_UPPER".equals(auto) || "SAME_LOWER".equals(auto)) {
            int sH = spatial(node, "strides", 0, 1);
            int sW = spatial(node, "strides", 1, 1);
            int dH = spatial(node, "dilations", 0, 1);
            int dW = spatial(node, "dilations", 1, 1);
            int[] p = samePads(inH, kH, sH, dH, "SAME_LOWER".equals(auto));
            int[] q = samePads(inW, kW, sW, dW, "SAME_LOWER".equals(auto));
            return new int[]{p[0], q[0], p[1], q[1]};
        }
        long[] raw = node.longs("pads", null);
        if (raw == null || raw.length == 0) {
            return new int[]{0, 0, 0, 0};
        }
        if (raw.length == 4) {
            return new int[]{(int) raw[0], (int) raw[1], (int) raw[2], (int) raw[3]};
        }
        if (raw.length == 2) {
            return new int[]{(int) raw[0], (int) raw[1], (int) raw[0], (int) raw[1]};
        }
        throw new OcrException("Conv pads length " + raw.length);
    }

    private static int[] samePads(int in, int k, int stride, int dil, boolean lower) {
        int out = (int) Math.ceil(in / (double) stride);
        int eff = dil * (k - 1) + 1;
        int total = Math.max(0, (out - 1) * stride + eff - in);
        int begin = total / 2;
        int end = total - begin;
        if (lower) {
            return new int[]{end, begin};
        }
        return new int[]{begin, end};
    }
}
