package io.github.kamilkrzywanski.ocr.runtime;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.util.Map;

final class Ops {
    private Ops() {
    }

    static void exec(Node node, Map<String, Tensor> env, Run run) {
        Tensor[] out = switch (node.op) {
            case "Identity", "Dropout" -> new Tensor[]{ShapeOps.identity(need(env, node, 0), run)};
            case "Add" -> new Tensor[]{ElementOps.binary(need(env, node, 0), need(env, node, 1), 0, run)};
            case "Sub" -> new Tensor[]{ElementOps.binary(need(env, node, 0), need(env, node, 1), 1, run)};
            case "Mul" -> new Tensor[]{ElementOps.binary(need(env, node, 0), need(env, node, 1), 2, run)};
            case "Div" -> new Tensor[]{ElementOps.binary(need(env, node, 0), need(env, node, 1), 3, run)};
            case "Pow" -> new Tensor[]{ElementOps.pow(need(env, node, 0), need(env, node, 1), run)};
            case "Sqrt" -> new Tensor[]{ElementOps.sqrt(need(env, node, 0), run)};
            case "Erf" -> new Tensor[]{ElementOps.erf(need(env, node, 0), run)};
            case "Relu" -> new Tensor[]{ElementOps.unary(need(env, node, 0), "Relu", 0f, 0f, run)};
            case "Sigmoid" -> new Tensor[]{ElementOps.unary(need(env, node, 0), "Sigmoid", 0f, 0f, run)};
            case "HardSigmoid" -> new Tensor[]{ElementOps.unary(need(env, node, 0), "HardSigmoid",
                    node.flt("alpha", 0.2f), node.flt("beta", 0.5f), run)};
            case "HardSwish" -> new Tensor[]{ElementOps.unary(need(env, node, 0), "HardSwish",
                    Optimizer.HSWISH_ALPHA, Optimizer.HSWISH_BETA, run)};
            case "Conv" -> new Tensor[]{ConvOps.conv(node, need(env, node, 0), need(env, node, 1), optional(env, node, 2), run)};
            case "ConvTranspose" -> new Tensor[]{ConvOps.convTranspose(node, need(env, node, 0), need(env, node, 1), optional(env, node, 2), run)};
            case "BatchNormalization" -> new Tensor[]{NnOps.batchNorm(
                    need(env, node, 0), need(env, node, 1), need(env, node, 2), need(env, node, 3), need(env, node, 4),
                    node.flt("epsilon", 1e-5f), run)};
            case "GlobalAveragePool" -> new Tensor[]{NnOps.globalAveragePool(need(env, node, 0), run)};
            case "AveragePool" -> new Tensor[]{averagePool(node, need(env, node, 0), run)};
            case "MaxPool" -> new Tensor[]{maxPool(node, need(env, node, 0), run)};
            case "Resize" -> new Tensor[]{resize(node, env, run)};
            case "Concat" -> new Tensor[]{concat(node, env, run)};
            case "MatMul" -> new Tensor[]{NnOps.matmul(need(env, node, 0), need(env, node, 1), run)};
            case "Softmax" -> new Tensor[]{softmax(node, need(env, node, 0), run)};
            case "ReduceMean" -> new Tensor[]{reduceMean(node, env, run)};
            case "Squeeze" -> new Tensor[]{ShapeOps.squeeze(need(env, node, 0), axes(node, env, 1), run)};
            case "Unsqueeze" -> new Tensor[]{ShapeOps.unsqueeze(need(env, node, 0), requireAxes(node, env), run)};
            case "Transpose" -> new Tensor[]{ShapeOps.transpose(need(env, node, 0), perm(node), run)};
            case "Slice" -> new Tensor[]{slice(node, env, run)};
            case "Reshape" -> new Tensor[]{ShapeOps.reshape(need(env, node, 0), need(env, node, 1), (int) node.lng("allowzero", 0), run)};
            case "Shape" -> new Tensor[]{ShapeOps.shape(need(env, node, 0), run)};
            case "Flatten" -> new Tensor[]{ShapeOps.flatten(need(env, node, 0), (int) node.lng("axis", 1), run)};
            case "Cast" -> new Tensor[]{ShapeOps.cast(need(env, node, 0), (int) node.lng("to", 1), run)};
            case "Constant" -> new Tensor[]{constant(node, run)};
            default -> throw new OcrException("Unsupported op: " + node.op);
        };
        if (out.length < node.outputs.length) {
            throw new OcrException("op produced " + out.length + " outputs, node has " + node.outputs.length);
        }
        for (int i = 0; i < node.outputs.length; i++) {
            if (!node.outputs[i].isEmpty()) {
                env.put(node.outputs[i], out[i]);
            }
        }
    }

