package io.github.kamilkrzywanski.ocr.internal.runtime;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Random operators against plain references in double precision, on the scalar and the vector
 * path, alone and with a thread pool, and random convolution chains raw against optimized. The
 * arena of every session hands its buffers out full of NaN ({@link Session#poisoned}), so an
 * output element that a kernel leaves unwritten, or a buffer it takes for zero, fails the
 * comparison. The seeds are fixed: a failure names the case and repeats.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class OperatorFuzzTest {
    private static Random R;
    private static ExecutorService pool;
    private static final List<String> failures = new ArrayList<>();

    @BeforeAll
    static void startPool() {
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterAll
    static void stopPool() {
        pool.shutdownNow();
    }

    @BeforeEach
    void seed() {
        R = new Random(12345);
        failures.clear();
    }

    private interface Case {
        void run(int iteration);
    }

    private static void repeat(int times, Case one) {
        for (int i = 0; i < times; i++) {
            one.run(i);
        }
        assertTrue(failures.isEmpty(), failures.size() + " of " + times + " cases failed, the first ones:\n"
                + String.join("\n", failures.subList(0, Math.min(5, failures.size()))));
    }

    @Test
    void convolutionsMatchTheReference() {
        repeat(200, OperatorFuzzTest::conv);
    }

    @Test
    void transposedConvolutionsMatchTheReference() {
        repeat(150, OperatorFuzzTest::convTranspose);
    }

    @Test
    void poolingMatchesTheReference() {
        repeat(1500, OperatorFuzzTest::pool);
    }

    @Test
    void slicesMatchTheReference() {
        repeat(1500, OperatorFuzzTest::slice);
    }

    @Test
    void broadcastArithmeticMatchesTheReference() {
        repeat(1500, OperatorFuzzTest::binary);
    }

    @Test
    void matrixProductsMatchTheReference() {
        repeat(600, OperatorFuzzTest::matmul);
    }

    @Test
    void transposeReduceMeanSoftmaxAndResizeMatchTheReference() {
        repeat(2000, OperatorFuzzTest::misc);
    }

    @Test
    void optimizedConvolutionChainsMatchTheRawGraph() {
        repeat(1500, OperatorFuzzTest::fusion);
    }

    static int rnd(int lo, int hi) {
        return lo + R.nextInt(hi - lo + 1);
    }

    static float[] rand(int n) {
        float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = R.nextFloat() * 2 - 1;
        return f;
    }

    static Graph graph(int opset, Map<String, Tensor> init, String out, String[] ins, Node... nodes) {
        Graph g = new Graph();
        g.opset = opset;
        g.initializers.putAll(init);
        for (String in : ins) g.inputs.add(new Sig(in, 1, null));
        g.outputs.add(new Sig(out, 1, null));
        g.nodes.addAll(Arrays.asList(nodes));
        return g;
    }

    /** Modes: 0 scalar, 1 vector, 2 vector with a pool, 3 scalar with a pool. Every buffer starts as NaN. */
    static Tensor run(Graph g, Map<String, Tensor> inputs, int mode, boolean optimize) {
        boolean vector = mode == 1 || mode == 2;
        boolean par = mode >= 2;
        Session s = Session.poisoned(g, new Session.Options(vector, par ? 4 : 1, par ? pool : null, false, false, optimize));
        return s.run(inputs).values().iterator().next();
    }

    static boolean close(float[] ref, Tensor got, int[] dims, String what) {
        if (!Arrays.equals(dims, got.dims)) {
            fail(what + " DIMS expected " + Arrays.toString(dims) + " got " + Arrays.toString(got.dims), what.split(" ")[0] + "-dims");
            return false;
        }
        for (int i = 0; i < ref.length; i++) {
            float d = Math.abs(ref[i] - got.f[i]);
            if (!(d <= 2e-4f * (1 + Math.abs(ref[i])))) {
                fail(what + " VALUE at " + i + " expected " + ref[i] + " got " + got.f[i], what.split(" ")[0] + "-value");
                return false;
            }
        }
        return true;
    }

    static void fail(String msg, String kind) {
        failures.add(msg);
    }

    static int[] samePad(int in, int k, int s, int d, boolean upper) {
        int out = (in + s - 1) / s;
        int total = Math.max(0, (out - 1) * s + d * (k - 1) + 1 - in);
        int small = total / 2;
        return upper ? new int[]{small, total - small} : new int[]{total - small, small};
    }

    static void conv(int iter) {
        int group = R.nextInt(4) == 0 ? rnd(2, 4) : 1;
        int icpg = rnd(1, 3), ocpg = rnd(1, 3);
        boolean depthwise = R.nextInt(5) == 0;
        if (depthwise) {
            icpg = 1;
        }
        int c = icpg * group, m = ocpg * group;
        int kH, kW;
        switch (R.nextInt(6)) {
            case 0 -> { kH = 1; kW = 1; }
            case 1, 2 -> { kH = 3; kW = 3; }
            case 3 -> { kH = rnd(1, 5); kW = 3; }
            default -> { kH = rnd(1, 5); kW = rnd(1, 5); }
        }
        int sH = R.nextInt(3) == 0 ? 2 : (R.nextInt(8) == 0 ? 3 : 1);
        int sW = R.nextInt(3) == 0 ? 2 : (R.nextInt(8) == 0 ? 3 : 1);
        if (R.nextBoolean()) sW = sH;
        int dH = R.nextInt(6) == 0 ? 2 : 1, dW = R.nextInt(6) == 0 ? 2 : 1;
        int inH = rnd(1, 40), inW = rnd(1, 70);
        int batch = R.nextInt(6) == 0 ? 2 : 1;
        int autoMode = R.nextInt(8);
        int[] pads;
        Node node = Node.of("Conv", "c", R.nextBoolean() ? new String[]{"x", "w", "b"} : new String[]{"x", "w"}, new String[]{"y"});
        if (autoMode == 0) {
            int[] ph = samePad(inH, kH, sH, dH, true), pw = samePad(inW, kW, sW, dW, true);
            pads = new int[]{ph[0], pw[0], ph[1], pw[1]};
            node.attrs.put("auto_pad", Attr.of("auto_pad", "SAME_UPPER"));
        } else if (autoMode == 1) {
            int[] ph = samePad(inH, kH, sH, dH, false), pw = samePad(inW, kW, sW, dW, false);
            pads = new int[]{ph[0], pw[0], ph[1], pw[1]};
            node.attrs.put("auto_pad", Attr.of("auto_pad", "SAME_LOWER"));
        } else if (autoMode == 2) {
            pads = new int[]{0, 0, 0, 0};
            if (R.nextBoolean()) node.attrs.put("auto_pad", Attr.of("auto_pad", "VALID"));
        } else if (autoMode <= 5) {
            int p = rnd(0, 2);
            pads = new int[]{p, p, p, p};
            if (R.nextBoolean() && kH == 3) pads = new int[]{1, 1, 1, 1};
            node.attrs.put("pads", Attr.ints("pads", pads[0], pads[1], pads[2], pads[3]));
        } else {
            pads = new int[]{rnd(0, 3), rnd(0, 3), rnd(0, 3), rnd(0, 3)};
            node.attrs.put("pads", Attr.ints("pads", pads[0], pads[1], pads[2], pads[3]));
        }
        node.attrs.put("strides", Attr.ints("strides", sH, sW));
        if (dH != 1 || dW != 1 || R.nextBoolean()) node.attrs.put("dilations", Attr.ints("dilations", dH, dW));
        if (group != 1 || R.nextBoolean()) node.attrs.put("group", Attr.of("group", (long) group));
        int numH = inH + pads[0] + pads[2] - dH * (kH - 1) - 1;
        int numW = inW + pads[1] + pads[3] - dW * (kW - 1) - 1;
        if (numH < 0 || numW < 0) return;
        int outH = numH / sH + 1, outW = numW / sW + 1;
        float[] x = rand(batch * c * inH * inW);
        float[] w = rand(m * icpg * kH * kW);
        // sprinkle zeros so zero-skip branches run
        for (int i = 0; i < w.length; i++) if (R.nextInt(7) == 0) w[i] = 0f;
        float[] b = rand(m);
        boolean hasBias = node.inputs.length == 3;
        float[] ref = new float[batch * m * outH * outW];
        for (int n = 0; n < batch; n++)
            for (int oc = 0; oc < m; oc++) {
                int g = oc / ocpg;
                for (int oh = 0; oh < outH; oh++)
                    for (int ow = 0; ow < outW; ow++) {
                        double sum = hasBias ? b[oc] : 0;
                        for (int ic = 0; ic < icpg; ic++)
                            for (int kh = 0; kh < kH; kh++)
                                for (int kw = 0; kw < kW; kw++) {
                                    int ih = oh * sH + kh * dH - pads[0], iw = ow * sW + kw * dW - pads[1];
                                    if (ih < 0 || iw < 0 || ih >= inH || iw >= inW) continue;
                                    sum += (double) w[((oc * icpg + ic) * kH + kh) * kW + kw]
                                            * x[((n * c + g * icpg + ic) * inH + ih) * inW + iw];
                                }
                        ref[((n * m + oc) * outH + oh) * outW + ow] = (float) sum;
                    }
            }
        Map<String, Tensor> init = new LinkedHashMap<>();
        init.put("w", Tensor.f32(new int[]{m, icpg, kH, kW}, w));
        if (hasBias) init.put("b", Tensor.f32(new int[]{m}, b));
        String desc = "conv k=" + kH + "x" + kW + " s=" + sH + "x" + sW + " d=" + dH + "x" + dW + " pads=" + Arrays.toString(pads)
                + " auto=" + node.str("auto_pad", "-") + " g=" + group + " icpg=" + icpg + " ocpg=" + ocpg + " in=" + inH + "x" + inW + " batch=" + batch + " bias=" + hasBias;
        for (int mode = 0; mode < 4; mode++) {
            try {
                Tensor got = run(graph(14, init, "y", new String[]{"x"}, node),
                        Map.of("x", Tensor.f32(new int[]{batch, c, inH, inW}, x)), mode, false);
                close(ref, got, new int[]{batch, m, outH, outW}, desc + " mode=" + mode);
            } catch (RuntimeException e) {
                fail(desc + " mode=" + mode + " EXC " + e, "conv-exc");
            }
        }
    }

    static void convTranspose(int iter) {
        int group = R.nextInt(4) == 0 ? 2 : 1;
        int icpg = rnd(1, 3), ocpg = rnd(1, 3);
        int c = icpg * group, m = ocpg * group;
        int kH = rnd(1, 4), kW = rnd(1, 4);
        int sH = rnd(1, 3), sW = rnd(1, 3);
        if (R.nextBoolean()) { sH = kH; sW = kW; }
        if (R.nextInt(3) == 0) { kH = kW = sH = sW = 2; }
        int dH = R.nextInt(6) == 0 ? 2 : 1, dW = R.nextInt(6) == 0 ? 2 : 1;
        int[] pads = R.nextBoolean() ? new int[]{0, 0, 0, 0} : new int[]{rnd(0, 1), rnd(0, 1), rnd(0, 1), rnd(0, 1)};
        int opH = R.nextInt(5) == 0 ? rnd(0, sH - 1) : 0, opW = R.nextInt(5) == 0 ? rnd(0, sW - 1) : 0;
        int inH = rnd(1, 20), inW = rnd(1, 30), batch = R.nextInt(6) == 0 ? 2 : 1;
        int outH = sH * (inH - 1) + opH + dH * (kH - 1) + 1 - pads[0] - pads[2];
        int outW = sW * (inW - 1) + opW + dW * (kW - 1) + 1 - pads[1] - pads[3];
        if (outH <= 0 || outW <= 0) return;
        boolean hasBias = R.nextBoolean();
        Node node = Node.of("ConvTranspose", "t", hasBias ? new String[]{"x", "w", "b"} : new String[]{"x", "w"}, new String[]{"y"});
        node.attrs.put("strides", Attr.ints("strides", sH, sW));
        node.attrs.put("pads", Attr.ints("pads", pads[0], pads[1], pads[2], pads[3]));
        node.attrs.put("dilations", Attr.ints("dilations", dH, dW));
        node.attrs.put("output_padding", Attr.ints("output_padding", opH, opW));
        node.attrs.put("group", Attr.of("group", (long) group));
        float[] x = rand(batch * c * inH * inW), w = rand(c * ocpg * kH * kW), b = rand(m);
        for (int i = 0; i < w.length; i++) if (R.nextInt(7) == 0) w[i] = 0f;
        double[] acc = new double[batch * m * outH * outW];
        for (int n = 0; n < batch; n++)
            for (int oc = 0; oc < m; oc++)
                for (int i = 0; i < outH * outW; i++) acc[(n * m + oc) * outH * outW + i] = hasBias ? b[oc] : 0;
        for (int n = 0; n < batch; n++)
            for (int ic = 0; ic < c; ic++) {
                int g = ic / icpg;
                for (int ol = 0; ol < ocpg; ol++) {
                    int oc = g * ocpg + ol;
                    for (int ih = 0; ih < inH; ih++)
                        for (int iw = 0; iw < inW; iw++)
                            for (int kh = 0; kh < kH; kh++)
                                for (int kw = 0; kw < kW; kw++) {
                                    int oh = ih * sH + kh * dH - pads[0], ow = iw * sW + kw * dW - pads[1];
                                    if (oh < 0 || ow < 0 || oh >= outH || ow >= outW) continue;
                                    acc[((n * m + oc) * outH + oh) * outW + ow] += (double) x[((n * c + ic) * inH + ih) * inW + iw]
                                            * w[((ic * ocpg + ol) * kH + kh) * kW + kw];
                                }
                }
            }
        float[] ref = new float[acc.length];
        for (int i = 0; i < acc.length; i++) ref[i] = (float) acc[i];
        Map<String, Tensor> init = new LinkedHashMap<>();
        init.put("w", Tensor.f32(new int[]{c, ocpg, kH, kW}, w));
        if (hasBias) init.put("b", Tensor.f32(new int[]{m}, b));
        String desc = "convT k=" + kH + "x" + kW + " s=" + sH + "x" + sW + " d=" + dH + "x" + dW + " pads=" + Arrays.toString(pads)
                + " op=" + opH + "," + opW + " g=" + group + " in=" + inH + "x" + inW;
        for (int mode = 0; mode < 4; mode++) {
            try {
                Tensor got = run(graph(14, init, "y", new String[]{"x"}, node),
                        Map.of("x", Tensor.f32(new int[]{batch, c, inH, inW}, x)), mode, false);
                close(ref, got, new int[]{batch, m, outH, outW}, desc + " mode=" + mode);
            } catch (RuntimeException e) {
                fail(desc + " mode=" + mode + " EXC " + e, "convT-exc");
            }
        }
    }

    static void pool(int iter) {
        boolean max = R.nextBoolean();
        int kH = rnd(1, 4), kW = rnd(1, 4), sH = rnd(1, 3), sW = rnd(1, 3);
        if (R.nextInt(3) == 0) { kH = kW = 2; sH = sW = 1; }
        int inH = rnd(1, 12), inW = rnd(1, 12), c = rnd(1, 3);
        boolean ceil = R.nextInt(3) == 0;
        boolean cip = !max && R.nextInt(3) == 0;
        int[] pads = new int[]{rnd(0, (kH) / 2), rnd(0, (kW) / 2), rnd(0, kH / 2), rnd(0, kW / 2)};
        if (R.nextBoolean()) pads = new int[]{0, 0, 0, 0};
        Node node = Node.of(max ? "MaxPool" : "AveragePool", "p", new String[]{"x"}, new String[]{"y"});
        node.attrs.put("kernel_shape", Attr.ints("kernel_shape", kH, kW));
        node.attrs.put("strides", Attr.ints("strides", sH, sW));
        String auto = "-";
        if (R.nextInt(4) == 0) {
            boolean upper = R.nextBoolean();
            auto = upper ? "SAME_UPPER" : "SAME_LOWER";
            int[] ph = samePad(inH, kH, sH, 1, upper), pw = samePad(inW, kW, sW, 1, upper);
            pads = new int[]{ph[0], pw[0], ph[1], pw[1]};
            node.attrs.put("auto_pad", Attr.of("auto_pad", auto));
            ceil = false;
        } else {
            node.attrs.put("pads", Attr.ints("pads", pads[0], pads[1], pads[2], pads[3]));
        }
        if (ceil) node.attrs.put("ceil_mode", Attr.of("ceil_mode", 1L));
        if (cip) node.attrs.put("count_include_pad", Attr.of("count_include_pad", 1L));
        int outH = outSize(inH, kH, sH, pads[0], pads[2], ceil), outW = outSize(inW, kW, sW, pads[1], pads[3], ceil);
        if (outH <= 0 || outW <= 0) return;
        float[] x = rand(c * inH * inW);
        float[] ref = new float[c * outH * outW];
        for (int ci = 0; ci < c; ci++)
            for (int oh = 0; oh < outH; oh++)
                for (int ow = 0; ow < outW; ow++) {
                    double sum = 0;
                    float mx = Float.NEGATIVE_INFINITY;
                    int valid = 0, padded = 0;
                    for (int kh = 0; kh < kH; kh++)
                        for (int kw = 0; kw < kW; kw++) {
                            int ih = oh * sH + kh - pads[0], iw = ow * sW + kw - pads[1];
                            // inside input + explicit padding (PyTorch / current ONNX reference divisor)
                            if (ih >= -pads[0] && ih < inH + pads[2] && iw >= -pads[1] && iw < inW + pads[3]) padded++;
                            if (ih < 0 || iw < 0 || ih >= inH || iw >= inW) continue;
                            float v = x[(ci * inH + ih) * inW + iw];
                            sum += v;
                            mx = Math.max(mx, v);
                            valid++;
                        }
                    ref[(ci * outH + oh) * outW + ow] = max ? mx : (float) (sum / (cip ? padded : valid));
                }
        String desc = (max ? "maxpool" : (cip ? (ceil ? "avgpool-cip-ceil" : "avgpool-cip") : "avgpool")) + " k=" + kH + "x" + kW + " s=" + sH + "x" + sW + " pads=" + Arrays.toString(pads)
                + " ceil=" + ceil + " cip=" + cip + " auto=" + auto + " in=" + inH + "x" + inW;
        for (int mode = 0; mode < 2; mode++) {
            try {
                Tensor got = run(graph(14, Map.of(), "y", new String[]{"x"}, node),
                        Map.of("x", Tensor.f32(new int[]{1, c, inH, inW}, x)), mode, false);
                close(ref, got, new int[]{1, c, outH, outW}, desc + " mode=" + mode);
            } catch (RuntimeException e) {
                fail(desc + " mode=" + mode + " EXC " + e, "pool-exc");
            }
        }
    }

    static int outSize(int in, int k, int s, int p0, int p1, boolean ceil) {
        int num = in + p0 + p1 - k;
        if (num < 0) return 0;
        int out = ceil ? (num + s - 1) / s + 1 : num / s + 1;
        if (ceil && (out - 1) * s >= in + p0) out--;
        return out;
    }

    static void slice(int iter) {
        int rank = rnd(1, 3);
        int[] dims = new int[rank];
        for (int i = 0; i < rank; i++) dims[i] = rnd(1, 6);
        int n = Tensor.numel(dims);
        float[] x = rand(n);
        int nAxes = rnd(1, rank);
        List<Integer> axesList = new ArrayList<>();
        for (int i = 0; i < rank; i++) axesList.add(i);
        Collections.shuffle(axesList, R);
        long[] axes = new long[nAxes], starts = new long[nAxes], ends = new long[nAxes], steps = new long[nAxes];
        int[] st = new int[rank], sp = new int[rank], len = dims.clone();
        Arrays.fill(sp, 1);
        for (int i = 0; i < nAxes; i++) {
            int ax = axesList.get(i);
            int d = dims[ax];
            axes[i] = R.nextBoolean() ? ax : ax - rank;
            long step = new long[]{1, 1, 2, 3, -1, -1, -2, -3}[R.nextInt(8)];
            long s = pick(d), e = pick(d);
            starts[i] = s;
            ends[i] = e;
            steps[i] = step;
            // python slice semantics
            long ss = s < 0 ? s + d : s, ee = e < 0 ? e + d : e;
            if (step > 0) {
                ss = Math.max(0, Math.min(d, ss));
                ee = Math.max(0, Math.min(d, ee));
                len[ax] = (int) Math.max(0, (ee - ss + step - 1) / step);
            } else {
                ss = Math.max(0, Math.min(d - 1, ss));
                ee = Math.max(-1, Math.min(d - 1, ee));
                len[ax] = (int) Math.max(0, (ss - ee + (-step) - 1) / (-step));
            }
            st[ax] = (int) ss;
            sp[ax] = (int) step;
        }
        int outN = Tensor.numel(len);
        float[] ref = new float[outN];
        int[] coord = new int[rank];
        for (int p = 0; p < outN; p++) {
            int rem = p;
            for (int dd = rank - 1; dd >= 0; dd--) {
                coord[dd] = len[dd] == 0 ? 0 : rem % len[dd];
                if (len[dd] != 0) rem /= len[dd];
            }
            int src = 0, stride = 1;
            for (int dd = rank - 1; dd >= 0; dd--) {
                src += (st[dd] + coord[dd] * sp[dd]) * stride;
                stride *= dims[dd];
            }
            ref[p] = x[src];
        }
        Map<String, Tensor> init = new LinkedHashMap<>();
        init.put("starts", Tensor.i64(new int[]{nAxes}, starts));
        init.put("ends", Tensor.i64(new int[]{nAxes}, ends));
        init.put("axes", Tensor.i64(new int[]{nAxes}, axes));
        init.put("steps", Tensor.i64(new int[]{nAxes}, steps));
        Node node = Node.of("Slice", "s", new String[]{"x", "starts", "ends", "axes", "steps"}, new String[]{"y"});
        String desc = "slice dims=" + Arrays.toString(dims) + " axes=" + Arrays.toString(axes) + " starts=" + Arrays.toString(starts)
                + " ends=" + Arrays.toString(ends) + " steps=" + Arrays.toString(steps);
        try {
            Tensor got = run(graph(14, init, "y", new String[]{"x"}, node), Map.of("x", Tensor.f32(dims, x)), 0, false);
            close(ref, got, len, desc);
        } catch (RuntimeException e) {
            fail(desc + " EXC " + e, "slice-exc");
        }
    }

    static long pick(int d) {
        return switch (R.nextInt(7)) {
            case 0 -> Long.MAX_VALUE;
            case 1 -> Long.MIN_VALUE;
            case 2 -> Integer.MAX_VALUE;
            case 3 -> -(long) Integer.MAX_VALUE;
            default -> rnd(-d - 2, d + 2);
        };
    }

    static int[] randShape(int rank, int[] base) {
        int[] s = new int[rank];
        for (int i = 0; i < rank; i++) {
            int b = base[base.length - rank + i];
            s[i] = R.nextInt(3) == 0 ? 1 : b;
        }
        return s;
    }

    static void binary(int iter) {
        int rank = rnd(1, 4);
        int[] base = new int[rank];
        for (int i = 0; i < rank; i++) base[i] = new int[]{1, 2, 3, 5, 9, 17, 19}[R.nextInt(7)];
        int[] ad = randShape(rnd(1, rank), base), bd = randShape(rnd(1, rank), base);
        if (R.nextInt(4) == 0) ad = base.clone();
        if (R.nextInt(8) == 0) bd = base.clone();
        int[] od = Broadcast.shape(ad, bd);
        float[] a = rand(Tensor.numel(ad)), b = rand(Tensor.numel(bd));
        for (int i = 0; i < b.length; i++) if (Math.abs(b[i]) < 0.05f) b[i] = 0.5f;
        int op = R.nextInt(4);
        String[] names = {"Add", "Sub", "Mul", "Div"};
        int n = Tensor.numel(od);
        float[] ref = new float[n];
        int orank = od.length;
        int[] coord = new int[orank];
        for (int p = 0; p < n; p++) {
            int rem = p;
            for (int d = orank - 1; d >= 0; d--) {
                coord[d] = rem % od[d];
                rem /= od[d];
            }
            float av = a[index(coord, ad)], bv = b[index(coord, bd)];
            ref[p] = switch (op) {
                case 0 -> av + bv;
                case 1 -> av - bv;
                case 2 -> av * bv;
                default -> av / bv;
            };
        }
        Node node = Node.of(names[op], "b", new String[]{"a", "b"}, new String[]{"y"});
        Map<String, Tensor> in = new LinkedHashMap<>();
        in.put("a", Tensor.f32(ad, a));
        in.put("b", Tensor.f32(bd, b));
        String desc = "binary " + names[op] + " " + Arrays.toString(ad) + " " + Arrays.toString(bd);
        for (int mode = 0; mode < 2; mode++) {
            try {
                Tensor got = run(graph(14, Map.of(), "y", new String[]{"a", "b"}, node), in, mode, false);
                close(ref, got, od, desc + " mode=" + mode);
            } catch (RuntimeException e) {
                fail(desc + " EXC " + e, "binary-exc");
            }
        }
    }

    static int index(int[] coord, int[] dims) {
        int off = coord.length - dims.length;
        int idx = 0;
        for (int d = 0; d < dims.length; d++) {
            int cdim = dims[d] == 1 ? 0 : coord[d + off];
            idx = idx * dims[d] + cdim;
        }
        return idx;
    }

    static void matmul(int iter) {
        int m = rnd(1, 40), k = rnd(1, 40), n = rnd(1, 80);
        if (R.nextInt(5) == 0) { m = rnd(32, 40); k = rnd(32, 40); n = rnd(64, 90); }
        int[] pre = R.nextInt(3) == 0 ? new int[]{rnd(1, 3)} : R.nextInt(3) == 0 ? new int[]{rnd(1, 2), rnd(1, 3)} : new int[0];
        int[] aPre = pre, bPre = pre;
        int variant = R.nextInt(5);
        if (variant == 0) bPre = new int[0];
        if (variant == 1) aPre = new int[0];
        if (variant == 2 && pre.length > 0) { aPre = pre.clone(); aPre[0] = 1; }
        boolean aVec = R.nextInt(10) == 0, bVec = R.nextInt(10) == 0;
        int[] ad, bd;
        if (aVec) { ad = new int[]{k}; aPre = new int[0]; m = 1; } else { ad = concat(aPre, m, k); }
        if (bVec) { bd = new int[]{k}; bPre = new int[0]; n = 1; } else { bd = concat(bPre, k, n); }
        int[] cPre = aPre.length == 0 ? bPre : bPre.length == 0 ? aPre : Broadcast.shape(aPre, bPre);
        int[] od;
        {
            List<Integer> l = new ArrayList<>();
            for (int d : cPre) l.add(d);
            if (!aVec) l.add(m);
            if (!bVec) l.add(n);
            od = l.stream().mapToInt(Integer::intValue).toArray();
        }
        float[] a = rand(Tensor.numel(ad)), b = rand(Tensor.numel(bd));
        int batch = Tensor.numel(cPre);
        float[] ref = new float[batch * m * n];
        int[] coord = new int[cPre.length];
        for (int bi = 0; bi < batch; bi++) {
            int rem = bi;
            for (int d = cPre.length - 1; d >= 0; d--) { coord[d] = rem % cPre[d]; rem /= cPre[d]; }
            int ai = aPre.length == 0 ? 0 : index(coord, aPre), bj = bPre.length == 0 ? 0 : index(coord, bPre);
            for (int i = 0; i < m; i++)
                for (int j = 0; j < n; j++) {
                    double s = 0;
                    for (int kk = 0; kk < k; kk++) s += (double) a[ai * m * k + i * k + kk] * b[bj * k * n + kk * n + j];
                    ref[bi * m * n + i * n + j] = (float) s;
                }
        }
        Node node = Node.of("MatMul", "mm", new String[]{"a", "b"}, new String[]{"y"});
        Map<String, Tensor> in = new LinkedHashMap<>();
        in.put("a", Tensor.f32(ad, a));
        in.put("b", Tensor.f32(bd, b));
        String desc = "matmul " + Arrays.toString(ad) + " " + Arrays.toString(bd);
        for (int mode = 0; mode < 4; mode++) {
            try {
                Tensor got = run(graph(14, Map.of(), "y", new String[]{"a", "b"}, node), in, mode, false);
                close(ref, got, od, desc + " mode=" + mode);
            } catch (RuntimeException e) {
                fail(desc + " EXC " + e, "matmul-exc");
            }
        }
    }

    static int[] concat(int[] pre, int x, int y) {
        int[] r = Arrays.copyOf(pre, pre.length + 2);
        r[pre.length] = x;
        r[pre.length + 1] = y;
        return r;
    }

    /** Transpose, ReduceMean, Softmax, Resize(nearest integer + odd scales), Concat. */
    static void misc(int iter) {
        int rank = rnd(1, 4);
        int[] dims = new int[rank];
        for (int i = 0; i < rank; i++) dims[i] = rnd(1, 6);
        int n = Tensor.numel(dims);
        float[] x = rand(n);
        int[] strides = new int[rank];
        int acc = 1;
        for (int d = rank - 1; d >= 0; d--) { strides[d] = acc; acc *= dims[d]; }
        int which = R.nextInt(4);
        if (which == 0) {
            List<Integer> p = new ArrayList<>();
            for (int i = 0; i < rank; i++) p.add(i);
            Collections.shuffle(p, R);
            long[] perm = p.stream().mapToLong(Integer::longValue).toArray();
            int[] od = new int[rank];
            for (int i = 0; i < rank; i++) od[i] = dims[(int) perm[i]];
            float[] ref = new float[n];
            int[] oc = new int[rank];
            for (int q = 0; q < n; q++) {
                int rem = q;
                for (int d = rank - 1; d >= 0; d--) { oc[d] = rem % od[d]; rem /= od[d]; }
                int src = 0;
                for (int i = 0; i < rank; i++) src += oc[i] * strides[(int) perm[i]];
                ref[q] = x[src];
            }
            Node node = Node.of("Transpose", "t", new String[]{"x"}, new String[]{"y"});
            node.attrs.put("perm", Attr.ints("perm", perm));
            Tensor got = run(graph(14, Map.of(), "y", new String[]{"x"}, node), Map.of("x", Tensor.f32(dims, x)), 0, false);
            close(ref, got, od, "transpose " + Arrays.toString(dims) + Arrays.toString(perm));
        } else if (which == 1) {
            // ReduceMean over random axes subset
            boolean[] red = new boolean[rank];
            List<Long> axes = new ArrayList<>();
            for (int i = 0; i < rank; i++) if (R.nextBoolean()) { red[i] = true; axes.add(R.nextBoolean() ? (long) i : (long) i - rank); }
            boolean keep = R.nextBoolean();
            boolean all = axes.isEmpty();
            if (all) Arrays.fill(red, true);
            List<Integer> odl = new ArrayList<>();
            for (int i = 0; i < rank; i++) { if (!red[i]) odl.add(dims[i]); else if (keep) odl.add(1); }
            int[] od = odl.stream().mapToInt(Integer::intValue).toArray();
            int on = Tensor.numel(od);
            double[] sum = new double[on];
            int cnt = 1;
            for (int i = 0; i < rank; i++) if (red[i]) cnt *= dims[i];
            int[] c = new int[rank];
            for (int q = 0; q < n; q++) {
                int rem = q;
                for (int d = rank - 1; d >= 0; d--) { c[d] = rem % dims[d]; rem /= dims[d]; }
                int oi = 0;
                for (int d = 0; d < rank; d++) if (!red[d]) oi = oi * dims[d] + c[d];
                sum[oi] += x[q];
            }
            float[] ref = new float[on];
            for (int i = 0; i < on; i++) ref[i] = (float) (sum[i] / cnt);
            Node node = Node.of("ReduceMean", "r", new String[]{"x"}, new String[]{"y"});
            if (!all) node.attrs.put("axes", Attr.ints("axes", axes.stream().mapToLong(Long::longValue).toArray()));
            node.attrs.put("keepdims", Attr.of("keepdims", keep ? 1L : 0L));
            Tensor got = run(graph(14, Map.of(), "y", new String[]{"x"}, node), Map.of("x", Tensor.f32(dims, x)), 0, false);
            close(ref, got, od, "reducemean " + Arrays.toString(dims) + axes + " keep=" + keep);
        } else if (which == 2) {
            int opset = R.nextBoolean() ? 11 : 13;
            int axis = rnd(-rank, rank - 1);
            int ax = axis < 0 ? axis + rank : axis;
            float[] ref = new float[n];
            if (opset == 11) {
                int outer = 1, inner = 1;
                for (int d = 0; d < ax; d++) outer *= dims[d];
                for (int d = ax; d < rank; d++) inner *= dims[d];
                for (int o = 0; o < outer; o++) {
                    double s = 0;
                    for (int i = 0; i < inner; i++) s += Math.exp(x[o * inner + i]);
                    for (int i = 0; i < inner; i++) ref[o * inner + i] = (float) (Math.exp(x[o * inner + i]) / s);
                }
            } else {
                int[] c = new int[rank];
                double[] s = new double[n];
                for (int q = 0; q < n; q++) {
                    int rem = q;
                    for (int d = rank - 1; d >= 0; d--) { c[d] = rem % dims[d]; rem /= dims[d]; }
                    int base = q - c[ax] * strides[ax];
                    double t = 0;
                    for (int i = 0; i < dims[ax]; i++) t += Math.exp(x[base + i * strides[ax]]);
                    ref[q] = (float) (Math.exp(x[q]) / t);
                }
            }
            Node node = Node.of("Softmax", "s", new String[]{"x"}, new String[]{"y"});
            node.attrs.put("axis", Attr.of("axis", (long) axis));
            Tensor got = run(graph(opset, Map.of(), "y", new String[]{"x"}, node), Map.of("x", Tensor.f32(dims, x)), 0, false);
            close(ref, got, dims, "softmax opset=" + opset + " " + Arrays.toString(dims) + " axis=" + axis);
        } else {
            // Resize nearest NCHW with scales, all coordinate/nearest modes
            int c = rnd(1, 3), h = rnd(1, 7), w = rnd(1, 7);
            float sh = new float[]{1f, 2f, 4f, 1.5f, 0.5f, 3f, 2.6f}[R.nextInt(7)], sw = new float[]{1f, 2f, 4f, 1.5f, 0.5f, 3f, 0.7f}[R.nextInt(7)];
            String coord = new String[]{"asymmetric", "half_pixel", "pytorch_half_pixel", "align_corners"}[R.nextInt(4)];
            String near = new String[]{"floor", "ceil", "round_prefer_floor", "round_prefer_ceil"}[R.nextInt(4)];
            int oh = (int) Math.floor(h * sh), ow = (int) Math.floor(w * sw);
            if (oh == 0 || ow == 0) return;
            float[] xx = rand(c * h * w);
            float[] ref = new float[c * oh * ow];
            for (int ci = 0; ci < c; ci++)
                for (int y = 0; y < oh; y++)
                    for (int xo = 0; xo < ow; xo++)
                        ref[(ci * oh + y) * ow + xo] = xx[(ci * h + src(y, h, oh, sh, coord, near)) * w + src(xo, w, ow, sw, coord, near)];
            Node node = Node.of("Resize", "r", new String[]{"x", "", "scales"}, new String[]{"y"});
            node.attrs.put("mode", Attr.of("mode", "nearest"));
            node.attrs.put("coordinate_transformation_mode", Attr.of("coordinate_transformation_mode", coord));
            node.attrs.put("nearest_mode", Attr.of("nearest_mode", near));
            Map<String, Tensor> init = Map.of("scales", Tensor.f32(new int[]{4}, new float[]{1, 1, sh, sw}));
            for (int mode = 0; mode < 4; mode += 2) {
                Tensor got = run(graph(14, init, "y", new String[]{"x"}, node), Map.of("x", Tensor.f32(new int[]{1, c, h, w}, xx)), mode, false);
                close(ref, got, new int[]{1, c, oh, ow}, "resize " + coord + " " + near + " " + h + "x" + w + " scale " + sh + "," + sw);
            }
        }
    }

    /** ONNX reference formula in double. */
    static int src(int o, int in, int out, float scale, String coord, String near) {
        float x = switch (coord) {
            case "asymmetric" -> o / scale;
            case "half_pixel" -> (o + 0.5f) / scale - 0.5f;
            case "pytorch_half_pixel" -> out > 1 ? (o + 0.5f) / scale - 0.5f : 0;
            default -> out == 1 ? 0 : o * (in - 1f) / (out - 1f);
        };
        int idx = switch (near) {
            case "floor" -> (int) Math.floor(x);
            case "ceil" -> (int) Math.ceil(x);
            case "round_prefer_floor" -> x == (float) Math.floor(x) + 0.5f ? (int) Math.floor(x) : Math.round(x);
            default -> x == (float) Math.floor(x) + 0.5f ? (int) Math.ceil(x) : Math.round(x);
        };
        return Math.max(0, Math.min(in - 1, idx));
    }

    /** Random conv chains: raw vs optimized, scalar and vector. */
    static void fusion(int iter) {
        Graph[] gs = new Graph[2];
        long seed = R.nextLong();
        int[] shape = new int[4];
        for (int v = 0; v < 2; v++) {
            Random r = new Random(seed);
            Graph g = new Graph();
            g.opset = 14;
            g.inputs.add(new Sig("x", 1, null));
            int c = 1 + r.nextInt(3);
            int h = 3 + r.nextInt(6), w = 3 + r.nextInt(6);
            shape = new int[]{1, c, h, w};
            String cur = "x";
            int id = 0;
            int layers = 1 + r.nextInt(3);
            for (int l = 0; l < layers; l++) {
                boolean transpose = r.nextInt(5) == 0;
                int m = 1 + r.nextInt(3);
                int k = transpose ? 2 : (r.nextBoolean() ? 1 : 3);
                String wn = "w" + l, bn = "b" + l;
                float[] wt = new float[(transpose ? c * m : m * c) * k * k];
                for (int i = 0; i < wt.length; i++) wt[i] = r.nextFloat() - 0.5f;
                g.initializers.put(wn, Tensor.f32(transpose ? new int[]{c, m, k, k} : new int[]{m, c, k, k}, wt));
                boolean bias = r.nextBoolean();
                if (bias) g.initializers.put(bn, Tensor.f32(new int[]{m}, new float[]{0.1f, -0.2f, 0.3f}.length >= m ? Arrays.copyOf(new float[]{0.1f, -0.2f, 0.3f}, m) : new float[m]));
                String out = "t" + (id++);
                Node conv = Node.of(transpose ? "ConvTranspose" : "Conv", "conv" + l, bias ? new String[]{cur, wn, bn} : new String[]{cur, wn}, new String[]{out});
                if (transpose) conv.attrs.put("strides", Attr.ints("strides", 2, 2));
                else if (k == 3) conv.attrs.put("pads", Attr.ints("pads", 1, 1, 1, 1));
                g.nodes.add(conv);
                cur = out;
                // random epilogue pieces
                int pieces = r.nextInt(5);
                for (int p = 0; p < pieces; p++) {
                    int kind = r.nextInt(9);
                    String nx = "t" + (id++);
                    switch (kind) {
                        case 0 -> {
                            String p0 = "bn" + id;
                            g.initializers.put(p0 + "s", Tensor.f32(new int[]{m}, fill(r, m, 0.5f, 1.5f)));
                            g.initializers.put(p0 + "b", Tensor.f32(new int[]{m}, fill(r, m, -0.5f, 0.5f)));
                            g.initializers.put(p0 + "m", Tensor.f32(new int[]{m}, fill(r, m, -0.5f, 0.5f)));
                            g.initializers.put(p0 + "v", Tensor.f32(new int[]{m}, fill(r, m, 0.5f, 1.5f)));
                            g.nodes.add(Node.of("BatchNormalization", "bn" + id, new String[]{cur, p0 + "s", p0 + "b", p0 + "m", p0 + "v"}, new String[]{nx}));
                        }
                        case 1 -> {
                            String bname = "ab" + id;
                            int[] bd = r.nextBoolean() ? new int[]{1, m, 1, 1} : new int[]{m, 1, 1};
                            g.initializers.put(bname, Tensor.f32(bd, fill(r, m, -0.5f, 0.5f)));
                            g.nodes.add(Node.of("Add", "add" + id, r.nextBoolean() ? new String[]{cur, bname} : new String[]{bname, cur}, new String[]{nx}));
                        }
                        case 2 -> g.nodes.add(Node.of("Relu", "relu" + id, new String[]{cur}, new String[]{nx}));
                        case 3 -> g.nodes.add(Node.of("Sigmoid", "sig" + id, new String[]{cur}, new String[]{nx}));
                        case 4 -> {
                            Node hs = Node.of("HardSigmoid", "hs" + id, new String[]{cur}, new String[]{nx});
                            if (r.nextBoolean()) hs.attrs.put("alpha", Attr.of("alpha", 1f / 6f));
                            g.nodes.add(hs);
                        }
                        case 5 -> g.nodes.add(Node.of("HardSwish", "hsw" + id, new String[]{cur}, new String[]{nx}));
                        case 6 -> {
                            String mid = "t" + (id++);
                            Node hs = Node.of("HardSigmoid", "hs" + id, new String[]{cur}, new String[]{mid});
                            hs.attrs.put("alpha", Attr.of("alpha", 1f / 6f));
                            g.nodes.add(hs);
                            g.nodes.add(Node.of("Mul", "mul" + id, r.nextBoolean() ? new String[]{cur, mid} : new String[]{mid, cur}, new String[]{nx}));
                        }
                        default -> {
                            String sn = "s" + id, tn = "sh" + id, mid = "t" + (id++);
                            int[] sd = r.nextBoolean() ? new int[]{1} : new int[0];
                            g.initializers.put(sn, Tensor.f32(sd, new float[]{0.5f + r.nextFloat()}));
                            g.initializers.put(tn, Tensor.f32(sd, new float[]{r.nextFloat() - 0.5f}));
                            g.nodes.add(Node.of("Mul", "smul" + id, r.nextBoolean() ? new String[]{cur, sn} : new String[]{sn, cur}, new String[]{mid}));
                            g.nodes.add(Node.of("Add", "sadd" + id, r.nextBoolean() ? new String[]{mid, tn} : new String[]{tn, mid}, new String[]{nx}));
                        }
                    }
                    cur = nx;
                }
                // sometimes a residual-style second consumer of the conv chain
                c = m;
            }
            g.outputs.add(new Sig(cur, 1, null));
            gs[v] = g;
        }
        float[] x = rand(Tensor.numel(shape));
        try {
            Tensor raw = run(gs[0], Map.of("x", Tensor.f32(shape, x)), 0, false);
            StringBuilder desc = new StringBuilder("fusion ");
            for (Node nd : gs[0].nodes) desc.append(nd.op).append(' ');
            for (int mode = 0; mode < 2; mode++) {
                Graph copy = mode == 0 ? gs[1] : gs[1];
                Tensor opt = run(copy, Map.of("x", Tensor.f32(shape, x)), mode, true);
                close(raw.f.length == Tensor.numel(raw.dims) ? raw.f : Arrays.copyOf(raw.f, Tensor.numel(raw.dims)), opt, raw.dims, desc + "mode=" + mode);
            }
        } catch (RuntimeException e) {
            fail("fusion EXC " + e, "fusion-exc");
        }
    }

    static float[] fill(Random r, int n, float lo, float hi) {
        float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = lo + r.nextFloat() * (hi - lo);
        return f;
    }
}
