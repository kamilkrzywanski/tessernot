package io.github.kamilkrzywanski.ocr.internal.runtime;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.util.Arrays;

final class NnOps {
    private NnOps() {
    }

    static Tensor batchNorm(Tensor x, Tensor scale, Tensor shift, Tensor mean, Tensor var, float eps, Run run) {
        if (x.rank() < 2) {
            throw new OcrException("BatchNormalization rank " + x.rank());
        }
        int channels = x.dims[1];
        if (scale.numel() != channels || shift.numel() != channels || mean.numel() != channels || var.numel() != channels) {
            throw new OcrException("BatchNormalization channel mismatch");
        }
        float[] a = new float[channels];
        float[] b = new float[channels];
        for (int c = 0; c < channels; c++) {
            double s = scale.f[c] / Math.sqrt((double) var.f[c] + eps);
            a[c] = (float) s;
            b[c] = (float) (shift.f[c] - mean.f[c] * s);
        }
        float[] out = run.arena.allocF(x.numel());
        int batch = x.dims[0];
        int after = ElementOps.product(x.dims, 2, x.rank());
        for (int n = 0; n < batch; n++) {
            for (int c = 0; c < channels; c++) {
                int off = (n * channels + c) * after;
                float sc = a[c];
                float sh = b[c];
                for (int i = 0; i < after; i++) {
                    out[off + i] = x.f[off + i] * sc + sh;
                }
            }
        }
        return Tensor.pooledF32(x.dims, out);
    }

    static Tensor globalAveragePool(Tensor x, Run run) {
        if (x.rank() < 2) {
            return ElementOps.copyFloat(x, run);
        }
        int[] outDims = x.dims.clone();
        for (int i = 2; i < outDims.length; i++) {
            outDims[i] = 1;
        }
        int batch = x.dims[0];
        int channels = x.dims[1];
        int spatial = ElementOps.product(x.dims, 2, x.rank());
        if (spatial == 0) {
            throw new OcrException("GlobalAveragePool over an empty map " + x.shapeString());
        }
        float[] out = run.arena.allocF(Tensor.numel(outDims));
        float inv = 1f / spatial;
        for (int n = 0; n < batch; n++) {
            for (int c = 0; c < channels; c++) {
                int off = (n * channels + c) * spatial;
                double sum = 0;
                for (int i = 0; i < spatial; i++) {
                    sum += x.f[off + i];
                }
                out[(n * channels + c)] = (float) (sum * inv);
            }
        }
        return Tensor.pooledF32(outDims, out);
    }

    static Tensor averagePool(
            Tensor x, int kH, int kW, int sH, int sW,
            int padT, int padL, int padB, int padR,
            boolean ceilMode, boolean countIncludePad, Run run
    ) {
        if (x.rank() != 4) {
            throw new OcrException("AveragePool expects NCHW");
        }
        int n = x.dims[0];
        int c = x.dims[1];
        int inH = x.dims[2];
        int inW = x.dims[3];
        int outH = poolOut(inH, kH, sH, padT, padB, ceilMode);
        int outW = poolOut(inW, kW, sW, padL, padR, ceilMode);
        float[] out = run.arena.allocF(n * c * outH * outW);
        for (int ni = 0; ni < n; ni++) {
            for (int ci = 0; ci < c; ci++) {
                int inBase = (ni * c + ci) * inH * inW;
                int outBase = (ni * c + ci) * outH * outW;
                for (int oh = 0; oh < outH; oh++) {
                    for (int ow = 0; ow < outW; ow++) {
                        int h0 = oh * sH - padT;
                        int w0 = ow * sW - padL;
                        double sum = 0;
                        int valid = 0;
                        for (int kh = 0; kh < kH; kh++) {
                            int ih = h0 + kh;
                            for (int kw = 0; kw < kW; kw++) {
                                int iw = w0 + kw;
                                boolean inside = ih >= 0 && iw >= 0 && ih < inH && iw < inW;
                                if (inside) {
                                    sum += x.f[inBase + ih * inW + iw];
                                    valid++;
                                } else if (countIncludePad && ih >= -padT && ih < inH + padB
                                        && iw >= -padL && iw < inW + padR) {
                                    // The padding counts; cells a ceil_mode window reaches beyond it do not.
                                    valid++;
                                }
                            }
                        }
                        out[outBase + oh * outW + ow] = valid == 0 ? 0f : (float) (sum / valid);
                    }
                }
            }
        }
        return Tensor.pooledF32(new int[]{n, c, outH, outW}, out);
    }

