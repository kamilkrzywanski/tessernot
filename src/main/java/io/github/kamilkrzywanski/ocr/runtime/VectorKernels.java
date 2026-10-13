package io.github.kamilkrzywanski.ocr.runtime;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/** The only type that references {@code jdk.incubator.vector}. */
final class VectorKernels {
    static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;
    /** Panel scratch for {@link #gemmPacked}. One buffer per carrier thread; line workers run together. */
    private static final ThreadLocal<float[]> PANELS = ThreadLocal.withInitial(() -> new float[0]);

    private VectorKernels() {
    }

    public static boolean ready() {
        return SPECIES.length() >= 4;
    }

    static void gemm(
            float[] a, int aOff, int lda,
            float[] b, int bOff, int ldb,
            float[] c, int cOff, int ldc,
            int m, int n, int k
    ) {
        int row = 0;
        for (; row + 4 <= m; row += 4) {
            gemm4(a, aOff + row * lda, lda, b, bOff, ldb, c, cOff + row * ldc, ldc, n, k);
        }
        for (; row < m; row++) {
            gemmRow(a, aOff + row * lda, b, bOff, ldb, c, cOff + row * ldc, ldc, n, k);
        }
    }

    /**
     * Serial GEMM when each panel of B is re-read once per four rows of A.
     * Recognition runs one thread per line, and a line re-reads a stride of about a thousand
     * sixty times. The panel is gathered once into a contiguous buffer; {@link #gemm} then
     * keeps that buffer in cache. Split workers do not call this: the extra pass lost there.
     * Line threads run at the same time, so the buffer is per carrier thread.
     */
    static void gemmPacked(
            float[] a, int aOff, int lda,
            float[] b, int bOff, int ldb,
            float[] c, int cOff, int ldc,
            int m, int n, int k
    ) {
        int pw = SPECIES.length() * 2;
        float[] pack = panel(k * pw);
        int j = 0;
        for (; j + pw <= n; j += pw) {
            packPanel(b, bOff, ldb, pack, j, pw, k);
            gemm(a, aOff, lda, pack, 0, pw, c, cOff + j, ldc, m, pw, k);
        }
        if (j < n) {
            int width = n - j;
            packPanel(b, bOff, ldb, pack, j, width, k);
            gemm(a, aOff, lda, pack, 0, width, c, cOff + j, ldc, m, width, k);
        }
    }

    private static float[] panel(int need) {
        float[] pack = PANELS.get();
        if (pack.length < need) {
            pack = new float[need];
            PANELS.set(pack);
        }
        return pack;
    }

    private static void packPanel(float[] b, int bOff, int ldb, float[] pack, int j, int width, int k) {
        int lanes = SPECIES.length();
        for (int kk = 0; kk < k; kk++) {
            int src = bOff + kk * ldb + j;
            int dst = kk * width;
            int col = 0;
            for (; col + lanes <= width; col += lanes) {
                FloatVector.fromArray(SPECIES, b, src + col).intoArray(pack, dst + col);
            }
            for (; col < width; col++) {
                pack[dst + col] = b[src + col];
            }
        }
    }

    /**
     * Four rows share each load of B. Two vector columns stay in registers for the whole K
     * reduction, in the same FMA order as a single output element of {@link #gemmRow}.
     */
    private static void gemm4(
            float[] a, int a0, int lda,
            float[] b, int bOff, int ldb,
            float[] c, int c0, int ldc,
            int n, int k
    ) {
        int lanes = SPECIES.length();
        int a1 = a0 + lda;
        int a2 = a1 + lda;
        int a3 = a2 + lda;
        int c1 = c0 + ldc;
        int c2 = c1 + ldc;
        int c3 = c2 + ldc;
        int j = 0;
        int two = lanes * 2;
        for (; j + two <= n; j += two) {
            gemm4x2(a, a0, a1, a2, a3, b, bOff, ldb, c, c0, c1, c2, c3, j, k, lanes);
        }
        for (; j + lanes <= n; j += lanes) {
            gemm4x1(a, a0, a1, a2, a3, b, bOff, ldb, c, c0, c1, c2, c3, j, k);
        }
        for (; j < n; j++) {
            int kk = 0;
            for (; kk + 3 < k; kk += 4) {
                int bBase = bOff + kk * ldb;
                scalar4(a, a0, b, bBase, c, c0, ldb, kk, j);
                scalar4(a, a1, b, bBase, c, c1, ldb, kk, j);
                scalar4(a, a2, b, bBase, c, c2, ldb, kk, j);
                scalar4(a, a3, b, bBase, c, c3, ldb, kk, j);
            }
            for (; kk < k; kk++) {
                float bv = b[bOff + kk * ldb + j];
                c[c0 + j] += a[a0 + kk] * bv;
                c[c1 + j] += a[a1 + kk] * bv;
                c[c2 + j] += a[a2 + kk] * bv;
                c[c3 + j] += a[a3 + kk] * bv;
            }
        }
    }