    private static Tensor need(Map<String, Tensor> env, Node node, int index) {
        if (index >= node.inputs.length || node.inputs[index].isEmpty()) {
            throw new OcrException("missing input " + index);
        }
        Tensor t = env.get(node.inputs[index]);
        if (t == null) {
            throw new OcrException("unknown tensor " + node.inputs[index]);
        }
        return t;
    }

    private static Tensor optional(Map<String, Tensor> env, Node node, int index) {
        if (index >= node.inputs.length || node.inputs[index] == null || node.inputs[index].isEmpty()) {
            return null;
        }
        return env.get(node.inputs[index]);
    }

    private static Tensor averagePool(Node node, Tensor x, Run run) {
        long[] k = node.longs("kernel_shape", null);
        if (k == null || k.length < 2) {
            throw new OcrException("AveragePool kernel_shape");
        }
        long[] pads = node.longs("pads", new long[]{0, 0, 0, 0});
        int padT = pads.length > 0 ? (int) pads[0] : 0;
        int padL = pads.length > 1 ? (int) pads[1] : 0;
        int padB = pads.length > 2 ? (int) pads[2] : padT;
        int padR = pads.length > 3 ? (int) pads[3] : padL;
        return NnOps.averagePool(x, (int) k[0], (int) k[1],
                spatial(node, "strides", 0, 1), spatial(node, "strides", 1, 1),
                padT, padL, padB, padR,
                node.lng("ceil_mode", 0) != 0,
                node.lng("count_include_pad", 0) != 0,
                run);
    }

    private static Tensor maxPool(Node node, Tensor x, Run run) {
        long[] k = node.longs("kernel_shape", null);
        if (k == null || k.length < 2 || x.rank() != 4) {
            throw new OcrException("MaxPool kernel_shape");
        }
        int kH = (int) k[0];
        int kW = (int) k[1];
        int sH = spatial(node, "strides", 0, 1);
        int sW = spatial(node, "strides", 1, 1);
        int dH = spatial(node, "dilations", 0, 1);
        int dW = spatial(node, "dilations", 1, 1);
        int[] pads = poolPads(node, x.dims[2], x.dims[3], kH, kW, sH, sW, dH, dW);
        return NnOps.maxPool(x, kH, kW, sH, sW, dH, dW, pads[0], pads[1], pads[2], pads[3],
                node.lng("ceil_mode", 0) != 0, run);
    }

    /** Explicit pads, or SAME_UPPER / SAME_LOWER. SAME puts the extra pixel on the end or the start. */
    private static int[] poolPads(Node node, int inH, int inW, int kH, int kW, int sH, int sW, int dH, int dW) {
        String auto = node.str("auto_pad", "NOTSET");
        if ("SAME_UPPER".equals(auto) || "SAME_LOWER".equals(auto)) {
            boolean end = "SAME_UPPER".equals(auto);
            int[] h = samePad(inH, kH, sH, dH, end);
            int[] w = samePad(inW, kW, sW, dW, end);
            return new int[]{h[0], w[0], h[1], w[1]};
        }
        if (!"NOTSET".equals(auto) && !"VALID".equals(auto)) {
            throw new OcrException("MaxPool auto_pad " + auto);
        }
        long[] pads = "VALID".equals(auto) ? new long[]{0, 0, 0, 0} : node.longs("pads", new long[]{0, 0, 0, 0});
        int padT = pads.length > 0 ? (int) pads[0] : 0;
        int padL = pads.length > 1 ? (int) pads[1] : 0;
        int padB = pads.length > 2 ? (int) pads[2] : padT;
        int padR = pads.length > 3 ? (int) pads[3] : padL;
        return new int[]{padT, padL, padB, padR};
    }

    private static int[] samePad(int in, int kernel, int stride, int dilation, boolean extraAtEnd) {
        int effective = dilation * (kernel - 1) + 1;
        int out = (int) Math.ceil(in / (double) stride);
        int total = (out - 1) * stride + effective - in;
        if (total < 0) {
            total = 0;
        }
        int begin = extraAtEnd ? total / 2 : total - total / 2;
        return new int[]{begin, total - begin};
    }