    static Tensor maxPool(
            Tensor x, int kH, int kW, int sH, int sW, int dH, int dW,
            int padT, int padL, int padB, int padR, boolean ceilMode, Run run
    ) {
        if (x.rank() != 4) {
            throw new OcrException("MaxPool expects NCHW");
        }
        int n = x.dims[0];
        int c = x.dims[1];
        int inH = x.dims[2];
        int inW = x.dims[3];
        int effH = dH * (kH - 1) + 1;
        int effW = dW * (kW - 1) + 1;
        int outH = poolOut(inH, effH, sH, padT, padB, ceilMode);
        int outW = poolOut(inW, effW, sW, padL, padR, ceilMode);
        float[] out = run.arena.allocF(n * c * outH * outW);
        if (kH == 2 && kW == 2 && sH == 1 && sW == 1 && dH == 1 && dW == 1 && padT == 0 && padL == 0
                && outH == inH - 1 + padB && outW == inW - 1 + padR && padB <= 1 && padR <= 1 && inW > 0) {
            maxPool2x2(x.f, out, n * c, inH, inW, outH, outW);
            return Tensor.pooledF32(new int[]{n, c, outH, outW}, out);
        }
        for (int ni = 0; ni < n; ni++) {
            for (int ci = 0; ci < c; ci++) {
                int inBase = (ni * c + ci) * inH * inW;
                int outBase = (ni * c + ci) * outH * outW;
                for (int oh = 0; oh < outH; oh++) {
                    for (int ow = 0; ow < outW; ow++) {
                        int h0 = oh * sH - padT;
                        int w0 = ow * sW - padL;
                        float max = Float.NEGATIVE_INFINITY;
                        boolean any = false;
                        for (int kh = 0; kh < kH; kh++) {
                            int ih = h0 + kh * dH;
                            if (ih < 0 || ih >= inH) {
                                continue;
                            }
                            for (int kw = 0; kw < kW; kw++) {
                                int iw = w0 + kw * dW;
                                if (iw < 0 || iw >= inW) {
                                    continue;
                                }
                                max = Math.max(max, x.f[inBase + ih * inW + iw]);
                                any = true;
                            }
                        }
                        out[outBase + oh * outW + ow] = any ? max : 0f;
                    }
                }
            }
        }
        return Tensor.pooledF32(new int[]{n, c, outH, outW}, out);
    }

    /**
     * A 2x2 window with stride 1, padded at most one row and column at the bottom and right
     * (SAME_UPPER in the recognition model). The maximum of two rows, then of two neighbours in
     * that row: the same elements as the general loop, so the same result, without its bounds
     * checks per window element.
     */
    private static void maxPool2x2(float[] in, float[] out, int planes, int inH, int inW, int outH, int outW) {
        float[] row = new float[inW];
        for (int p = 0; p < planes; p++) {
            int inBase = p * inH * inW;
            int outBase = p * outH * outW;
            for (int oh = 0; oh < outH; oh++) {
                int r0 = inBase + oh * inW;
                if (oh + 1 < inH) {
                    int r1 = r0 + inW;
                    for (int w = 0; w < inW; w++) {
                        row[w] = Math.max(in[r0 + w], in[r1 + w]);
                    }
                } else {
                    System.arraycopy(in, r0, row, 0, inW);
                }
                int o = outBase + oh * outW;
                int last = Math.min(outW, inW - 1);
                for (int w = 0; w < last; w++) {
                    out[o + w] = Math.max(row[w], row[w + 1]);
                }
                if (outW == inW) {
                    out[o + inW - 1] = row[inW - 1];
                }
            }
        }
    }