    private static void gemm4x2(
            float[] a, int a0, int a1, int a2, int a3,
            float[] b, int bOff, int ldb,
            float[] c, int c0, int c1, int c2, int c3,
            int j, int k, int lanes
    ) {
        int j1 = j + lanes;
        var c00 = FloatVector.fromArray(SPECIES, c, c0 + j);
        var c01 = FloatVector.fromArray(SPECIES, c, c0 + j1);
        var c10 = FloatVector.fromArray(SPECIES, c, c1 + j);
        var c11 = FloatVector.fromArray(SPECIES, c, c1 + j1);
        var c20 = FloatVector.fromArray(SPECIES, c, c2 + j);
        var c21 = FloatVector.fromArray(SPECIES, c, c2 + j1);
        var c30 = FloatVector.fromArray(SPECIES, c, c3 + j);
        var c31 = FloatVector.fromArray(SPECIES, c, c3 + j1);
        for (int kk = 0; kk < k; kk++) {
            int bRow = bOff + kk * ldb;
            var b0 = FloatVector.fromArray(SPECIES, b, bRow + j);
            var b1 = FloatVector.fromArray(SPECIES, b, bRow + j1);
            var a0v = FloatVector.broadcast(SPECIES, a[a0 + kk]);
            var a1v = FloatVector.broadcast(SPECIES, a[a1 + kk]);
            var a2v = FloatVector.broadcast(SPECIES, a[a2 + kk]);
            var a3v = FloatVector.broadcast(SPECIES, a[a3 + kk]);
            c00 = a0v.fma(b0, c00);
            c01 = a0v.fma(b1, c01);
            c10 = a1v.fma(b0, c10);
            c11 = a1v.fma(b1, c11);
            c20 = a2v.fma(b0, c20);
            c21 = a2v.fma(b1, c21);
            c30 = a3v.fma(b0, c30);
            c31 = a3v.fma(b1, c31);
        }
        c00.intoArray(c, c0 + j);
        c01.intoArray(c, c0 + j1);
        c10.intoArray(c, c1 + j);
        c11.intoArray(c, c1 + j1);
        c20.intoArray(c, c2 + j);
        c21.intoArray(c, c2 + j1);
        c30.intoArray(c, c3 + j);
        c31.intoArray(c, c3 + j1);
    }

    private static void gemm4x1(
            float[] a, int a0, int a1, int a2, int a3,
            float[] b, int bOff, int ldb,
            float[] c, int c0, int c1, int c2, int c3,
            int j, int k
    ) {
        var c0v = FloatVector.fromArray(SPECIES, c, c0 + j);
        var c1v = FloatVector.fromArray(SPECIES, c, c1 + j);
        var c2v = FloatVector.fromArray(SPECIES, c, c2 + j);
        var c3v = FloatVector.fromArray(SPECIES, c, c3 + j);
        for (int kk = 0; kk < k; kk++) {
            var bv = FloatVector.fromArray(SPECIES, b, bOff + kk * ldb + j);
            c0v = FloatVector.broadcast(SPECIES, a[a0 + kk]).fma(bv, c0v);
            c1v = FloatVector.broadcast(SPECIES, a[a1 + kk]).fma(bv, c1v);
            c2v = FloatVector.broadcast(SPECIES, a[a2 + kk]).fma(bv, c2v);
            c3v = FloatVector.broadcast(SPECIES, a[a3 + kk]).fma(bv, c3v);
        }
        c0v.intoArray(c, c0 + j);
        c1v.intoArray(c, c1 + j);
        c2v.intoArray(c, c2 + j);
        c3v.intoArray(c, c3 + j);
    }

    private static void scalar4(float[] a, int aRow, float[] b, int bBase, float[] c, int cRow, int ldb, int kk, int j) {
        c[cRow + j] += a[aRow + kk] * b[bBase + j]
                + a[aRow + kk + 1] * b[bBase + ldb + j]
                + a[aRow + kk + 2] * b[bBase + 2 * ldb + j]
                + a[aRow + kk + 3] * b[bBase + 3 * ldb + j];
    }