    private static int spatial(Node node, String key, int index, int def) {
        long[] v = node.longs(key, null);
        if (v == null || v.length == 0) {
            return def;
        }
        if (v.length == 1) {
            return (int) v[0];
        }
        return (int) v[index];
    }

    private static Tensor resize(Node node, Map<String, Tensor> env, Run run) {
        Tensor x = need(env, node, 0);
        Tensor scales = optional(env, node, 2);
        Tensor sizes = optional(env, node, 3);
        if (scales != null && scales.numel() == 0) {
            scales = null;
        }
        if (sizes != null && sizes.numel() == 0) {
            sizes = null;
        }
        String mode = node.str("mode", "nearest");
        String coord = node.str("coordinate_transformation_mode", "half_pixel");
        String nearest = node.str("nearest_mode", "round_prefer_floor");
        return NnOps.resize(x, scales, sizes, mode, coord, nearest, run);
    }

    private static Tensor concat(Node node, Map<String, Tensor> env, Run run) {
        Tensor[] parts = new Tensor[node.inputs.length];
        int n = 0;
        for (String name : node.inputs) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            Tensor t = env.get(name);
            if (t == null) {
                throw new OcrException("unknown tensor " + name);
            }
            parts[n++] = t;
        }
        Tensor[] used = new Tensor[n];
        System.arraycopy(parts, 0, used, 0, n);
        return ShapeOps.concat(used, (int) node.lng("axis", 0), run);
    }

    private static Tensor softmax(Node node, Tensor x, Run run) {
        boolean old = run.opset <= 12;
        int axis = (int) node.lng("axis", old ? 1 : -1);
        return ElementOps.softmax(x, axis, old, run);
    }

    private static Tensor reduceMean(Node node, Map<String, Tensor> env, Run run) {
        Tensor x = need(env, node, 0);
        long[] axes = axes(node, env, 1);
        boolean keep = node.lng("keepdims", 1) != 0;
        boolean noop = node.lng("noop_with_empty_axes", 0) != 0;
        int[] as = null;
        if (axes != null) {
            as = new int[axes.length];
            for (int i = 0; i < axes.length; i++) {
                as[i] = (int) axes[i];
            }
        }
        return ElementOps.reduceMean(x, as, keep, noop, run);
    }

    private static long[] axes(Node node, Map<String, Tensor> env, int inputIndex) {
        long[] attr = node.longs("axes", null);
        if (attr != null) {
            return attr;
        }
        Tensor t = optional(env, node, inputIndex);
        if (t == null) {
            return null;
        }
        return t.i;
    }

    private static long[] requireAxes(Node node, Map<String, Tensor> env) {
        long[] axes = axes(node, env, 1);
        if (axes == null) {
            throw new OcrException("missing axes");
        }
        return axes;
    }

    private static int[] perm(Node node) {
        long[] raw = node.longs("perm", null);
        if (raw == null) {
            return null;
        }
        int[] perm = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            perm[i] = (int) raw[i];
        }
        return perm;
    }

    private static Tensor slice(Node node, Map<String, Tensor> env, Run run) {
        Tensor data = need(env, node, 0);
        if (node.inputs.length >= 3 && !node.inputs[1].isEmpty()) {
            long[] starts = need(env, node, 1).i;
            long[] ends = need(env, node, 2).i;
            long[] axes = node.inputs.length >= 4 ? optionalLongs(env, node, 3) : null;
            long[] steps = node.inputs.length >= 5 ? optionalLongs(env, node, 4) : null;
            return ShapeOps.slice(data, starts, ends, axes, steps, run);
        }
        long[] starts = node.longs("starts", null);
        long[] ends = node.longs("ends", null);
        if (starts == null || ends == null) {
            throw new OcrException("Slice is missing starts/ends");
        }
        return ShapeOps.slice(data, starts, ends, node.longs("axes", null), node.longs("steps", null), run);
    }

    private static long[] optionalLongs(Map<String, Tensor> env, Node node, int index) {
        Tensor t = optional(env, node, index);
        return t == null ? null : t.i;
    }

    private static Tensor constant(Node node, Run run) {
        Attr value = node.attrs.get("value");
        if (value == null || value.tensor == null) {
            throw new OcrException("Constant without value");
        }
        return value.tensor.copy();
    }
}