    private static int poolOut(int in, int kernel, int stride, int pad0, int pad1, boolean ceilMode) {
        double raw = (in + pad0 + pad1 - kernel) / (double) stride + 1;
        int out = ceilMode ? (int) Math.ceil(raw) : (int) Math.floor(raw);
        if (ceilMode && (out - 1) * stride >= in + pad0) {
            out--;
        }
        return Math.max(out, 0);
    }

    static Tensor resize(Tensor x, Tensor scales, Tensor sizes, String mode, String coord, String nearest, Run run) {
        if (!"nearest".equals(mode)) {
            throw new OcrException("unsupported resize mode " + mode);
        }
        int rank = x.rank();
        int[] outDims = new int[rank];
        float[] scale = new float[rank];
        if (sizes != null && sizes.numel() == rank) {
            for (int i = 0; i < rank; i++) {
                outDims[i] = (int) sizes.i[i];
                scale[i] = x.dims[i] == 0 ? 1f : outDims[i] / (float) x.dims[i];
            }
        } else if (scales != null && scales.numel() == rank) {
            for (int i = 0; i < rank; i++) {
                scale[i] = scales.f[i];
                outDims[i] = (int) Math.floor(x.dims[i] * scale[i]);
            }
        } else {
            throw new OcrException("resize needs scales or sizes");
        }
        for (int d : outDims) {
            if (d < 0) {
                throw new OcrException("negative resize output");
            }
        }
        int n = Tensor.numel(outDims);
        for (int d = 0; d < rank; d++) {
            if (outDims[d] > 0 && x.dims[d] <= 0) {
                throw new OcrException("resize empty input axis " + d);
            }
        }
        float[] out = run.arena.allocF(n);
        if (n == 0) {
            return Tensor.pooledF32(outDims, out);
        }
        if (rank == 4 && outDims[0] == x.dims[0] && outDims[1] == x.dims[1]) {
            resizeNchw(x.f, x.dims, out, outDims, scale, coord, nearest, run);
            return Tensor.pooledF32(outDims, out);
        }
        int[] inStride = Broadcast.strides(x.dims, rank);
        int[] outCoord = new int[rank];
        int[] src = new int[rank];
        for (int p = 0; p < n; p++) {
            ElementOps.unravel(p, outDims, outCoord);
            for (int d = 0; d < rank; d++) {
                src[d] = nearestIndex(outCoord[d], x.dims[d], outDims[d], scale[d], coord, nearest);
            }
            int off = 0;
            for (int d = 0; d < rank; d++) {
                off += src[d] * inStride[d];
            }
            out[p] = x.f[off];
        }
        return Tensor.pooledF32(outDims, out);
    }

    /**
     * Nearest resize of NCHW where batch and channels stay put. Index maps are built once per axis,
     * and an integer block scale (the PP-OCR 2/4/8 nearest upsample) replicates rows with arraycopy.
     */
    private static void resizeNchw(
            float[] in, int[] inDims, float[] out, int[] outDims, float[] scale,
            String coord, String nearest, Run run
    ) {
        int batch = inDims[0];
        int channels = inDims[1];
        int inH = inDims[2];
        int inW = inDims[3];
        int outH = outDims[2];
        int outW = outDims[3];
        int[] hMap = indexMap(inH, outH, scale[2], coord, nearest);
        int[] wMap = indexMap(inW, outW, scale[3], coord, nearest);
        int sh = blockFactor(hMap, inH);
        int sw = blockFactor(wMap, inW);
        int planes = batch * channels;
        if (run.pool != null && run.threads > 1 && (long) planes * outH * outW >= 65_536L && planes >= 2) {
            int parts = Math.min(run.threads, planes);
            int span = (planes + parts - 1) / parts;
            java.util.List<java.util.concurrent.Callable<Void>> jobs = new java.util.ArrayList<>();
            for (int p = 0; p < parts; p++) {
                int a = p * span;
                int b = Math.min(planes, a + span);
                if (a >= b) {
                    break;
                }
                final int fa = a;
                final int fb = b;
                jobs.add(() -> {
                    resizePlanes(in, out, fa, fb, inH, inW, outH, outW, hMap, wMap, sh, sw);
                    return null;
                });
            }
            Blas.await(run.pool, jobs);
            return;
        }
        resizePlanes(in, out, 0, planes, inH, inW, outH, outW, hMap, wMap, sh, sw);
    }

