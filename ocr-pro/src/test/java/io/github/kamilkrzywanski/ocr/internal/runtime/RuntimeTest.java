package io.github.kamilkrzywanski.ocr.internal.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.kamilkrzywanski.ocr.ModelFiles;
import io.github.kamilkrzywanski.ocr.OcrException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RuntimeTest {
    @Test
    void erfMatchesTheAbramowitzApproximation() {
        float[] x = new float[43];
        for (int i = 0; i < x.length; i++) {
            x[i] = (i - 21) * 0.2f;
        }
        x[0] = 0f;
        x[1] = 1f;
        x[2] = -1f;
        x[3] = 2f;
        x[4] = -0.5f;
        Tensor out = exec(11, node("Erf", "e", "x", "y"), Map.of(),
                Map.of("x", Tensor.f32(new int[]{x.length}, x)), false);
        assertEquals(0f, out.f[0], 2e-7f);
        assertEquals(0.84270079f, out.f[1], 2e-6f);
        assertEquals(-0.84270079f, out.f[2], 2e-6f);
        assertEquals(0.99532227f, out.f[3], 2e-6f);
        assertEquals(-0.52049988f, out.f[4], 2e-6f);
        if (VectorSupport.available()) {
            Tensor wide = exec(11, node("Erf", "e", "x", "y"), Map.of(),
                    Map.of("x", Tensor.f32(new int[]{x.length}, x)), true);
            assertClose(out.f, wide.f, 2e-5f);
        }
    }

    @Test
    void maxPoolSameUpperKeepsTheLargerNeighbor() {
        Tensor x = Tensor.f32(new int[]{1, 1, 1, 3}, new float[]{1f, 8f, 2f});
        Tensor out = exec(11, node("MaxPool", "m", attrs(
                Attr.ints("kernel_shape", 2, 2),
                Attr.ints("strides", 1, 1),
                Attr.of("auto_pad", "SAME_UPPER")), "x", "y"),
                Map.of(), Map.of("x", x), false);
        assertArrayEquals(new int[]{1, 1, 1, 3}, out.dims);
        assertEquals(8f, out.f[0], 0f);
        assertEquals(8f, out.f[1], 0f);
        assertEquals(2f, out.f[2], 0f);
    }

    @Test
    void conv1x1ScalarAndVector() {
        float[] y = conv1x1(false);
        assertEquals(16f, y[0], 1e-4);
        if (VectorSupport.available()) {
            assertEquals(16f, conv1x1(true)[0], 1e-4);
        }
    }

    @Test
    void conv3x3Pad1() {
        float[] x = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        float[] w = new float[9];
        java.util.Arrays.fill(w, 1f);
        Tensor out = exec(14, node("Conv", "c", attrs(Attr.ints("pads", 1, 1, 1, 1)), "x", "w", "y"),
                Map.of("w", Tensor.f32(new int[]{1, 1, 3, 3}, w)),
                Map.of("x", Tensor.f32(new int[]{1, 1, 3, 3}, x)),
                false);
        assertEquals(3, out.dims[2]);
        assertEquals(3, out.dims[3]);
        assertEquals(12f, out.f[0], 1e-4);
        assertEquals(45f, out.f[4], 1e-4);
        if (VectorSupport.available()) {
            float[] wide = sequence(8 * 16, 0.1f);
            Tensor vector = exec(14, node("Conv", "c", attrs(Attr.ints("pads", 1, 1, 1, 1)), "x", "w", "y"),
                    Map.of("w", Tensor.f32(new int[]{1, 1, 3, 3}, w)),
                    Map.of("x", Tensor.f32(new int[]{1, 1, 8, 16}, wide)),
                    true);
            Tensor scalar = exec(14, node("Conv", "c", attrs(Attr.ints("pads", 1, 1, 1, 1)), "x", "w", "y"),
                    Map.of("w", Tensor.f32(new int[]{1, 1, 3, 3}, w)),
                    Map.of("x", Tensor.f32(new int[]{1, 1, 8, 16}, wide)),
                    false);
            assertArrayEquals(scalar.f, vector.f, 1e-4f);
        }
    }

    @Test
    void strideTwoConvMatchesHandOracle() {
        float[] x = sequence(4 * 5, 0.2f);
        float[] w = sequence(9, 0.3f);
        Tensor out = exec(14, node("Conv", "c", attrs(
                        Attr.ints("strides", 2, 2),
                        Attr.ints("pads", 1, 1, 1, 1)),
                "x", "w", "y"),
                Map.of("w", Tensor.f32(new int[]{1, 1, 3, 3}, w)),
                Map.of("x", Tensor.f32(new int[]{1, 1, 4, 5}, x)),
                true);
        int outH = 2;
        int outW = 3;
        assertArrayEquals(new int[]{1, 1, outH, outW}, out.dims);
        float[] expect = new float[outH * outW];
        for (int oh = 0; oh < outH; oh++) {
            for (int ow = 0; ow < outW; ow++) {
                float sum = 0f;
                for (int kh = 0; kh < 3; kh++) {
                    for (int kw = 0; kw < 3; kw++) {
                        int ih = oh * 2 + kh - 1;
                        int iw = ow * 2 + kw - 1;
                        if (ih >= 0 && iw >= 0 && ih < 4 && iw < 5) {
                            sum += w[kh * 3 + kw] * x[ih * 5 + iw];
                        }
                    }
                }
                expect[oh * outW + ow] = sum;
            }
        }
        assertArrayEquals(expect, out.f, 1e-4f);
    }

    @Test
    void strideTwoWideMatchesScalar() {
        assumeTrue(VectorSupport.available());
        int ic = 3;
        int m = 4;
        int h = 40;
        int w = 48;
        float[] x = sequence(ic * h * w, 0.02f);
        float[] weight = sequence(m * ic * 9, 0.03f);
        Node node = node("Conv", "c", attrs(
                Attr.ints("strides", 2, 2),
                Attr.ints("pads", 1, 1, 1, 1)), "x", "w", "y");
        Map<String, Tensor> init = Map.of("w", Tensor.f32(new int[]{m, ic, 3, 3}, weight));
        Map<String, Tensor> inputs = Map.of("x", Tensor.f32(new int[]{1, ic, h, w}, x));
        assertArrayEquals(exec(14, node, init, inputs, false).f, exec(14, node, init, inputs, true).f, 1e-4f);
        Node grouped = node("Conv", "g", attrs(
                Attr.ints("strides", 2, 2),
                Attr.ints("pads", 1, 1, 1, 1),
                Attr.of("group", 4)), "x", "w", "y");
        float[] dw = sequence(4 * 9, 0.04f);
        Map<String, Tensor> gInit = Map.of("w", Tensor.f32(new int[]{4, 1, 3, 3}, dw));
        Map<String, Tensor> gInputs = Map.of("x", Tensor.f32(new int[]{1, 4, h, w}, sequence(4 * h * w, 0.02f)));
        assertArrayEquals(exec(14, grouped, gInit, gInputs, false).f, exec(14, grouped, gInit, gInputs, true).f, 1e-4f);
    }

    @Test
    void strideTwoOddWidthMatchesHandOracle() {
        int inH = 8;
        int inW = 9;
        float[] x = sequence(inH * inW, 0.2f);
        float[] w = sequence(9, 0.3f);
        Tensor out = exec(14, node("Conv", "c", attrs(
                        Attr.ints("strides", 2, 2),
                        Attr.ints("pads", 1, 1, 1, 1)),
                "x", "w", "b", "y"),
                Map.of(
                        "w", Tensor.f32(new int[]{1, 1, 3, 3}, w),
                        "b", Tensor.f32(new int[]{1}, new float[]{0.5f})),
                Map.of("x", Tensor.f32(new int[]{1, 1, inH, inW}, x)),
                true);
        int outH = (inH - 1) / 2 + 1;
        int outW = (inW - 1) / 2 + 1;
        assertArrayEquals(new int[]{1, 1, outH, outW}, out.dims);
        float[] expect = new float[outH * outW];
        for (int oh = 0; oh < outH; oh++) {
            for (int ow = 0; ow < outW; ow++) {
                float sum = 0.5f;
                for (int kh = 0; kh < 3; kh++) {
                    for (int kw = 0; kw < 3; kw++) {
                        int ih = oh * 2 + kh - 1;
                        int iw = ow * 2 + kw - 1;
                        if (ih >= 0 && iw >= 0 && ih < inH && iw < inW) {
                            sum += w[kh * 3 + kw] * x[ih * inW + iw];
                        }
                    }
                }
                expect[oh * outW + ow] = sum;
            }
        }
        assertArrayEquals(expect, out.f, 1e-4f);
    }

    @Test
    void parallelStride2MatchesOneThread() {
        assumeTrue(VectorSupport.available());
        int ic = 3;
        int m = 4;
        int h = 40;
        int w = 48;
        float[] x = sequence(ic * h * w, 0.02f);
        float[] weight = sequence(m * ic * 9, 0.03f);
        float[] bias = sequence(m, 0.01f);
        Node node = node("Conv", "c", attrs(
                Attr.ints("strides", 2, 2),
                Attr.ints("pads", 1, 1, 1, 1)), "x", "w", "b", "y");
        Map<String, Tensor> init = Map.of(
                "w", Tensor.f32(new int[]{m, ic, 3, 3}, weight),
                "b", Tensor.f32(new int[]{m}, bias));
        Map<String, Tensor> inputs = Map.of("x", Tensor.f32(new int[]{1, ic, h, w}, x));
        float[] one = exec(14, node, init, inputs, true).f;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Session session = Session.create(graph(14, node, init),
                    new Session.Options(true, 4, pool, false, false, false));
            assertArrayEquals(one, session.run(inputs).get("y").f, 0f);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelSharedInputMatchesOneThread() {
        assumeTrue(VectorSupport.available());
        int ic = 16;
        int m = 8;
        int h = 30;
        int w = 28;
        float[] x = sequence(ic * h * w, 0.02f);
        float[] weight = sequence(m * ic * 9, 0.03f);
        Node node = node("Conv", "c", attrs(Attr.ints("pads", 1, 1, 1, 1)), "x", "w", "y");
        Map<String, Tensor> init = Map.of("w", Tensor.f32(new int[]{m, ic, 3, 3}, weight));
        Map<String, Tensor> inputs = Map.of("x", Tensor.f32(new int[]{1, ic, h, w}, x));
        float[] one = exec(14, node, init, inputs, true).f;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Session session = Session.create(graph(14, node, init),
                    new Session.Options(true, 4, pool, false, false, false));
            assertArrayEquals(one, session.run(inputs).get("y").f, 0f);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelRowsMatchOneThread() {
        int m = 2;
        int h = 140;
        int w = 140;
        float[] x = sequence(h * w, 0.04f);
        float[] weight = sequence(m * 9, 0.05f);
        Node node = node("Conv", "c", attrs(Attr.ints("pads", 1, 0, 1, 1)), "x", "w", "y");
        Map<String, Tensor> init = Map.of("w", Tensor.f32(new int[]{m, 1, 3, 3}, weight));
        Map<String, Tensor> inputs = Map.of("x", Tensor.f32(new int[]{1, 1, h, w}, x));
        float[] one = exec(14, node, init, inputs, true).f;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Session session = Session.create(graph(14, node, init),
                    new Session.Options(true, 4, pool, false, false, false));
            assertArrayEquals(one, session.run(inputs).get("y").f, 1e-4f);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void depthwisePointwise() {
        Tensor out = exec(14, node("Conv", "c", attrs(Attr.of("group", 2)), "x", "w", "y"),
                Map.of("w", Tensor.f32(new int[]{2, 1, 1, 1}, new float[]{7, 11})),
                Map.of("x", Tensor.f32(new int[]{1, 2, 1, 1}, new float[]{3, 5})),
                false);
        assertArrayEquals(new float[]{21f, 55f}, out.f, 1e-4f);
    }

    @Test
    void convTransposeScattersKernel() {
        Tensor out = exec(14, node("ConvTranspose", "c", attrs(Attr.ints("strides", 2, 2)), "x", "w", "y"),
                Map.of("w", Tensor.f32(new int[]{1, 1, 2, 2}, new float[]{1, 2, 3, 4})),
                Map.of("x", Tensor.f32(new int[]{1, 1, 1, 1}, new float[]{1})),
                false);
        assertArrayEquals(new int[]{1, 1, 2, 2}, out.dims);
        assertArrayEquals(new float[]{1, 2, 3, 4}, out.f, 1e-4f);
    }

    @Test
    void convTransposeOverlapUsesTheSlowPath() {
        Tensor out = exec(14, node("ConvTranspose", "c", attrs(Attr.ints("strides", 1, 1)), "x", "w", "y"),
                Map.of("w", Tensor.f32(new int[]{1, 1, 2, 2}, new float[]{1, 1, 1, 1})),
                Map.of("x", Tensor.f32(new int[]{1, 1, 2, 2}, new float[]{1, 2, 3, 4})),
                false);
        assertArrayEquals(new int[]{1, 1, 3, 3}, out.dims);
        assertArrayEquals(new float[]{
                1, 3, 2,
                4, 10, 6,
                3, 7, 4
        }, out.f, 1e-4f);
    }

    @Test
    void convTransposePairVectorMatchesScalar() {
        assumeTrue(VectorSupport.available());
        int channels = 3;
        int height = 2;
        int width = 9;
        int outputs = 2;
        float[] x = sequence(channels * height * width, 0.08f);
        float[] weight = sequence(channels * outputs * 4, 0.05f);
        Node node = node("ConvTranspose", "t", attrs(Attr.ints("strides", 2, 2)), "x", "w", "y");
        Map<String, Tensor> init = Map.of("w", Tensor.f32(new int[]{channels, outputs, 2, 2}, weight));
        Map<String, Tensor> inputs = Map.of("x", Tensor.f32(new int[]{1, channels, height, width}, x));
        assertArrayEquals(exec(14, node, init, inputs, false).f, exec(14, node, init, inputs, true).f, 1e-5f);
    }

    @Test
    void parallelTransposeRowsMatchOneThread() {
        assumeTrue(VectorSupport.available());
        int channels = 16;
        int height = 80;
        int width = 64;
        float[] x = sequence(channels * height * width, 0.03f);
        float[] weight = sequence(channels * 4, 0.07f);
        Node node = node("ConvTranspose", "t", attrs(Attr.ints("strides", 2, 2)), "x", "w", "y");
        Map<String, Tensor> init = Map.of("w", Tensor.f32(new int[]{channels, 1, 2, 2}, weight));
        Map<String, Tensor> inputs = Map.of("x", Tensor.f32(new int[]{1, channels, height, width}, x));
        float[] one = exec(14, node, init, inputs, true).f;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Session session = Session.create(graph(14, node, init),
                    new Session.Options(true, 4, pool, false, false, false));
            assertArrayEquals(one, session.run(inputs).get("y").f, 1e-4f);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void gemmVectorMatchesScalarOnUnevenWidth() {
        assumeTrue(VectorSupport.available());
        int m = 5;
        int n = 13;
        int k = 9;
        float[] a = sequence(m * k, 0.1f);
        float[] b = sequence(k * n, 0.07f);
        float[] scalar = new float[m * n];
        float[] vector = new float[m * n];
        Blas.gemm(a, 0, k, b, 0, n, scalar, 0, n, m, n, k, false, null, 1);
        Blas.gemm(a, 0, k, b, 0, n, vector, 0, n, m, n, k, true, null, 1);
        assertClose(scalar, vector, 1e-4f);
    }

    @Test
    void gemmScalarHandProduct() {
        float[] c = new float[4];
        Gemm.scalar(new float[]{1, 2, 3, 4}, 0, 2, new float[]{5, 6, 7, 8}, 0, 2, c, 0, 2, 2, 2, 2);
        assertArrayEquals(new float[]{19, 22, 43, 50}, c, 1e-4f);
    }

    @Test
    void parallelGemmSplitsWideActivation() {
        assumeTrue(VectorSupport.available());
        int m = 8;
        int n = 513;
        int k = 48;
        float[] a = sequence(m * k, 0.03f);
        float[] b = sequence(k * n, 0.02f);
        float[] one = new float[m * n];
        float[] many = new float[m * n];
        Blas.gemm(a, 0, k, b, 0, n, one, 0, n, m, n, k, true, null, 1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Blas.gemm(a, 0, k, b, 0, n, many, 0, n, m, n, k, true, pool, 4);
        } finally {
            pool.shutdownNow();
        }
        assertClose(one, many, 1e-5f);
    }

    @Test
    void packedGemmMatchesScalarOnPanelTail() {
        assumeTrue(VectorSupport.available());
        int m = 32;
        int n = 70;
        int k = 40;
        float[] a = sequence(m * k, 0.05f);
        float[] b = sequence(k * n, 0.04f);
        float[] scalar = new float[m * n];
        float[] vector = new float[m * n];
        Blas.gemm(a, 0, k, b, 0, n, scalar, 0, n, m, n, k, false, null, 1);
        Blas.gemm(a, 0, k, b, 0, n, vector, 0, n, m, n, k, true, null, 1);
        assertClose(scalar, vector, 1e-4f);
    }

    @Test
    void parallelGemmMatchesOneThread() {
        assumeTrue(VectorSupport.available());
        int m = 128;
        int n = 64;
        int k = 64;
        float[] a = sequence(m * k, 0.01f);
        float[] b = sequence(k * n, 0.02f);
        float[] one = new float[m * n];
        float[] many = new float[m * n];
        Blas.gemm(a, 0, k, b, 0, n, one, 0, n, m, n, k, true, null, 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Blas.gemm(a, 0, k, b, 0, n, many, 0, n, m, n, k, true, pool, 2);
        } finally {
            pool.shutdownNow();
        }
        assertClose(one, many, 1e-5f);
    }

    @Test
    void parallelElementwiseMatchesOneThread() {
        assumeTrue(VectorSupport.available());
        int n = 1_000_000 + 17;
        float[] values = sequence(n, 0.07f);
        float[] one = values.clone();
        float[] many = values.clone();
        Blas.activate(one, 0, n, "HardSwish", 1f / 6f, 0.5f, true, null, 1);
        float[] scaled = new float[n];
        float[] scaledMany = new float[n];
        float[] affine = values.clone();
        float[] affineMany = values.clone();
        Blas.activate(affine, 0, n, "HardSwish", 1f / 6f, 0.5f, 1.25f, -0.5f, true, null, 1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Blas.activate(many, 0, n, "HardSwish", 1f / 6f, 0.5f, true, pool, 4);
            Blas.scalarRight(values, 0, 1.25f, scaled, 0, n, 2, true, null, 1);
            Blas.scalarRight(values, 0, 1.25f, scaledMany, 0, n, 2, true, pool, 4);
            Blas.activate(affineMany, 0, n, "HardSwish", 1f / 6f, 0.5f, 1.25f, -0.5f, true, pool, 4);
        } finally {
            pool.shutdownNow();
        }
        assertArrayEquals(one, many, 0f);
        assertArrayEquals(scaled, scaledMany, 0f);
        assertArrayEquals(affine, affineMany, 0f);
    }

    @Test
    void scalarAffineFoldsIntoConv() {
        float[] raw = runAffine(false, false, false);
        float[] fused = runAffine(false, true, false);
        assertClose(raw, fused, 1e-4f);
        float[] wideRaw = runAffine(true, false, false);
        float[] wideFused = runAffine(true, true, false);
        assertClose(wideRaw, wideFused, 1e-3f);
        if (VectorSupport.available()) {
            assertClose(fused, runAffine(false, true, true), 1e-4f);
            assertClose(wideFused, runAffine(true, true, true), 1e-3f);
        }
        Graph folded = affineGraph(false);
        folded.optimize();
        assertEquals(1, folded.nodes.size());
        Node conv = folded.nodes.get(0);
        assertEquals("Conv", conv.op);
        assertEquals("HardSwish", conv.activation);
        assertEquals(4f, conv.flt("out_scale", 1f), 0f);
        assertEquals(0.25f, conv.flt("out_shift", 0f), 0f);
        assertEquals("y", conv.outputs[0]);
    }

    @Test
    void int8GemmStaysCloseOnShortK() {
        int m = 4;
        int n = 4;
        int k = 8;
        float[] a = new float[m * k];
        float[] b = new float[k * n];
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < a.length; i++) {
            a[i] = random.nextFloat() * 2f - 1f;
        }
        for (int i = 0; i < b.length; i++) {
            b[i] = random.nextFloat() * 2f - 1f;
        }
        float[] exact = new float[m * n];
        float[] quant = new float[m * n];
        Gemm.scalar(a, 0, k, b, 0, n, exact, 0, n, m, n, k);
        Gemm.int8(a, 0, k, b, 0, n, quant, 0, n, m, n, k);
        float max = maxAbs(exact, quant);
        System.out.println("int8 max abs " + max);
        assertTrue(max < 0.2f, "int8 error " + max);
    }

    @Test
    void broadcastAdd() {
        Tensor out = exec(14, node("Add", "add", "x", "y", "z"),
                Map.of(),
                inputs(
                        "x", Tensor.f32(new int[]{1, 2, 1, 1}, new float[]{10, 20}),
                        "y", Tensor.f32(new int[]{1, 2, 2, 1}, new float[]{1, 2, 3, 4})),
                false);
        assertArrayEquals(new float[]{11, 12, 23, 24}, out.f, 1e-4f);
    }

    @Test
    void trailingBiasRepeatsAcrossRows() {
        int rows = 3;
        int width = 19;
        float[] bias = new float[width];
        float[] values = new float[rows * width];
        for (int i = 0; i < width; i++) {
            bias[i] = i - 9;
        }
        for (int i = 0; i < values.length; i++) {
            values[i] = i * 0.5f;
        }
        float[] expect = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            expect[i] = values[i] + bias[i % width];
        }
        Tensor out = exec(11, node("Add", "add", "x", "b", "y"), Map.of(),
                inputs(
                        "x", Tensor.f32(new int[]{1, rows, width}, values),
                        "b", Tensor.f32(new int[]{width}, bias)),
                false);
        assertArrayEquals(expect, out.f, 0f);
        Tensor sub = exec(11, node("Sub", "sub", "b", "x", "y"), Map.of(),
                inputs(
                        "b", Tensor.f32(new int[]{1, width}, bias),
                        "x", Tensor.f32(new int[]{rows, width}, values)),
                false);
        for (int i = 0; i < expect.length; i++) {
            expect[i] = bias[i % width] - values[i];
        }
        assertArrayEquals(expect, sub.f, 0f);
        Tensor mul = exec(11, node("Mul", "mul", "x", "b", "y"), Map.of(),
                inputs(
                        "x", Tensor.f32(new int[]{rows, 1, width}, values),
                        "b", Tensor.f32(new int[]{1, width}, bias)),
                false);
        for (int i = 0; i < values.length; i++) {
            expect[i] = values[i] * bias[i % width];
        }
        assertArrayEquals(expect, mul.f, 0f);
        if (VectorSupport.available()) {
            for (int i = 0; i < values.length; i++) {
                expect[i] = values[i] + bias[i % width];
            }
            Tensor wide = exec(11, node("Add", "add", "x", "b", "y"), Map.of(),
                    inputs(
                            "x", Tensor.f32(new int[]{1, rows, width}, values),
                            "b", Tensor.f32(new int[]{width}, bias)),
                    true);
            assertArrayEquals(expect, wide.f, 0f);
        }
    }

    @Test
    void hardSigmoidReadsAlpha() {
        Tensor out = exec(14, node("HardSigmoid", "h", attrs(Attr.of("alpha", 0.2f), Attr.of("beta", 0.5f)), "x", "y"),
                Map.of(),
                Map.of("x", Tensor.f32(new int[]{3}, new float[]{-3, 0, 3})),
                false);
        assertArrayEquals(new float[]{0f, 0.5f, 1f}, out.f, 1e-4f);
    }

    @Test
    void softmaxOpset14IsSingleAxis() {
        Tensor zeros = exec(14, node("Softmax", "s", attrs(Attr.of("axis", -1)), "x", "y"),
                Map.of(),
                Map.of("x", Tensor.f32(new int[]{1, 2}, new float[]{0, 0})),
                false);
        assertArrayEquals(new float[]{0.5f, 0.5f}, zeros.f, 1e-5f);
        Tensor shifted = exec(14, node("Softmax", "s", attrs(Attr.of("axis", -1)), "x", "y"),
                Map.of(),
                Map.of("x", Tensor.f32(new int[]{1, 2}, new float[]{1, 0})),
                false);
        float e = (float) Math.exp(1);
        float sum = e + 1f;
        assertArrayEquals(new float[]{e / sum, 1f / sum}, shifted.f, 1e-5f);
        Tensor axis1 = exec(14, node("Softmax", "s", attrs(Attr.of("axis", 1)), "x", "y"),
                Map.of(),
                Map.of("x", Tensor.f32(new int[]{1, 2, 2}, new float[]{1, 0, 0, 0})),
                false);
        float mid = e / sum;
        float rest = 1f / sum;
        assertArrayEquals(new float[]{mid, 0.5f, rest, 0.5f}, axis1.f, 1e-5f);
    }

    @Test
    void softmaxOpset9CoalescesTheTail() {
        Tensor out = exec(11, node("Softmax", "s", attrs(Attr.of("axis", 1)), "x", "y"),
                Map.of(),
                Map.of("x", Tensor.f32(new int[]{1, 2, 2}, new float[]{1, 0, 0, 0})),
                false);
        float e = (float) Math.exp(1);
        float sum = e + 3f;
        assertArrayEquals(new float[]{e / sum, 1f / sum, 1f / sum, 1f / sum}, out.f, 1e-5f);
    }

    @Test
    void resizeNearestAsymmetricFloor() {
        Tensor out = exec(14, node("Resize", "r", attrs(
                        Attr.of("mode", "nearest"),
                        Attr.of("coordinate_transformation_mode", "asymmetric"),
                        Attr.of("nearest_mode", "floor")),
                "x", "roi", "scales", "y"),
                Map.of(
                        "roi", Tensor.f32(new int[]{0}, new float[0]),
                        "scales", Tensor.f32(new int[]{4}, new float[]{1, 1, 2, 2})),
                Map.of("x", Tensor.f32(new int[]{1, 1, 2, 2}, new float[]{1, 2, 3, 4})),
                false);
        assertArrayEquals(new int[]{1, 1, 4, 4}, out.dims);
        assertArrayEquals(new float[]{
                1, 1, 2, 2,
                1, 1, 2, 2,
                3, 3, 4, 4,
                3, 3, 4, 4
        }, out.f, 1e-6f);
    }

    @Test
    void resizeNearestUnevenAsymmetricFloor() {
        Tensor out = exec(14, node("Resize", "r", attrs(
                        Attr.of("mode", "nearest"),
                        Attr.of("coordinate_transformation_mode", "asymmetric"),
                        Attr.of("nearest_mode", "floor")),
                "x", "roi", "scales", "y"),
                Map.of(
                        "roi", Tensor.f32(new int[]{0}, new float[0]),
                        "scales", Tensor.f32(new int[]{4}, new float[]{1, 1, 1.5f, 1.5f})),
                Map.of("x", Tensor.f32(new int[]{1, 1, 2, 2}, new float[]{1, 2, 3, 4})),
                false);
        assertArrayEquals(new int[]{1, 1, 3, 3}, out.dims);
        assertArrayEquals(new float[]{
                1, 1, 2,
                1, 1, 2,
                3, 3, 4
        }, out.f, 1e-6f);
    }

    @Test
    void parallelResizeMatchesOneThread() {
        int channels = 32;
        int h = 40;
        int w = 40;
        float[] data = sequence(channels * h * w, 0.05f);
        Node node = node("Resize", "r", attrs(
                        Attr.of("mode", "nearest"),
                        Attr.of("coordinate_transformation_mode", "asymmetric"),
                        Attr.of("nearest_mode", "floor")),
                "x", "roi", "scales", "y");
        Map<String, Tensor> init = Map.of(
                "roi", Tensor.f32(new int[]{0}, new float[0]),
                "scales", Tensor.f32(new int[]{4}, new float[]{1, 1, 2, 2}));
        Map<String, Tensor> inputs = Map.of("x", Tensor.f32(new int[]{1, channels, h, w}, data));
        float[] one = exec(14, node, init, inputs, true).f;
        Graph graph = graph(14, node, init);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Session session = Session.create(graph, new Session.Options(true, 4, pool, false, false, false));
            float[] many = session.run(inputs).get("y").f;
            assertArrayEquals(one, many, 0f);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sliceAttributeAndReverse() {
        Tensor cut = exec(9, node("Slice", "s", attrs(
                        Attr.ints("starts", 1),
                        Attr.ints("ends", 3),
                        Attr.ints("axes", 0)),
                "x", "y"),
                Map.of(),
                Map.of("x", Tensor.f32(new int[]{4}, new float[]{1, 2, 3, 4})),
                false);
        assertArrayEquals(new float[]{2, 3}, cut.f, 0f);
        Tensor reversed = exec(14, node("Slice", "s", "x", "starts", "ends", "axes", "steps", "y"),
                Map.of(
                        "starts", Tensor.i64(new int[]{1}, new long[]{3}),
                        "ends", Tensor.i64(new int[]{1}, new long[]{-5}),
                        "axes", Tensor.i64(new int[]{1}, new long[]{0}),
                        "steps", Tensor.i64(new int[]{1}, new long[]{-1})),
                Map.of("x", Tensor.f32(new int[]{4}, new float[]{0, 1, 2, 3})),
                false);
        assertArrayEquals(new float[]{3, 2, 1, 0}, reversed.f, 0f);
    }

    @Test
    void reshapeZeroCopiesAndInfers() {
        float[] data = new float[24];
        for (int i = 0; i < data.length; i++) {
            data[i] = i;
        }
        Tensor out = exec(14, node("Reshape", "r", "x", "shape", "y"),
                Map.of("shape", Tensor.i64(new int[]{2}, new long[]{0, -1})),
                Map.of("x", Tensor.f32(new int[]{2, 3, 4}, data)),
                false);
        assertArrayEquals(new int[]{2, 12}, out.dims);
        assertEquals(23f, out.f[23], 0f);
    }

    @Test
    void unsupportedOpNamesItself() {
        Session session = Session.create(graph(14, node("Gather", "g", "x", "y"), Map.of()), Session.Options.scalar());
        OcrException ex = assertThrows(OcrException.class,
                () -> session.run(Map.of("x", Tensor.f32(new int[]{1}, new float[]{1f}))));
        assertTrue(ex.getMessage().contains("Unsupported op: Gather"), ex.getMessage());
    }

    @Test
    void jirRoundTripKeepsWeights() throws Exception {
        Graph graph = new Graph();
        graph.inputs.add(new Sig("x", Tensor.F32, new int[]{1, 1, 1, 1}));
        graph.outputs.add(new Sig("y", Tensor.F32, null));
        graph.initializers.put("w", Tensor.f32(new int[]{1, 1, 1, 1}, new float[]{2f}));
        graph.initializers.put("b", Tensor.f32(new int[]{1}, new float[]{3f}));
        graph.nodes.add(Node.of("Conv", "c", new String[]{"x", "w", "b"}, new String[]{"h"}));
        graph.nodes.add(Node.of("Relu", "r", new String[]{"h"}, new String[]{"y"}));
        Session created = Session.create(graph, new Session.Options(false, 1, null, false, false, true));
        Path path = Files.createTempFile("ocr", ".jir");
        created.saveJir(path);
        Session loaded = Session.load(path, Session.Options.scalar());
        float negative = loaded.run(Map.of("x", Tensor.f32(new int[]{1, 1, 1, 1}, new float[]{-4f}))).get("y").f[0];
        float positive = loaded.run(Map.of("x", Tensor.f32(new int[]{1, 1, 1, 1}, new float[]{4f}))).get("y").f[0];
        assertEquals(0f, negative, 1e-4);
        assertEquals(11f, positive, 1e-4);
        Files.deleteIfExists(path);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void parsesOfficialModels() {
        assumeTrue(Files.exists(model(ModelFiles.DET)));
        Session det = Session.load(model(ModelFiles.DET), Session.Options.scalar());
        Map<String, Integer> hist = det.histogram();
        assertEquals(502, det.graph().nodes.size());
        assertEquals(246, det.graph().initializers.size());
        assertEquals(62, hist.get("Conv"));
        assertEquals(192, hist.get("Identity"));
        assertEquals(6, hist.get("Resize"));
        assertEquals(1, hist.get("Sigmoid"));
        assertEquals(14, det.graph().opset);
        boolean stem = false;
        boolean kernel5 = false;
        for (Node node : det.graph().nodes) {
            if (!"Conv".equals(node.op)) {
                continue;
            }
            long[] kernel = node.longs("kernel_shape", null);
            long[] strides = node.longs("strides", null);
            long[] pads = node.longs("pads", null);
            if (kernel != null && kernel.length == 2 && kernel[0] == 3 && kernel[1] == 3
                    && strides != null && strides[0] == 2 && strides[1] == 2
                    && pads != null && pads.length == 4
                    && pads[0] == 1 && pads[1] == 1 && pads[2] == 1 && pads[3] == 1) {
                stem = true;
            }
            if (kernel != null && kernel.length == 2 && kernel[0] == 5 && kernel[1] == 5) {
                kernel5 = true;
            }
        }
        assertTrue(stem);
        assertTrue(kernel5);
        boolean empty = false;
        boolean scale8 = false;
        for (Tensor tensor : det.graph().initializers.values()) {
            if (tensor.numel() == 0) {
                empty = true;
            }
            if (tensor.f != null && tensor.f.length == 4
                    && tensor.f[0] == 1f && tensor.f[1] == 1f && tensor.f[2] == 8f && tensor.f[3] == 8f) {
                scale8 = true;
            }
        }
        assertTrue(empty);
        assertTrue(scale8);

        assumeTrue(Files.exists(model(ModelFiles.CLS)));
        Session cls = Session.load(model(ModelFiles.CLS), Session.Options.scalar());
        assertEquals(9, cls.graph().opset);
        Sig clsIn = input(cls);
        assertEquals(80, clsIn.dims[2]);
        assertEquals(160, clsIn.dims[3]);
        assertTrue(cls.graph().nodes.stream().anyMatch(node -> "Slice".equals(node.op) && node.inputs.length == 1));

        assumeTrue(Files.exists(model(ModelFiles.REC)));
        Session rec = Session.load(model(ModelFiles.REC), Session.Options.scalar());
        assertEquals(480, rec.graph().nodes.size());
        assertEquals(244, rec.graph().initializers.size());
        assertEquals(11, rec.graph().opset);
        assertEquals(13, rec.histogram().get("Erf"));
        assertEquals(1, rec.histogram().get("MaxPool"));
        Sig recIn = input(rec);
        assertEquals(48, recIn.dims[2]);
        int[] recOut = rec.outputSig().dims;
        assertEquals(18710, recOut[recOut.length - 1]);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void detection64MatchesOptimizedAndIsFinite() {
        assumeTrue(Files.exists(model(ModelFiles.DET)));
        float[] raw = det64(false);
        float[] fused = det64(true);
        assertEquals(raw.length, fused.length);
        float drift = maxAbs(raw, fused);
        System.out.println("det 64x64 fusion drift " + drift + " values " + raw.length);
        assertTrue(drift < 1e-3f, "fusion drift " + drift);
        for (float v : fused) {
            assertTrue(Float.isFinite(v), "non-finite detection output");
        }
    }

    @Test
    void arenaSizeClassesServeNearbySizes() {
        Arena arena = new Arena();
        float[] exact = arena.allocF(200);
        assertEquals(200, exact.length);
        // Four sizes per doubling above 256: 320, 384, 448, 512, 640, ...
        float[] wide = arena.allocF(300);
        assertEquals(320, wide.length);
        assertEquals(512, arena.allocF(512).length);
        assertEquals(640, arena.allocF(513).length);
        arena.freeF(wide);
        assertSame(wide, arena.allocF(310));
    }

    @Test
    void arenaServesASmallerRequestFromALargerIdleBufferUpToTwiceItsSize() {
        Arena arena = new Arena();
        float[] large = arena.allocF(600);
        assertEquals(640, large.length);
        arena.freeF(large);
        // 330 rounds to 384, and 640 is within twice that.
        assertSame(large, arena.allocF(330));
        arena.freeF(large);
        // 257 rounds to 320; 640 is exactly twice that and still serves it.
        assertSame(large, arena.allocF(257));
        arena.freeF(large);
        float[] huge = arena.allocF(4000);
        arena.freeF(huge);
        arena.allocF(600);
        // 4096 is more than twice 320, so it waits for a request of its own size.
        float[] fresh = arena.allocF(300);
        assertEquals(320, fresh.length);
        assertSame(huge, arena.allocF(3000));
    }

    @Test
    void arenaCountsWhatItHoldsAndTrimsTheLargestFirst() {
        Arena arena = new Arena();
        float[] small = arena.allocF(1024);
        float[] middle = arena.allocF(4096);
        float[] large = arena.allocF(1 << 20);
        assertEquals(4L * (1024 + 4096 + (1 << 20)), arena.allocatedBytes());
        assertEquals(0L, arena.idleBytes());
        arena.freeF(small);
        arena.freeF(middle);
        arena.freeF(large);
        assertEquals(arena.allocatedBytes(), arena.idleBytes());

        arena.trim(4L * (1024 + 4096));
        assertEquals(4L * (1024 + 4096), arena.idleBytes());
        assertSame(small, arena.allocF(1024));
        assertSame(middle, arena.allocF(4096));
        assertEquals(0L, arena.idleBytes());
        // The large buffer is gone: the same request makes a new array.
        long before = arena.allocatedBytes();
        arena.allocF(1 << 20);
        assertEquals(before + 4L * (1 << 20), arena.allocatedBytes());

        arena.freeF(small);
        Tensor ints = Tensor.pooledI64(new int[]{8}, arena.allocI(8));
        arena.free(ints);
        assertEquals(4L * 1024 + 8L * 8, arena.idleBytes());
        arena.trim(0L);
        assertEquals(0L, arena.idleBytes());
        arena.trim(0L);
    }

    @Test
    void sessionKeepsABoundedShareOfItsBuffersAfterARun() {
        Graph graph = new Graph();
        graph.opset = 14;
        graph.inputs.add(new Sig("x", Tensor.F32, new int[]{1, -1}));
        graph.outputs.add(new Sig("y", Tensor.F32, new int[]{1, -1}));
        graph.nodes.add(Node.of("Relu", "a", new String[]{"x"}, new String[]{"h"}));
        graph.nodes.add(Node.of("Relu", "b", new String[]{"h"}, new String[]{"y"}));
        Session session = Session.create(graph, Session.Options.scalar());
        int n = (int) (Session.RETAIN_BYTES / 4) + 1024;
        Tensor out = session.run(Map.of("x", Tensor.f32(new int[]{1, n}, new float[n]))).get("y");
        assertEquals(n, out.f.length);
        assertTrue(session.allocatedBytes() > Session.RETAIN_BYTES, "the run needed more than a session keeps");
        assertTrue(session.idleBytes() <= Session.RETAIN_BYTES, "kept " + session.idleBytes());
        session.trim(0L);
        assertEquals(0L, session.idleBytes());
    }

    @Test
    void packedInt64DataIsVarintsEvenWhenItComesToEightBytesAValue() {
        // -1, -1, -1 and 300 take 10 + 10 + 10 + 2 bytes: 32, the size of four fixed values.
        ProtoBuf packed = new ProtoBuf();
        packed.varint(-1);
        packed.varint(-1);
        packed.varint(-1);
        packed.varint(300);
        assertEquals(32, packed.toByteArray().length);
        Graph graph = Onnx.read(int64Model(packed.toByteArray(), 4L), Path.of("."));
        assertArrayEquals(new long[]{-1, -1, -1, 300}, graph.initializers.get("w").i);

        byte[] fixed = new byte[16];
        fixed[0] = 5;
        fixed[8] = 7;
        Graph stored = Onnx.read(int64Model(fixed, 2L), Path.of("."));
        assertArrayEquals(new long[]{5, 7}, stored.initializers.get("w").i);
    }

    @Test
    void anInt64ShapeFarLargerThanItsDataFailsBeforeTheArrayIsMade() {
        OcrException huge = assertThrows(OcrException.class,
                () -> Onnx.read(int64Model(new byte[]{1}, 2147483647L), Path.of(".")));
        assertTrue(huge.getMessage().contains("cannot decode tensor"), huge.getMessage());
        assertThrows(OcrException.class, () -> Onnx.read(int64Model(new byte[]{1, 2}, 1_000_000_000L), Path.of(".")));
    }

    /** {@code [3]} after a convolution with a width of 3 adds one value per column, not per channel. */
    @Test
    void aConstantAlongTheWidthIsNotFoldedIntoTheBias() {
        float[][] out = new float[2][];
        for (int pass = 0; pass < 2; pass++) {
            Graph graph = new Graph();
            graph.opset = 14;
            graph.inputs.add(new Sig("x", Tensor.F32, null));
            graph.outputs.add(new Sig("y", Tensor.F32, null));
            graph.initializers.put("w", Tensor.f32(new int[]{3, 1, 1, 1}, new float[]{1, 2, 3}));
            graph.initializers.put("k", Tensor.f32(new int[]{3}, new float[]{10, 20, 30}));
            graph.nodes.add(node("Conv", "c", "x", "w", "c0"));
            graph.nodes.add(node("Add", "a", "c0", "k", "y"));
            Session session = Session.create(graph, new Session.Options(false, 1, null, false, false, pass == 1));
            out[pass] = session.run(Map.of("x", Tensor.f32(new int[]{1, 1, 2, 3}, new float[]{1, 1, 1, 1, 1, 1})))
                    .get("y").f.clone();
        }
        assertArrayEquals(new float[]{11, 21, 31, 11, 21, 31, 12, 22, 32, 12, 22, 32, 13, 23, 33, 13, 23, 33}, out[0], 0f);
        assertArrayEquals(out[0], out[1], 0f);
    }

    /** The same constant shaped {@code [1, 3, 1, 1]} is a bias, and folding it changes nothing. */
    @Test
    void aConstantOverTheChannelsIsFoldedIntoTheBias() {
        float[][] out = new float[2][];
        for (int pass = 0; pass < 2; pass++) {
            Graph graph = new Graph();
            graph.opset = 14;
            graph.inputs.add(new Sig("x", Tensor.F32, null));
            graph.outputs.add(new Sig("y", Tensor.F32, null));
            graph.initializers.put("w", Tensor.f32(new int[]{3, 1, 1, 1}, new float[]{1, 2, 3}));
            graph.initializers.put("k", Tensor.f32(new int[]{1, 3, 1, 1}, new float[]{10, 20, 30}));
            graph.nodes.add(node("Conv", "c", "x", "w", "c0"));
            graph.nodes.add(node("Add", "a", "c0", "k", "y"));
            Session session = Session.create(graph, new Session.Options(false, 1, null, false, false, pass == 1));
            if (pass == 1) {
                assertEquals(1, session.graph().nodes.size(), "the Add is part of the convolution");
            }
            out[pass] = session.run(Map.of("x", Tensor.f32(new int[]{1, 1, 2, 3}, new float[]{1, 1, 1, 1, 1, 1})))
                    .get("y").f.clone();
        }
        assertArrayEquals(new float[]{11, 11, 11, 11, 11, 11, 22, 22, 22, 22, 22, 22, 33, 33, 33, 33, 33, 33}, out[0], 0f);
        assertArrayEquals(out[0], out[1], 0f);
    }

    @Test
    void aConvolutionOutputTheGraphHandsOutSurvivesFusion() {
        Graph graph = new Graph();
        graph.opset = 14;
        graph.inputs.add(new Sig("x", Tensor.F32, null));
        graph.outputs.add(new Sig("h", Tensor.F32, null));
        graph.outputs.add(new Sig("y", Tensor.F32, null));
        graph.initializers.put("w", Tensor.f32(new int[]{1, 1, 1, 1}, new float[]{-2}));
        graph.nodes.add(node("Conv", "c", "x", "w", "h"));
        graph.nodes.add(node("Relu", "r", "h", "y"));
        Session session = Session.create(graph, new Session.Options(false, 1, null, false, false, true));
        Map<String, Tensor> out = session.run(Map.of("x", Tensor.f32(new int[]{1, 1, 1, 2}, new float[]{1, -1})));
        assertArrayEquals(new float[]{-2, 2}, out.get("h").f, 0f);
        assertArrayEquals(new float[]{0, 2}, out.get("y").f, 0f);
    }

    /** Old exporters list every weight among the graph inputs. A folded weight must not become a required input. */
    @Test
    void aFoldedWeightListedAsAGraphInputIsNotAskedFor() {
        Graph graph = new Graph();
        graph.opset = 14;
        graph.inputs.add(new Sig("w", Tensor.F32, new int[]{1, 1, 1, 1}));
        graph.inputs.add(new Sig("x", Tensor.F32, null));
        graph.outputs.add(new Sig("y", Tensor.F32, null));
        graph.initializers.put("w", Tensor.f32(new int[]{1, 1, 1, 1}, new float[]{2}));
        graph.initializers.put("scale", Tensor.f32(new int[]{1}, new float[]{3}));
        graph.initializers.put("shift", Tensor.f32(new int[]{1}, new float[]{1}));
        graph.initializers.put("mean", Tensor.f32(new int[]{1}, new float[]{0}));
        graph.initializers.put("var", Tensor.f32(new int[]{1}, new float[]{1}));
        graph.nodes.add(node("Conv", "c", "x", "w", "c0"));
        graph.nodes.add(node("BatchNormalization", "bn", attrs(Attr.of("epsilon", 0f)),
                "c0", "scale", "shift", "mean", "var", "y"));
        Session session = Session.create(graph, new Session.Options(false, 1, null, false, false, true));
        assertEquals(1, session.graph().nodes.size(), "the batch norm is part of the convolution");
        assertEquals("x", session.inputName());
        Tensor y = session.run(Map.of("x", Tensor.f32(new int[]{1, 1, 1, 1}, new float[]{5}))).get("y");
        assertEquals(31f, y.f[0], 1e-5f);
    }

    /** Inputs an operator cannot compute fail by name; none returns a buffer it did not fill. */
    @Test
    void operatorsRejectWhatTheyCannotCompute() {
        Tensor map = Tensor.f32(new int[]{1, 1, 2, 2}, new float[]{1, 2, 3, 4});
        Map<String, Tensor> x = Map.of("x", map);

        OcrException perm = assertThrows(OcrException.class, () -> exec(14,
                node("Transpose", "t", attrs(Attr.ints("perm", 0, 0, 2, 3)), "x", "y"), Map.of(), x, false));
        assertTrue(perm.getMessage().contains("not a permutation"), perm.getMessage());

        OcrException empty = assertThrows(OcrException.class, () -> exec(14,
                node("GlobalAveragePool", "g", "x", "y"), Map.of(),
                Map.of("x", Tensor.f32(new int[]{1, 2, 0, 3}, new float[0])), false));
        assertTrue(empty.getMessage().contains("empty map"), empty.getMessage());

        // A 3x3 kernel does not fit a 2x2 map, with a stride of 2 as with a stride of 1.
        Map<String, Tensor> kernel = Map.of("w", Tensor.f32(new int[]{1, 1, 3, 3}, new float[9]));
        for (long stride : new long[]{1, 2, 3}) {
            OcrException small = assertThrows(OcrException.class, () -> exec(14,
                    node("Conv", "c", attrs(Attr.ints("strides", stride, stride)), "x", "w", "y"), kernel, x, false));
            assertTrue(small.getMessage().contains("non-positive output"), small.getMessage());
        }

        Map<String, Tensor> up = Map.of("w", Tensor.f32(new int[]{1, 1, 2, 2}, new float[]{1, 1, 1, 1}));
        OcrException same = assertThrows(OcrException.class, () -> exec(14,
                node("ConvTranspose", "t", attrs(Attr.of("auto_pad", "SAME_UPPER"), Attr.ints("strides", 2, 2)),
                        "x", "w", "y"), up, x, false));
        assertTrue(same.getMessage().contains("auto_pad SAME_UPPER is not supported"), same.getMessage());
        OcrException shape = assertThrows(OcrException.class, () -> exec(14,
                node("ConvTranspose", "t", attrs(Attr.ints("output_shape", 4, 4), Attr.ints("strides", 2, 2)),
                        "x", "w", "y"), up, x, false));
        assertTrue(shape.getMessage().contains("output_shape"), shape.getMessage());
    }

    @Test
    void reduceMeanWithoutAxesIsTheMeanOfEverything() {
        Tensor matrix = Tensor.f32(new int[]{2, 2}, new float[]{1, 2, 3, 4});
        // Opset 18 passes the axes as an input; an empty one means all of them.
        Graph graph = new Graph();
        graph.opset = 18;
        graph.inputs.add(new Sig("x", Tensor.F32, null));
        graph.outputs.add(new Sig("y", Tensor.F32, null));
        graph.initializers.put("axes", Tensor.i64(new int[]{0}, new long[0]));
        graph.nodes.add(node("ReduceMean", "r", attrs(Attr.of("keepdims", 0L)), "x", "axes", "y"));
        Tensor all = Session.create(graph, Session.Options.scalar()).run(Map.of("x", matrix)).get("y");
        assertEquals(1, all.numel());
        assertEquals(2.5f, all.f[0], 1e-6f);
    }

    @Test
    void sliceStepBeyondTheIntRangeTakesOneElement() {
        Tensor row = Tensor.f32(new int[]{4}, new float[]{10, 20, 30, 40});
        Map<String, Tensor> forward = Map.of(
                "starts", Tensor.i64(new int[]{1}, new long[]{0}),
                "ends", Tensor.i64(new int[]{1}, new long[]{Long.MAX_VALUE}),
                "axes", Tensor.i64(new int[]{1}, new long[]{0}),
                "steps", Tensor.i64(new int[]{1}, new long[]{Long.MAX_VALUE}));
        Tensor first = exec(14, node("Slice", "s", "x", "starts", "ends", "axes", "steps", "y"),
                forward, Map.of("x", row), false);
        assertArrayEquals(new int[]{1}, first.dims);
        assertEquals(10f, first.f[0], 0f);
        Map<String, Tensor> backward = Map.of(
                "starts", Tensor.i64(new int[]{1}, new long[]{-1}),
                "ends", Tensor.i64(new int[]{1}, new long[]{Long.MIN_VALUE}),
                "axes", Tensor.i64(new int[]{1}, new long[]{0}),
                "steps", Tensor.i64(new int[]{1}, new long[]{Long.MIN_VALUE}));
        Tensor last = exec(14, node("Slice", "s", "x", "starts", "ends", "axes", "steps", "y"),
                backward, Map.of("x", row), false);
        assertArrayEquals(new int[]{1}, last.dims);
        assertEquals(40f, last.f[0], 0f);
    }

    /** With count_include_pad the padding counts, and cells a ceil_mode window reaches beyond it do not. */
    @Test
    void averagePoolCountsThePaddingButNotWhatLiesBeyondIt() {
        Tensor row = Tensor.f32(new int[]{1, 1, 1, 5}, new float[]{2, 4, 6, 8, 10});
        Tensor out = exec(14, node("AveragePool", "p", attrs(
                Attr.ints("kernel_shape", 1, 2), Attr.ints("strides", 1, 2),
                Attr.of("ceil_mode", 1L), Attr.of("count_include_pad", 1L)), "x", "y"),
                Map.of(), Map.of("x", row), false);
        // Windows [2,4], [6,8] and [10, beyond]: the last one holds one cell.
        assertArrayEquals(new int[]{1, 1, 1, 3}, out.dims);
        assertArrayEquals(new float[]{3, 7, 10}, java.util.Arrays.copyOf(out.f, 3), 1e-6f);

        Tensor same = exec(14, node("AveragePool", "p", attrs(
                Attr.ints("kernel_shape", 1, 3), Attr.ints("strides", 1, 1), Attr.of("auto_pad", "SAME_UPPER")), "x", "y"),
                Map.of(), Map.of("x", row), false);
        assertArrayEquals(new int[]{1, 1, 1, 5}, same.dims);
        assertArrayEquals(new float[]{3, 4, 6, 8, 9}, java.util.Arrays.copyOf(same.f, 5), 1e-6f);
    }

    @Test
    void aNodeTheOptimizerCannotReadFailsWithOcrException() {
        Graph graph = new Graph();
        graph.opset = 14;
        graph.inputs.add(new Sig("x", Tensor.F32, null));
        graph.outputs.add(new Sig("y", Tensor.F32, null));
        graph.initializers.put("k", Tensor.f32(new int[]{1, 1, 1, 1}, new float[]{1}));
        graph.nodes.add(node("Conv", "c", "x", "c0"));
        graph.nodes.add(node("Add", "a", "c0", "k", "y"));
        assertThrows(OcrException.class, () -> Session.create(graph, new Session.Options(false, 1, null, false, false, true)));
    }

    @Test
    void aPoisonedArenaHandsOutNaN() {
        Arena arena = new Arena(true);
        for (float v : arena.allocF(300)) {
            assertTrue(Float.isNaN(v));
        }
        float[] used = arena.allocF(300);
        java.util.Arrays.fill(used, 1f);
        arena.freeF(used);
        float[] again = arena.allocF(300);
        assertSame(used, again);
        assertTrue(Float.isNaN(again[0]));
        assertEquals(0f, new Arena().allocF(300)[0], 0f);
    }

    @Test
    void jirRoundTripAndTruncation() {
        Graph graph = new Graph();
        graph.opset = 14;
        graph.inputs.add(new Sig("x", Tensor.F32, new int[]{1}));
        graph.outputs.add(new Sig("y", Tensor.F32, new int[]{1}));
        graph.initializers.put("w", Tensor.f32(new int[]{1}, new float[]{2f}));
        Node node = Node.of("Add", "add", new String[]{"x", "w"}, new String[]{"y"});
        node.activation = "Relu";
        graph.nodes.add(node);
        byte[] bytes = Jir.write(graph);
        Graph back = Jir.read(bytes);
        assertEquals(14, back.opset);
        assertEquals("Add", back.nodes.get(0).op);
        assertEquals("Relu", back.nodes.get(0).activation);
        assertEquals(2f, back.initializers.get("w").f[0], 0f);
        assertArrayEquals(new int[]{1}, back.inputs.get(0).dims);

        byte[] body = {'J', 'I', 'R', '1', 1, 0, 0, 0};
        CRC32 crc = new CRC32();
        crc.update(body);
        int sum = (int) crc.getValue();
        byte[] shortFile = new byte[12];
        System.arraycopy(body, 0, shortFile, 0, body.length);
        shortFile[8] = (byte) sum;
        shortFile[9] = (byte) (sum >>> 8);
        shortFile[10] = (byte) (sum >>> 16);
        shortFile[11] = (byte) (sum >>> 24);
        OcrException truncated = assertThrows(OcrException.class, () -> Jir.read(shortFile));
        assertTrue(truncated.getMessage().contains("truncated"), truncated.getMessage());
        OcrException junk = assertThrows(OcrException.class, () -> Jir.read(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
        assertTrue(junk.getMessage().contains("not a JIR"), junk.getMessage());

        // A count far beyond the file, with the checksum recomputed, is truncated and not an OutOfMemoryError.
        byte[] hugeCount = withChecksum(new byte[]{
                'J', 'I', 'R', '1', 1, 0, 0, 0, 14, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f});
        OcrException huge = assertThrows(OcrException.class, () -> Jir.read(hugeCount));
        assertTrue(huge.getMessage().contains("truncated"), huge.getMessage());
    }

    private static byte[] withChecksum(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        int sum = (int) crc.getValue();
        byte[] file = new byte[body.length + 4];
        System.arraycopy(body, 0, file, 0, body.length);
        file[body.length] = (byte) sum;
        file[body.length + 1] = (byte) (sum >>> 8);
        file[body.length + 2] = (byte) (sum >>> 16);
        file[body.length + 3] = (byte) (sum >>> 24);
        return file;
    }

    @Test
    void externalDataStaysInsideTheModelDirectory() throws Exception {
        Path dir = Files.createTempDirectory("ocr-ext");
        Path outside = Files.createTempDirectory("ocr-ext-out");
        Path weights = dir.resolve("w.bin");
        Path secret = outside.resolve("secret.bin");
        Path link = dir.resolve("link.bin");
        try {
            Files.write(weights, new byte[]{0x00, 0x00, (byte) 0x80, 0x3f});
            Graph graph = Onnx.read(externalModel("w.bin", null, null), dir);
            assertEquals(1f, graph.initializers.get("w").f[0], 0f);
            assertEquals(1f, Onnx.read(externalModel("./w.bin", null, null), dir).initializers.get("w").f[0], 0f);

            OcrException escape = assertThrows(OcrException.class,
                    () -> Onnx.read(externalModel("../w.bin", null, null), dir));
            assertTrue(escape.getMessage().contains("escapes"), escape.getMessage());

            OcrException absolute = assertThrows(OcrException.class,
                    () -> Onnx.read(externalModel("/etc/hostname", null, null), dir));
            assertTrue(absolute.getMessage().contains("escapes"), absolute.getMessage());

            Files.write(secret, new byte[]{0, 0, 0, 0});
            Files.createSymbolicLink(link, secret);
            OcrException linked = assertThrows(OcrException.class,
                    () -> Onnx.read(externalModel("link.bin", null, null), dir));
            assertTrue(linked.getMessage().contains("escapes"), linked.getMessage());

            OcrException range = assertThrows(OcrException.class,
                    () -> Onnx.read(externalModel("w.bin", "9223372036854775807", "1"), dir));
            assertTrue(range.getMessage().contains("bad external range"), range.getMessage());
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(weights);
            Files.deleteIfExists(dir);
            Files.deleteIfExists(secret);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void wrappedOnnxDimensionIsRejected() {
        // -2^32 truncates to 0 through a signed int cast, so the weight used to load as empty.
        OcrException wrappedZero = assertThrows(OcrException.class,
                () -> Onnx.read(weightModel(null, -4294967296L), Path.of(".")));
        assertTrue(wrappedZero.getMessage().contains("negative dimension"), wrappedZero.getMessage());
        assertTrue(wrappedZero.getMessage().contains("w"), wrappedZero.getMessage());

        OcrException min = assertThrows(OcrException.class,
                () -> Onnx.read(weightModel(null, Long.MIN_VALUE), Path.of(".")));
        assertTrue(min.getMessage().contains("negative dimension"), min.getMessage());

        // Low 32 bits are 48 and four bytes per element match that invented shape.
        byte[] looksLike48 = new byte[48 * 4];
        OcrException wrappedShape = assertThrows(OcrException.class,
                () -> Onnx.read(weightModel(looksLike48, 1L, -4294967296L + 48), Path.of(".")));
        assertTrue(wrappedShape.getMessage().contains("negative dimension"), wrappedShape.getMessage());

        OcrException tooBig = assertThrows(OcrException.class,
                () -> Onnx.read(weightModel(null, 2147483648L), Path.of(".")));
        assertTrue(tooBig.getMessage().contains("dim too large in w"), tooBig.getMessage());

        Graph empty = Onnx.read(weightModel(null, 0L), Path.of("."));
        assertArrayEquals(new int[]{0}, empty.initializers.get("w").dims);
        assertEquals(0, empty.initializers.get("w").numel());

        Graph one = Onnx.read(weightModel(new byte[]{0, 0, (byte) 0x80, 0x3f}, 1L), Path.of("."));
        assertArrayEquals(new int[]{1}, one.initializers.get("w").dims);
        assertEquals(1f, one.initializers.get("w").f[0], 0f);

        OcrException inputWrapped = assertThrows(OcrException.class,
                () -> Onnx.read(inputShapeModel(4294967299L), Path.of(".")));
        assertTrue(inputWrapped.getMessage().contains("dim too large in x"), inputWrapped.getMessage());

        OcrException inputNegative = assertThrows(OcrException.class,
                () -> Onnx.read(inputShapeModel(-5L), Path.of(".")));
        assertTrue(inputNegative.getMessage().contains("negative dimension"), inputNegative.getMessage());
        assertTrue(inputNegative.getMessage().contains("x"), inputNegative.getMessage());

        Graph dynamic = Onnx.read(inputShapeModel(1L, "h"), Path.of("."));
        assertArrayEquals(new int[]{1, -1}, dynamic.inputs.get(0).dims);
        Graph fixed = Onnx.read(inputShapeModel(48L), Path.of("."));
        assertArrayEquals(new int[]{48}, fixed.inputs.get(0).dims);
    }

    @Test
    void wrappedOnnxOpsetIsRejected() {
        // -2^32 + 14 truncates to opset 14, which is a real PP-OCR opset.
        OcrException wrapped = assertThrows(OcrException.class,
                () -> Onnx.read(opsetModel(-4294967296L + 14), Path.of(".")));
        assertTrue(wrapped.getMessage().contains("bad opset"), wrapped.getMessage());

        OcrException huge = assertThrows(OcrException.class,
                () -> Onnx.read(opsetModel(4294967296L + 9), Path.of(".")));
        assertTrue(huge.getMessage().contains("bad opset"), huge.getMessage());

        assertEquals(14, Onnx.read(opsetModel(14), Path.of(".")).opset);
        assertEquals(9, Onnx.read(opsetModel(9), Path.of(".")).opset);
        OcrException missing = assertThrows(OcrException.class,
                () -> Onnx.read(opsetModel(0), Path.of(".")));
        assertTrue(missing.getMessage().contains("no ai.onnx opset"), missing.getMessage());
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void classifierSmokeOnZeros() {
        assumeTrue(Files.exists(model(ModelFiles.CLS)));
        Session session = Session.load(model(ModelFiles.CLS), new Session.Options(true, 1, null, false, false, true));
        long started = System.nanoTime();
        Tensor out = session.run(Map.of(session.inputName(),
                Tensor.f32(new int[]{1, 3, 80, 160}, new float[3 * 80 * 160]))).values().iterator().next();
        System.out.printf("cls smoke %.1f ms vector=%s%n",
                (System.nanoTime() - started) / 1e6, session.vector());
        assertEquals(2, out.rank());
        assertEquals(2, out.dims[out.dims.length - 1]);
        float sum = out.f[0] + out.f[1];
        assertEquals(1f, sum, 1e-3);
        assertTrue(Float.isFinite(out.f[0]) && Float.isFinite(out.f[1]));
    }

    private static float[] det64(boolean optimize) {
        Session session = Session.load(model(ModelFiles.DET),
                new Session.Options(true, 1, null, false, false, optimize));
        float[] input = new float[3 * 64 * 64];
        for (int i = 0; i < input.length; i++) {
            input[i] = ((i * 17) % 100) / 50f - 1f;
        }
        long started = System.nanoTime();
        Tensor out = session.run(Map.of(session.inputName(), Tensor.f32(new int[]{1, 3, 64, 64}, input)))
                .values().iterator().next();
        System.out.printf("det 64 optimize=%s %.1f ms shape=%s%n",
                optimize, (System.nanoTime() - started) / 1e6, out.shapeString());
        assertEquals(4, out.rank());
        assertEquals(1, out.dims[0]);
        assertTrue(out.dims[2] > 0 && out.dims[3] > 0);
        return out.f.clone();
    }

    private static float[] runAffine(boolean wide, boolean optimize, boolean vector) {
        Graph graph = affineGraph(wide);
        Session session = Session.create(graph, new Session.Options(vector, 1, null, false, false, optimize));
        int n = wide ? 1 * 5 * 5 : 1 * 2 * 2;
        return session.run(Map.of("x", Tensor.f32(new int[]{1, 1, wide ? 5 : 2, wide ? 5 : 2}, sequence(n, 0.2f))))
                .get("y").f.clone();
    }

    /** Conv, scalar mul, scalar add, HardSwish, scalar mul, scalar add. */
    private static Graph affineGraph(boolean wide) {
        Graph graph = new Graph();
        graph.opset = 14;
        graph.inputs.add(new Sig("x", Tensor.F32, null));
        graph.outputs.add(new Sig("y", Tensor.F32, null));
        int k = wide ? 3 : 1;
        graph.initializers.put("w", Tensor.f32(new int[]{1, 1, k, k}, sequence(k * k, 0.3f)));
        graph.initializers.put("b", Tensor.f32(new int[]{1}, new float[]{0.5f}));
        graph.initializers.put("s1", Tensor.f32(new int[]{1}, new float[]{0.5f}));
        graph.initializers.put("t1", Tensor.f32(new int[]{1}, new float[]{-1f}));
        graph.initializers.put("s2", Tensor.f32(new int[]{1}, new float[]{4f}));
        graph.initializers.put("t2", Tensor.f32(new int[]{1}, new float[]{0.25f}));
        Map<String, Attr> attrs = wide ? attrs(Attr.ints("pads", 1, 1, 1, 1)) : new LinkedHashMap<>();
        graph.nodes.add(node("Conv", "c", attrs, "x", "w", "b", "c0"));
        graph.nodes.add(node("Mul", "m1", "c0", "s1", "m1"));
        graph.nodes.add(node("Add", "a1", "m1", "t1", "a1"));
        graph.nodes.add(node("HardSwish", "h", "a1", "h"));
        graph.nodes.add(node("Mul", "m2", "h", "s2", "m2"));
        graph.nodes.add(node("Add", "a2", "m2", "t2", "y"));
        return graph;
    }

    private static float[] conv1x1(boolean vector) {
        return exec(14, node("Conv", "c", "x", "w", "b", "y"),
                Map.of(
                        "w", Tensor.f32(new int[]{1, 2, 1, 1}, new float[]{3, 4}),
                        "b", Tensor.f32(new int[]{1}, new float[]{5})),
                Map.of("x", Tensor.f32(new int[]{1, 2, 1, 1}, new float[]{1, 2})),
                vector).f;
    }

    private static Tensor exec(int opset, Node node, Map<String, Tensor> initializers, Map<String, Tensor> inputs, boolean vector) {
        Graph graph = graph(opset, node, initializers);
        Session session = Session.create(graph, new Session.Options(vector, 1, null, false, false, false));
        return session.run(inputs).get(node.outputs[0]);
    }

    private static Graph graph(int opset, Node node, Map<String, Tensor> initializers) {
        Graph graph = new Graph();
        graph.opset = opset;
        initializers.forEach(graph.initializers::put);
        for (String name : node.inputs) {
            if (!initializers.containsKey(name)) {
                graph.inputs.add(new Sig(name, Tensor.F32, null));
            }
        }
        graph.nodes.add(node);
        graph.outputs.add(new Sig(node.outputs[0], Tensor.F32, null));
        return graph;
    }

    private static Node node(String op, String name, String... io) {
        return node(op, name, new LinkedHashMap<>(), io);
    }

    private static Node node(String op, String name, Map<String, Attr> attrs, String... io) {
        String[] inputs = new String[io.length - 1];
        System.arraycopy(io, 0, inputs, 0, inputs.length);
        return new Node(op, name, inputs, new String[]{io[io.length - 1]}, attrs);
    }

    private static Map<String, Attr> attrs(Attr... values) {
        Map<String, Attr> map = new LinkedHashMap<>();
        for (Attr attr : values) {
            map.put(attr.name, attr);
        }
        return map;
    }

    private static Map<String, Tensor> inputs(String a, Tensor ta, String b, Tensor tb) {
        Map<String, Tensor> map = new LinkedHashMap<>();
        map.put(a, ta);
        map.put(b, tb);
        return map;
    }

    private static float[] sequence(int n, float scale) {
        float[] data = new float[n];
        for (int i = 0; i < n; i++) {
            data[i] = ((i % 11) - 5) * scale;
        }
        return data;
    }

    private static void assertClose(float[] expected, float[] actual, float tol) {
        assertEquals(expected.length, actual.length);
        float max = maxAbs(expected, actual);
        assertTrue(max <= tol, "max abs " + max);
    }

    private static float maxAbs(float[] a, float[] b) {
        float max = 0f;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
        }
        return max;
    }

    private static Path model(String name) {
        return Path.of("models", name);
    }

    /** Minimal ONNX model whose only initializer is the float tensor {@code w}. */
    private static byte[] weightModel(byte[] raw, long... dims) {
        ProtoBuf tensor = new ProtoBuf();
        for (long dim : dims) {
            tensor.uint(1, dim);
        }
        tensor.uint(2, 1);
        tensor.str(8, "w");
        if (raw != null) {
            tensor.msg(9, raw);
        }
        ProtoBuf graph = new ProtoBuf();
        graph.msg(5, tensor.toByteArray());
        return withOpset(graph.toByteArray());
    }

    /** Minimal ONNX model whose only initializer is an int64 tensor with packed {@code int64_data}. */
    private static byte[] int64Model(byte[] packed, long... dims) {
        ProtoBuf tensor = new ProtoBuf();
        for (long dim : dims) {
            tensor.uint(1, dim);
        }
        tensor.uint(2, 7);
        tensor.msg(7, packed);
        tensor.str(8, "w");
        ProtoBuf graph = new ProtoBuf();
        graph.msg(5, tensor.toByteArray());
        return withOpset(graph.toByteArray());
    }

    /**
     * Graph input {@code x}. A {@link Long} is a dim_value and a {@link String} is a dim_param.
     */
    private static byte[] inputShapeModel(Object... dims) {
        ProtoBuf shape = new ProtoBuf();
        for (Object dim : dims) {
            ProtoBuf one = new ProtoBuf();
            if (dim instanceof String param) {
                one.str(2, param);
            } else if (dim instanceof Long value) {
                one.uint(1, value);
            } else {
                throw new IllegalArgumentException(String.valueOf(dim));
            }
            shape.msg(1, one.toByteArray());
        }
        ProtoBuf tensorType = new ProtoBuf();
        tensorType.uint(1, 1);
        tensorType.msg(2, shape.toByteArray());
        ProtoBuf type = new ProtoBuf();
        type.msg(1, tensorType.toByteArray());
        ProtoBuf info = new ProtoBuf();
        info.str(1, "x");
        info.msg(2, type.toByteArray());
        ProtoBuf graph = new ProtoBuf();
        graph.msg(11, info.toByteArray());
        return withOpset(graph.toByteArray());
    }

    private static byte[] opsetModel(long version) {
        ProtoBuf graph = new ProtoBuf();
        ProtoBuf opset = new ProtoBuf();
        opset.uint(2, version);
        ProtoBuf model = new ProtoBuf();
        model.msg(7, graph.toByteArray());
        model.msg(8, opset.toByteArray());
        return model.toByteArray();
    }

    private static byte[] withOpset(byte[] graphBody) {
        ProtoBuf opset = new ProtoBuf();
        opset.uint(2, 14);
        ProtoBuf model = new ProtoBuf();
        model.msg(7, graphBody);
        model.msg(8, opset.toByteArray());
        return model.toByteArray();
    }

    /** Minimal ONNX model whose only initializer is an external float. */
    private static byte[] externalModel(String location, String offset, String length) {
        ProtoBuf tensor = new ProtoBuf();
        tensor.uint(1, 1);
        tensor.uint(2, 1);
        tensor.str(8, "w");
        tensor.msg(13, entry("location", location));
        if (offset != null) {
            tensor.msg(13, entry("offset", offset));
        }
        if (length != null) {
            tensor.msg(13, entry("length", length));
        }
        tensor.uint(14, 1);
        ProtoBuf graph = new ProtoBuf();
        graph.msg(5, tensor.toByteArray());
        ProtoBuf opset = new ProtoBuf();
        opset.uint(2, 14);
        ProtoBuf model = new ProtoBuf();
        model.msg(7, graph.toByteArray());
        model.msg(8, opset.toByteArray());
        return model.toByteArray();
    }

    private static byte[] entry(String key, String value) {
        ProtoBuf entry = new ProtoBuf();
        entry.str(1, key);
        entry.str(2, value);
        return entry.toByteArray();
    }

    private static final class ProtoBuf {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private void varint(long v) {
            while ((v & ~0x7fL) != 0) {
                out.write((int) ((v & 0x7f) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }

        private void tag(int field, int wire) {
            varint(((long) field << 3) | wire);
        }

        private void uint(int field, long v) {
            tag(field, 0);
            varint(v);
        }

        private void str(int field, String s) {
            byte[] body = s.getBytes(StandardCharsets.UTF_8);
            tag(field, 2);
            varint(body.length);
            out.write(body, 0, body.length);
        }

        private void msg(int field, byte[] body) {
            tag(field, 2);
            varint(body.length);
            out.write(body, 0, body.length);
        }

        private byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    private static Sig input(Session session) {
        String name = session.inputName();
        for (Sig sig : session.graph().inputs) {
            if (name.equals(sig.name)) {
                return sig;
            }
        }
        throw new AssertionError(name);
    }
}
