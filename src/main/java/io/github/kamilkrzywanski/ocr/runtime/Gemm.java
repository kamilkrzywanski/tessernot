package io.github.kamilkrzywanski.ocr.runtime;

/** Scalar GEMM and an optional int8 GEMM. C is accumulated, never cleared. */
final class Gemm {
    private Gemm() {
    }

    static void scalar(
            float[] a, int aOff, int lda,
            float[] b, int bOff, int ldb,
            float[] c, int cOff, int ldc,
            int m, int n, int k
    ) {
        int nb = 64;
        int kb = 64;
        for (int row = 0; row < m; row++) {
            int aRow = aOff + row * lda;
            int cRow = cOff + row * ldc;
            for (int n0 = 0; n0 < n; n0 += nb) {
                int n1 = Math.min(n, n0 + nb);
                for (int k0 = 0; k0 < k; k0 += kb) {
                    int k1 = Math.min(k, k0 + kb);
                    for (int kk = k0; kk < k1; kk++) {
                        float av = a[aRow + kk];
                        int bRow = bOff + kk * ldb;
                        int j = n0;
                        for (; j + 3 < n1; j += 4) {
                            c[cRow + j] += av * b[bRow + j];
                            c[cRow + j + 1] += av * b[bRow + j + 1];
                            c[cRow + j + 2] += av * b[bRow + j + 2];
                            c[cRow + j + 3] += av * b[bRow + j + 3];
                        }
                        for (; j < n1; j++) {
                            c[cRow + j] += av * b[bRow + j];
                        }
                    }
                }
            }
        }
    }

    /**
     * Per-row weight scale and one activation scale. Accurate enough for the opt-in path, not the default OCR path.
     * C is accumulated with the dequantized product.
     */
    static void int8(
            float[] a, int aOff, int lda,
            float[] b, int bOff, int ldb,
            float[] c, int cOff, int ldc,
            int m, int n, int k
    ) {
        float bMax = 0f;
        for (int kk = 0; kk < k; kk++) {
            int row = bOff + kk * ldb;
            for (int j = 0; j < n; j++) {
                bMax = Math.max(bMax, Math.abs(b[row + j]));
            }
        }
        float bScale = bMax == 0f ? 0f : bMax / 127f;
        byte[] bq = new byte[k * n];
        if (bScale != 0f) {
            for (int kk = 0; kk < k; kk++) {
                int row = bOff + kk * ldb;
                int dst = kk * n;
                for (int j = 0; j < n; j++) {
                    bq[dst + j] = quant(b[row + j], bScale);
                }
            }
        }
        for (int row = 0; row < m; row++) {
            int aRow = aOff + row * lda;
            float aMax = 0f;
            for (int kk = 0; kk < k; kk++) {
                aMax = Math.max(aMax, Math.abs(a[aRow + kk]));
            }
            float aScale = aMax == 0f ? 0f : aMax / 127f;
            if (aScale == 0f || bScale == 0f) {
                continue;
            }
            byte[] aq = new byte[k];
            for (int kk = 0; kk < k; kk++) {
                aq[kk] = quant(a[aRow + kk], aScale);
            }
            float outScale = aScale * bScale;
            int cRow = cOff + row * ldc;
            for (int j = 0; j < n; j++) {
                int acc = 0;
                for (int kk = 0; kk < k; kk++) {
                    acc += aq[kk] * bq[kk * n + j];
                }
                c[cRow + j] += acc * outScale;
            }
        }
    }

    private static byte quant(float v, float scale) {
        int q = Math.round(v / scale);
        if (q > 127) {
            q = 127;
        } else if (q < -127) {
            q = -127;
        }
        return (byte) q;
    }
}