    private static void resizePlanes(
            float[] in, float[] out, int plane0, int plane1,
            int inH, int inW, int outH, int outW, int[] hMap, int[] wMap, int sh, int sw
    ) {
        int inHW = inH * inW;
        int outHW = outH * outW;
        for (int plane = plane0; plane < plane1; plane++) {
            int inBase = plane * inHW;
            int outBase = plane * outHW;
            if (sh > 0 && sw > 0) {
                replicatePlane(in, inBase, out, outBase, inH, inW, outW, sh, sw);
            } else {
                gatherPlane(in, inBase, out, outBase, inW, outH, outW, hMap, wMap);
            }
        }
    }

    private static void replicatePlane(
            float[] in, int inBase, float[] out, int outBase, int inH, int inW, int outW, int sh, int sw
    ) {
        for (int ih = 0; ih < inH; ih++) {
            int dst0 = outBase + (ih * sh) * outW;
            expandRow(in, inBase + ih * inW, out, dst0, inW, sw);
            for (int dup = 1; dup < sh; dup++) {
                System.arraycopy(out, dst0, out, dst0 + dup * outW, outW);
            }
        }
    }

    private static void expandRow(float[] in, int src, float[] out, int dst, int inW, int sw) {
        if (sw == 1) {
            System.arraycopy(in, src, out, dst, inW);
            return;
        }
        if (sw == 2) {
            for (int i = 0; i < inW; i++) {
                float v = in[src + i];
                int o = dst + i * 2;
                out[o] = v;
                out[o + 1] = v;
            }
            return;
        }
        if (sw == 4) {
            for (int i = 0; i < inW; i++) {
                float v = in[src + i];
                int o = dst + i * 4;
                out[o] = v;
                out[o + 1] = v;
                out[o + 2] = v;
                out[o + 3] = v;
            }
            return;
        }
        if (sw == 8) {
            for (int i = 0; i < inW; i++) {
                float v = in[src + i];
                int o = dst + i * 8;
                out[o] = v;
                out[o + 1] = v;
                out[o + 2] = v;
                out[o + 3] = v;
                out[o + 4] = v;
                out[o + 5] = v;
                out[o + 6] = v;
                out[o + 7] = v;
            }
            return;
        }
        int o = dst;
        for (int i = 0; i < inW; i++) {
            float v = in[src + i];
            for (int k = 0; k < sw; k++) {
                out[o++] = v;
            }
        }
    }

    private static void gatherPlane(
            float[] in, int inBase, float[] out, int outBase, int inW, int outH, int outW, int[] hMap, int[] wMap
    ) {
        boolean sameW = outW == inW;
        if (sameW) {
            for (int i = 0; i < outW; i++) {
                if (wMap[i] != i) {
                    sameW = false;
                    break;
                }
            }
        }
        for (int oh = 0; oh < outH; oh++) {
            int srcRow = inBase + hMap[oh] * inW;
            int dstRow = outBase + oh * outW;
            if (sameW) {
                System.arraycopy(in, srcRow, out, dstRow, outW);
            } else {
                for (int ow = 0; ow < outW; ow++) {
                    out[dstRow + ow] = in[srcRow + wMap[ow]];
                }
            }
        }
    }

    private static int[] indexMap(int inSize, int outSize, float scale, String coord, String nearest) {
        int[] map = new int[outSize];
        for (int i = 0; i < outSize; i++) {
            map[i] = nearestIndex(i, inSize, outSize, scale, coord, nearest);
        }
        return map;
    }

    /** Scale factor when every output index i reads input i / factor, otherwise 0. */
    private static int blockFactor(int[] map, int inSize) {
        if (inSize <= 0 || map.length == 0 || map.length % inSize != 0) {
            return 0;
        }
        int factor = map.length / inSize;
        for (int i = 0; i < map.length; i++) {
            if (map[i] != i / factor) {
                return 0;
            }
        }
        return factor;
    }