    private static void gemmRow(
            float[] a, int aRow,
            float[] b, int bOff, int ldb,
            float[] c, int cRow, int ldc,
            int n, int k
    ) {
        int lanes = SPECIES.length();
        int tileN = Math.max(lanes * 8, 64);
        for (int n0 = 0; n0 < n; n0 += tileN) {
            int n1 = Math.min(n, n0 + tileN);
            int aligned = n0 + ((n1 - n0) / lanes) * lanes;
            int kk = 0;
            for (; kk + 3 < k; kk += 4) {
                var a0 = FloatVector.broadcast(SPECIES, a[aRow + kk]);
                var a1 = FloatVector.broadcast(SPECIES, a[aRow + kk + 1]);
                var a2 = FloatVector.broadcast(SPECIES, a[aRow + kk + 2]);
                var a3 = FloatVector.broadcast(SPECIES, a[aRow + kk + 3]);
                int b0 = bOff + kk * ldb;
                int b1 = b0 + ldb;
                int b2 = b1 + ldb;
                int b3 = b2 + ldb;
                for (int j = n0; j < aligned; j += lanes) {
                    var cv = FloatVector.fromArray(SPECIES, c, cRow + j);
                    cv = a0.fma(FloatVector.fromArray(SPECIES, b, b0 + j), cv);
                    cv = a1.fma(FloatVector.fromArray(SPECIES, b, b1 + j), cv);
                    cv = a2.fma(FloatVector.fromArray(SPECIES, b, b2 + j), cv);
                    cv = a3.fma(FloatVector.fromArray(SPECIES, b, b3 + j), cv);
                    cv.intoArray(c, cRow + j);
                }
                for (int j = aligned; j < n1; j++) {
                    float sum = a[aRow + kk] * b[b0 + j]
                            + a[aRow + kk + 1] * b[b1 + j]
                            + a[aRow + kk + 2] * b[b2 + j]
                            + a[aRow + kk + 3] * b[b3 + j];
                    c[cRow + j] += sum;
                }
            }
            for (; kk < k; kk++) {
                var av = FloatVector.broadcast(SPECIES, a[aRow + kk]);
                int bRow = bOff + kk * ldb;
                for (int j = n0; j < aligned; j += lanes) {
                    var cv = FloatVector.fromArray(SPECIES, c, cRow + j);
                    av.fma(FloatVector.fromArray(SPECIES, b, bRow + j), cv).intoArray(c, cRow + j);
                }
                float as = a[aRow + kk];
                for (int j = aligned; j < n1; j++) {
                    c[cRow + j] += as * b[bRow + j];
                }
            }
        }
    }

    static void axpy(float[] in, int inOff, float[] out, int outOff, int n, float scale) {
        int lanes = SPECIES.length();
        var sv = FloatVector.broadcast(SPECIES, scale);
        int i = 0;
        for (; i + lanes <= n; i += lanes) {
            var xv = FloatVector.fromArray(SPECIES, in, inOff + i);
            var yv = FloatVector.fromArray(SPECIES, out, outOff + i);
            sv.fma(xv, yv).intoArray(out, outOff + i);
        }
        for (; i < n; i++) {
            out[outOff + i] += scale * in[inOff + i];
        }
    }

    /** One pass over a row: out += s0*in0 + s1*in1 + s2*in2. The three input windows have length {@code n}. */
    static void axpy3(
            float[] in, int i0, int i1, int i2, float[] out, int outOff, int n,
            float s0, float s1, float s2
    ) {
        int lanes = SPECIES.length();
        var v0 = FloatVector.broadcast(SPECIES, s0);
        var v1 = FloatVector.broadcast(SPECIES, s1);
        var v2 = FloatVector.broadcast(SPECIES, s2);
        int i = 0;
        for (; i + lanes <= n; i += lanes) {
            var y = FloatVector.fromArray(SPECIES, out, outOff + i);
            y = v0.fma(FloatVector.fromArray(SPECIES, in, i0 + i), y);
            y = v1.fma(FloatVector.fromArray(SPECIES, in, i1 + i), y);
            y = v2.fma(FloatVector.fromArray(SPECIES, in, i2 + i), y);
            y.intoArray(out, outOff + i);
        }
        for (; i < n; i++) {
            out[outOff + i] += s0 * in[i0 + i] + s1 * in[i1 + i] + s2 * in[i2 + i];
        }
    }