    private static int nearestIndex(int outIndex, int inSize, int outSize, float scale, String coord, String nearest) {
        if (inSize <= 1) {
            return 0;
        }
        float inCoord = switch (coord) {
            case "asymmetric" -> outIndex / scale;
            case "half_pixel" -> (outIndex + 0.5f) / scale - 0.5f;
            case "pytorch_half_pixel" -> outSize > 1 ? (outIndex + 0.5f) / scale - 0.5f : 0f;
            case "align_corners" -> outSize == 1 ? 0f : outIndex * (inSize - 1f) / (outSize - 1f);
            default -> throw new OcrException("unsupported resize coordinate mode " + coord);
        };
        int idx = switch (nearest) {
            case "floor" -> (int) Math.floor(inCoord);
            case "ceil" -> (int) Math.ceil(inCoord);
            case "round_prefer_floor" -> roundPrefer(inCoord, false);
            case "round_prefer_ceil" -> roundPrefer(inCoord, true);
            default -> throw new OcrException("unsupported nearest mode " + nearest);
        };
        if (idx < 0) {
            return 0;
        }
        return Math.min(idx, inSize - 1);
    }

    private static int roundPrefer(float x, boolean ceilOnTie) {
        float lower = (float) Math.floor(x);
        float upper = (float) Math.ceil(x);
        float dl = x - lower;
        float du = upper - x;
        if (dl < du) {
            return (int) lower;
        }
        if (du < dl) {
            return (int) upper;
        }
        return (int) (ceilOnTie ? upper : lower);
    }

    static Tensor matmul(Tensor a, Tensor b, Run run) {
        if (a.f == null || b.f == null) {
            throw new OcrException("MatMul expects float");
        }
        boolean aVec = a.rank() == 1;
        boolean bVec = b.rank() == 1;
        int[] ad = aVec ? new int[]{1, a.dims[0]} : a.dims;
        int[] bd = bVec ? new int[]{b.dims[0], 1} : b.dims;
        int m = ad[ad.length - 2];
        int k = ad[ad.length - 1];
        int k2 = bd[bd.length - 2];
        int n = bd[bd.length - 1];
        if (k != k2) {
            throw new OcrException("MatMul K " + k + " != " + k2);
        }
        int[] aPre = Arrays.copyOf(ad, ad.length - 2);
        int[] bPre = Arrays.copyOf(bd, bd.length - 2);
        int[] cPre = aPre.length == 0 ? bPre : bPre.length == 0 ? aPre : Broadcast.shape(aPre, bPre);
        if (aPre.length == 0 && bPre.length == 0) {
            cPre = new int[0];
        }
        int[] full = new int[cPre.length + 2];
        System.arraycopy(cPre, 0, full, 0, cPre.length);
        full[full.length - 2] = m;
        full[full.length - 1] = n;
        int[] outDims;
        if (!aVec && !bVec) {
            outDims = full;
        } else {
            int len = cPre.length + (aVec ? 0 : 1) + (bVec ? 0 : 1);
            outDims = new int[len];
            int cursor = 0;
            for (int dim : cPre) {
                outDims[cursor++] = dim;
            }
            if (!aVec) {
                outDims[cursor++] = m;
            }
            if (!bVec) {
                outDims[cursor] = n;
            }
        }
        int batch = Tensor.numel(cPre);
        int nOut = Tensor.numel(outDims);
        float[] out = run.arena.allocF(nOut);
        Arrays.fill(out, 0, nOut, 0f);
        int[] aStride = Broadcast.strides(aPre, cPre.length);
        int[] bStride = Broadcast.strides(bPre, cPre.length);
        int aMat = m * k;
        int bMat = k * n;
        int cMat = m * n;
        int[] coord = new int[cPre.length];
        for (int bi = 0; bi < batch; bi++) {
            ElementOps.unravel(bi, cPre, coord);
            int aBatch = Broadcast.offset(coord, aStride);
            int bBatch = Broadcast.offset(coord, bStride);
            Blas.gemm(a.f, aBatch * aMat, k, b.f, bBatch * bMat, n, out, bi * cMat, n,
                    m, n, k, run.vector, run.pool, run.threads);
        }
        return Tensor.pooledF32(outDims, out);
    }
}