    /** 3×3 window in one pass. FMA order matches three {@link #axpy3} calls, top row first. */
    static void axpy9(
            float[] in, int origin, int inW, float[] out, int outOff, int n, float[] w, int wOff
    ) {
        int lanes = SPECIES.length();
        var s0 = FloatVector.broadcast(SPECIES, w[wOff]);
        var s1 = FloatVector.broadcast(SPECIES, w[wOff + 1]);
        var s2 = FloatVector.broadcast(SPECIES, w[wOff + 2]);
        var s3 = FloatVector.broadcast(SPECIES, w[wOff + 3]);
        var s4 = FloatVector.broadcast(SPECIES, w[wOff + 4]);
        var s5 = FloatVector.broadcast(SPECIES, w[wOff + 5]);
        var s6 = FloatVector.broadcast(SPECIES, w[wOff + 6]);
        var s7 = FloatVector.broadcast(SPECIES, w[wOff + 7]);
        var s8 = FloatVector.broadcast(SPECIES, w[wOff + 8]);
        int row1 = origin + inW;
        int row2 = row1 + inW;
        int i = 0;
        for (; i + lanes <= n; i += lanes) {
            var y = FloatVector.fromArray(SPECIES, out, outOff + i);
            y = s0.fma(FloatVector.fromArray(SPECIES, in, origin + i), y);
            y = s1.fma(FloatVector.fromArray(SPECIES, in, origin + 1 + i), y);
            y = s2.fma(FloatVector.fromArray(SPECIES, in, origin + 2 + i), y);
            y = s3.fma(FloatVector.fromArray(SPECIES, in, row1 + i), y);
            y = s4.fma(FloatVector.fromArray(SPECIES, in, row1 + 1 + i), y);
            y = s5.fma(FloatVector.fromArray(SPECIES, in, row1 + 2 + i), y);
            y = s6.fma(FloatVector.fromArray(SPECIES, in, row2 + i), y);
            y = s7.fma(FloatVector.fromArray(SPECIES, in, row2 + 1 + i), y);
            y = s8.fma(FloatVector.fromArray(SPECIES, in, row2 + 2 + i), y);
            y.intoArray(out, outOff + i);
        }
        for (; i < n; i++) {
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
     * 3×3 stride-2 window over an even/odd packed row. Even pixels sit in
     * {@code [row, row + evenCount)} and odd pixels follow them. Output index {@code i} is spatial x = i + 1.
     * FMA order is kernel-row, then kw 0, 1, 2, matching three {@link #axpy3} calls.
     * Caller keeps every load inside the packed row: {@code n <= evenCount - 1} and the odd side is at least {@code n + 1}.
     */
    static void axpyPacked3(
            float[] in, int row0, int row1, int row2, int evenCount,
            float[] out, int outOff, int n, float[] w, int wOff
    ) {
        int lanes = SPECIES.length();
        var s00 = FloatVector.broadcast(SPECIES, w[wOff]);
        var s01 = FloatVector.broadcast(SPECIES, w[wOff + 1]);
        var s02 = FloatVector.broadcast(SPECIES, w[wOff + 2]);
        var s10 = FloatVector.broadcast(SPECIES, w[wOff + 3]);
        var s11 = FloatVector.broadcast(SPECIES, w[wOff + 4]);
        var s12 = FloatVector.broadcast(SPECIES, w[wOff + 5]);
        var s20 = FloatVector.broadcast(SPECIES, w[wOff + 6]);
        var s21 = FloatVector.broadcast(SPECIES, w[wOff + 7]);
        var s22 = FloatVector.broadcast(SPECIES, w[wOff + 8]);
        int odd0 = row0 + evenCount;
        int odd1 = row1 + evenCount;
        int odd2 = row2 + evenCount;
        int i = 0;
        for (; i + lanes <= n; i += lanes) {
            var y = FloatVector.fromArray(SPECIES, out, outOff + i);
            y = s00.fma(FloatVector.fromArray(SPECIES, in, odd0 + i), y);
            y = s01.fma(FloatVector.fromArray(SPECIES, in, row0 + 1 + i), y);
            y = s02.fma(FloatVector.fromArray(SPECIES, in, odd0 + 1 + i), y);
            y = s10.fma(FloatVector.fromArray(SPECIES, in, odd1 + i), y);
            y = s11.fma(FloatVector.fromArray(SPECIES, in, row1 + 1 + i), y);
            y = s12.fma(FloatVector.fromArray(SPECIES, in, odd1 + 1 + i), y);
            y = s20.fma(FloatVector.fromArray(SPECIES, in, odd2 + i), y);
            y = s21.fma(FloatVector.fromArray(SPECIES, in, row2 + 1 + i), y);
            y = s22.fma(FloatVector.fromArray(SPECIES, in, odd2 + 1 + i), y);
            y.intoArray(out, outOff + i);
        }
        for (; i < n; i++) {
            float acc = out[outOff + i];
            acc += w[wOff] * in[odd0 + i] + w[wOff + 1] * in[row0 + 1 + i] + w[wOff + 2] * in[odd0 + 1 + i];
            acc += w[wOff + 3] * in[odd1 + i] + w[wOff + 4] * in[row1 + 1 + i] + w[wOff + 5] * in[odd1 + 1 + i];
            acc += w[wOff + 6] * in[odd2 + i] + w[wOff + 7] * in[row2 + 1 + i] + w[wOff + 8] * in[odd2 + 1 + i];
            out[outOff + i] = acc;
        }
    }

    /** Repeats {@code tile} across {@code big}. {@code tileOnLeft} is the left operand of {@code op}. */
    static void binaryTiled(float[] big, float[] tile, float[] out, int n, int tileLen, int op, boolean tileOnLeft) {
        int lanes = SPECIES.length();
        int aligned = tileLen - Math.floorMod(tileLen, lanes);
        int rows = n / tileLen;
        for (int row = 0; row < rows; row++) {
            int off = row * tileLen;
            int j = 0;
            for (; j < aligned; j += lanes) {
                var bv = FloatVector.fromArray(SPECIES, big, off + j);
                var tv = FloatVector.fromArray(SPECIES, tile, j);
                apply(tileOnLeft ? tv : bv, tileOnLeft ? bv : tv, op).intoArray(out, off + j);
            }
            for (; j < tileLen; j++) {
                float t = tile[j];
                float v = big[off + j];
                out[off + j] = scalarOp(tileOnLeft ? t : v, tileOnLeft ? v : t, op);
            }
        }
        for (int i = rows * tileLen; i < n; i++) {
            float t = tile[i % tileLen];
            float v = big[i];
            out[i] = scalarOp(tileOnLeft ? t : v, tileOnLeft ? v : t, op);
        }
    }

    static void binary(float[] a, int aOff, float[] b, int bOff, float[] out, int outOff, int n, int op) {
        int lanes = SPECIES.length();
        int i = 0;
        for (; i + lanes <= n; i += lanes) {
            var av = FloatVector.fromArray(SPECIES, a, aOff + i);
            var bv = FloatVector.fromArray(SPECIES, b, bOff + i);
            apply(av, bv, op).intoArray(out, outOff + i);
        }
        for (; i < n; i++) {
            out[outOff + i] = scalarOp(a[aOff + i], b[bOff + i], op);
        }
    }

    static void scalarBroadcast(float[] a, int aOff, float s, float[] out, int outOff, int n, int op) {
        int lanes = SPECIES.length();
        var sv = FloatVector.broadcast(SPECIES, s);
        int i = 0;
        for (; i + lanes <= n; i += lanes) {
            var av = FloatVector.fromArray(SPECIES, a, aOff + i);
            apply(av, sv, op).intoArray(out, outOff + i);
        }
        for (; i < n; i++) {
            out[outOff + i] = scalarOp(a[aOff + i], s, op);
        }
    }

    static void scalarLeft(float s, float[] a, int aOff, float[] out, int outOff, int n, int op) {
        int lanes = SPECIES.length();
        var sv = FloatVector.broadcast(SPECIES, s);
        int i = 0;
        for (; i + lanes <= n; i += lanes) {
            var av = FloatVector.fromArray(SPECIES, a, aOff + i);
            apply(sv, av, op).intoArray(out, outOff + i);
        }
        for (; i < n; i++) {
            out[outOff + i] = scalarOp(s, a[aOff + i], op);
        }
    }

    private static FloatVector apply(FloatVector left, FloatVector right, int op) {
        return switch (op) {
            case 0 -> left.add(right);
            case 1 -> left.sub(right);
            case 2 -> left.mul(right);
            default -> left.div(right);
        };
    }

    static void activate(float[] y, int off, int n, String act, float alpha, float beta) {
        int lanes = SPECIES.length();
        int i = 0;
        var zero = FloatVector.zero(SPECIES);
        var one = FloatVector.broadcast(SPECIES, 1f);
        for (; i + lanes <= n; i += lanes) {
            var x = FloatVector.fromArray(SPECIES, y, off + i);
            var r = switch (act) {
                case "Relu" -> x.max(zero);
                case "Sigmoid" -> sigmoid(x);
                case "HardSigmoid" -> hardSigmoid(x, alpha, beta, zero, one);
                case "HardSwish" -> x.mul(hardSigmoid(x, alpha, beta, zero, one));
                default -> x;
            };
            r.intoArray(y, off + i);
        }
        for (; i < n; i++) {
            y[off + i] = Activations.apply(y[off + i], act, alpha, beta);
        }
    }

    /** Same FMA order as {@link #activate}, then {@code y * outScale + outShift} before the store. */
    static void activateAffine(
            float[] y, int off, int n, String act, float alpha, float beta, float outScale, float outShift
    ) {
        int lanes = SPECIES.length();
        int i = 0;
        var zero = FloatVector.zero(SPECIES);
        var one = FloatVector.broadcast(SPECIES, 1f);
        var scale = FloatVector.broadcast(SPECIES, outScale);
        var shift = FloatVector.broadcast(SPECIES, outShift);
        for (; i + lanes <= n; i += lanes) {
            var x = FloatVector.fromArray(SPECIES, y, off + i);
            var r = switch (act) {
                case "Relu" -> x.max(zero);
                case "Sigmoid" -> sigmoid(x);
                case "HardSigmoid" -> hardSigmoid(x, alpha, beta, zero, one);
                case "HardSwish" -> x.mul(hardSigmoid(x, alpha, beta, zero, one));
                case null, default -> x;
            };
            r.mul(scale).add(shift).intoArray(y, off + i);
        }
        for (; i < n; i++) {
            float v = act == null ? y[off + i] : Activations.apply(y[off + i], act, alpha, beta);
            y[off + i] = v * outScale + outShift;
        }
    }

    private static FloatVector hardSigmoid(FloatVector x, float alpha, float beta, FloatVector zero, FloatVector one) {
        return x.mul(alpha).add(beta).max(zero).min(one);
    }

    /**
     * Same Abramowitz polynomial as {@link ElementOps#erf(float)}, in float.
     * PP-OCRv6 evaluates this on every GELU, and the scalar double loop was
     * the largest new cost against the latin recognizer.
     */
    static void erf(float[] in, float[] out, int n) {
        int lanes = SPECIES.length();
        var p = FloatVector.broadcast(SPECIES, 0.3275911f);
        var one = FloatVector.broadcast(SPECIES, 1f);
        var a5 = FloatVector.broadcast(SPECIES, 1.061405429f);
        var a4 = FloatVector.broadcast(SPECIES, -1.453152027f);
        var a3 = FloatVector.broadcast(SPECIES, 1.421413741f);
        var a2 = FloatVector.broadcast(SPECIES, -0.284496736f);
        var a1 = FloatVector.broadcast(SPECIES, 0.254829592f);
        var zero = FloatVector.zero(SPECIES);
        int i = 0;
        for (; i + lanes <= n; i += lanes) {
            var x = FloatVector.fromArray(SPECIES, in, i);
            var ax = x.abs();
            var t = one.div(one.add(p.mul(ax)));
            var poly = a5.fma(t, a4);
            poly = poly.fma(t, a3);
            poly = poly.fma(t, a2);
            poly = poly.fma(t, a1);
            poly = poly.mul(t);
            var exp = ax.mul(ax).neg().lanewise(VectorOperators.EXP);
            var mag = one.sub(poly.mul(exp));
            mag.blend(mag.neg(), x.compare(VectorOperators.LT, zero)).intoArray(out, i);
        }
        for (; i < n; i++) {
            out[i] = ElementOps.erf(in[i]);
        }
    }

    private static FloatVector sigmoid(FloatVector x) {
        var lo = FloatVector.broadcast(SPECIES, -80f);
        var hi = FloatVector.broadcast(SPECIES, 80f);
        var clamped = x.max(lo).min(hi);
        var one = FloatVector.broadcast(SPECIES, 1f);
        var e = clamped.neg().lanewise(VectorOperators.EXP);
        return one.div(one.add(e));
    }

    private static float scalarOp(float a, float b, int op) {
        return switch (op) {
            case 0 -> a + b;
            case 1 -> a - b;
            case 2 -> a * b;
            default -> a / b;
        };
    }
}
