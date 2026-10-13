package io.github.kamilkrzywanski.ocr.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identity elimination, Conv-BN fold, channel-bias Add fold, unary / decomposed HardSwish epilogue,
 * and scalar {@code y * scale + shift} folded into the conv that produced {@code y}.
 * Fused weights are cloned under a new name so shared initializers are not rewritten twice.
 */
final class Optimizer {
    static final float HSWISH_ALPHA = 1f / 6f;
    static final float HSWISH_BETA = 0.5f;

    private int seq;

    private Optimizer() {
    }

    static void optimize(Graph g) {
        new Optimizer().run(g);
    }

    private void run(Graph g) {
        eliminateIdentity(g);
        for (int i = 0; i < 8; i++) {
            boolean a = fuseBatchNorm(g);
            boolean b = fuseBias(g);
            boolean c = fuseUnary(g);
            boolean d = foldOutputAffine(g);
            boolean e = fuseUnary(g);
            boolean f = foldEpilogueAffine(g);
            if (!a && !b && !c && !d && !e && !f) {
                break;
            }
        }
        dropUnusedInitializers(g);
    }

    private static void eliminateIdentity(Graph g) {
        Map<String, String> alias = new HashMap<>();
        for (Node n : g.nodes) {
            if ("Identity".equals(n.op) && n.inputs.length >= 1 && n.outputs.length == 1 && !n.inputs[0].isEmpty()) {
                alias.put(n.outputs[0], n.inputs[0]);
            }
        }
        List<Node> kept = new ArrayList<>();
        for (Node n : g.nodes) {
            if ("Identity".equals(n.op)) {
                continue;
            }
            for (int i = 0; i < n.inputs.length; i++) {
                n.inputs[i] = resolve(alias, n.inputs[i]);
            }
            kept.add(n);
        }
        g.nodes.clear();
        g.nodes.addAll(kept);
        for (Sig s : g.outputs) {
            String root = resolve(alias, s.name);
            if (!root.equals(s.name)) {
                g.redirects.put(s.name, root);
            }
        }
    }

    private static String resolve(Map<String, String> alias, String name) {
        String n = name;
        for (int guard = 0; guard < 10_000 && alias.containsKey(n); guard++) {
            n = alias.get(n);
        }
        return n;
    }

    private boolean fuseBatchNorm(Graph g) {
        Map<String, List<Node>> cons = consumers(g);
        List<Node> remove = new ArrayList<>();
        boolean changed = false;
        for (Node conv : g.nodes) {
            if (!"Conv".equals(conv.op) || conv.outputs.length != 1 || conv.activation != null) {
                continue;
            }
            List<Node> cs = cons.get(conv.outputs[0]);
            if (cs == null || cs.size() != 1 || !"BatchNormalization".equals(cs.get(0).op)) {
                continue;
            }
            Node bn = cs.get(0);
            if (bn.inputs.length < 5) {
                continue;
            }
            Tensor scale = g.initializers.get(bn.inputs[1]);
            Tensor shift = g.initializers.get(bn.inputs[2]);
            Tensor mean = g.initializers.get(bn.inputs[3]);
            Tensor var = g.initializers.get(bn.inputs[4]);
            Tensor w = g.initializers.get(conv.inputs[1]);
            if (scale == null || shift == null || mean == null || var == null || w == null) {
                continue;
            }
            if (w.f == null || scale.f == null || w.dims.length != 4) {
                continue;
            }
            int m = w.dims[0];
            if (scale.numel() != m || shift.numel() != m || mean.numel() != m || var.numel() != m) {
                continue;
            }
            float[] oldBias = null;
            if (conv.inputs.length >= 3 && !conv.inputs[2].isEmpty()) {
                Tensor b = g.initializers.get(conv.inputs[2]);
                if (b == null || b.f == null || b.numel() != m) {
                    continue;
                }
                oldBias = b.f;
            }
            float eps = bn.flt("epsilon", 1e-5f);
            int per = w.f.length / m;
            float[] nw = new float[w.f.length];
            float[] nb = new float[m];
            for (int oc = 0; oc < m; oc++) {
                double s = scale.f[oc] / Math.sqrt((double) var.f[oc] + eps);
                for (int k = 0; k < per; k++) {
                    nw[oc * per + k] = (float) (w.f[oc * per + k] * s);
                }
                float b0 = oldBias == null ? 0f : oldBias[oc];
                nb[oc] = (float) ((b0 - mean.f[oc]) * s + shift.f[oc]);
            }
            String wn = conv.inputs[1] + "#bn" + (seq++);
            String bnName = wn + "b";
            g.initializers.put(wn, Tensor.f32(w.dims, nw));
            g.initializers.put(bnName, Tensor.f32(new int[]{m}, nb));
            conv.inputs = new String[]{conv.inputs[0], wn, bnName};
            conv.outputs = bn.outputs.clone();
            remove.add(bn);
            changed = true;
        }
        g.nodes.removeAll(remove);
        return changed;
    }

    private boolean fuseBias(Graph g) {
        Map<String, List<Node>> cons = consumers(g);
        List<Node> remove = new ArrayList<>();
        boolean changed = false;
        for (Node conv : g.nodes) {
            if (!isConvLike(conv.op) || conv.outputs.length != 1 || conv.activation != null) {
                continue;
            }
            List<Node> cs = cons.get(conv.outputs[0]);
            if (cs == null || cs.size() != 1 || !"Add".equals(cs.get(0).op)) {
                continue;
            }
            Node add = cs.get(0);
            if (add.inputs.length != 2) {
                continue;
            }
            String other = add.inputs[0].equals(conv.outputs[0]) ? add.inputs[1] : add.inputs[0];
            if (other.equals(conv.outputs[0])) {
                continue;
            }
            Tensor bias = g.initializers.get(other);
            Tensor w = g.initializers.get(conv.inputs[1]);
            if (bias == null || bias.f == null || w == null || w.f == null) {
                continue;
            }
            int m = outChannels(conv, w);
            if (m <= 0 || bias.numel() != m || !channelShape(bias.dims, m)) {
                continue;
            }
            float[] nb = new float[m];
            System.arraycopy(bias.f, 0, nb, 0, m);
            if (conv.inputs.length >= 3 && !conv.inputs[2].isEmpty()) {
                Tensor existing = g.initializers.get(conv.inputs[2]);
                if (existing == null || existing.f == null || existing.numel() != m) {
                    continue;
                }
                for (int oc = 0; oc < m; oc++) {
                    nb[oc] += existing.f[oc];
                }
            }
            String name = other + "#bias" + (seq++);
            g.initializers.put(name, Tensor.f32(new int[]{m}, nb));
            conv.inputs = new String[]{conv.inputs[0], conv.inputs[1], name};
            conv.outputs = add.outputs.clone();
            remove.add(add);
            changed = true;
        }
        g.nodes.removeAll(remove);
        return changed;
    }

    private static boolean fuseUnary(Graph g) {
        Map<String, List<Node>> cons = consumers(g);
        List<Node> remove = new ArrayList<>();
        boolean changed = false;
        for (Node conv : g.nodes) {
            if (!isConvLike(conv.op) || conv.outputs.length != 1 || conv.activation != null) {
                continue;
            }
            String out = conv.outputs[0];
            List<Node> cs = cons.get(out);
            if (cs == null) {
                continue;
            }
            if (cs.size() == 1) {
                Node u = cs.get(0);
                String act = switch (u.op) {
                    case "Relu", "Sigmoid", "HardSigmoid", "HardSwish" -> u.op;
                    default -> null;
                };
                if (act == null || u.inputs.length < 1 || !u.inputs[0].equals(out)) {
                    continue;
                }
                conv.activation = act;
                if ("HardSigmoid".equals(act)) {
                    conv.alpha = u.flt("alpha", 0.2f);
                    conv.beta = u.flt("beta", 0.5f);
                } else if ("HardSwish".equals(act)) {
                    conv.alpha = HSWISH_ALPHA;
                    conv.beta = HSWISH_BETA;
                }
                conv.outputs = u.outputs.clone();
                remove.add(u);
                changed = true;
            } else if (cs.size() == 2) {
                Node hs = null;
                Node mul = null;
                for (Node n : cs) {
                    if ("HardSigmoid".equals(n.op)) {
                        hs = n;
                    } else if ("Mul".equals(n.op)) {
                        mul = n;
                    }
                }
                if (hs == null || mul == null || hs.outputs.length != 1 || mul.inputs.length != 2) {
                    continue;
                }
                List<Node> hsCons = cons.get(hs.outputs[0]);
                if (hsCons == null || hsCons.size() != 1 || hsCons.get(0) != mul) {
                    continue;
                }
                boolean mulInputs = (mul.inputs[0].equals(out) && mul.inputs[1].equals(hs.outputs[0]))
                        || (mul.inputs[1].equals(out) && mul.inputs[0].equals(hs.outputs[0]));
                if (!mulInputs || hs.inputs.length < 1 || !hs.inputs[0].equals(out)) {
                    continue;
                }
                conv.activation = "HardSwish";
                conv.alpha = hs.flt("alpha", 0.2f);
                conv.beta = hs.flt("beta", 0.5f);
                conv.outputs = mul.outputs.clone();
                remove.add(hs);
                remove.add(mul);
                changed = true;
            }
        }
        g.nodes.removeAll(remove);
        return changed;
    }

    /**
     * Conv with no epilogue, followed by a scalar multiply and a scalar add.
     * {@code (W x + b) * s + t} becomes weights {@code W * s} and bias {@code b * s + t}.
     * Padding stays correct because the affine runs on the conv output, not on its input.
     */
    private boolean foldOutputAffine(Graph g) {
        Map<String, List<Node>> cons = consumers(g);
        List<Node> remove = new ArrayList<>();
        boolean changed = false;
        for (Node conv : g.nodes) {
            // ConvTranspose stores weights per input channel, so a per-output scale is not a contiguous block.
            if (!"Conv".equals(conv.op) || conv.outputs.length != 1 || conv.activation != null) {
                continue;
            }
            AffineMatch match = scalarAffine(g, cons, conv.outputs[0]);
            if (match == null || exposed(g, match.mul.outputs[0])) {
                continue;
            }
            if (!scaleConvWeights(g, conv, match.scale, match.shift)) {
                continue;
            }
            conv.outputs = match.add.outputs.clone();
            remove.add(match.mul);
            remove.add(match.add);
            changed = true;
        }
        g.nodes.removeAll(remove);
        return changed;
    }

    /**
     * Scalar affine after an activation epilogue. It stays on that epilogue
     * ({@code scale * act(y) + shift}) so the extra maps are not written.
     * A shift on the input of a padded conv is not a bias, so this does not rewrite the next kernel.
     */
    private boolean foldEpilogueAffine(Graph g) {
        Map<String, List<Node>> cons = consumers(g);
        List<Node> remove = new ArrayList<>();
        boolean changed = false;
        for (Node conv : g.nodes) {
            if (!isConvLike(conv.op) || conv.outputs.length != 1 || conv.activation == null) {
                continue;
            }
            AffineMatch match = scalarAffine(g, cons, conv.outputs[0]);
            if (match == null || exposed(g, match.mul.outputs[0])) {
                continue;
            }
            float s0 = conv.flt("out_scale", 1f);
            float t0 = conv.flt("out_shift", 0f);
            conv.attrs.put("out_scale", Attr.of("out_scale", s0 * match.scale));
            conv.attrs.put("out_shift", Attr.of("out_shift", t0 * match.scale + match.shift));
            conv.outputs = match.add.outputs.clone();
            remove.add(match.mul);
            remove.add(match.add);
            changed = true;
        }
        g.nodes.removeAll(remove);
        return changed;
    }

    private boolean scaleConvWeights(Graph g, Node conv, float scale, float shift) {
        if (conv.inputs.length < 2 || conv.inputs[1].isEmpty()) {
            return false;
        }
        Tensor w = g.initializers.get(conv.inputs[1]);
        if (w == null || w.f == null || w.dims.length != 4) {
            return false;
        }
        int m = outChannels(conv, w);
        if (m <= 0 || w.f.length % m != 0) {
            return false;
        }
        float[] old = null;
        if (conv.inputs.length >= 3 && !conv.inputs[2].isEmpty()) {
            Tensor b = g.initializers.get(conv.inputs[2]);
            if (b == null || b.f == null || b.numel() != m) {
                return false;
            }
            old = b.f;
        }
        int per = w.f.length / m;
        float[] nw = new float[w.f.length];
        float[] nb = new float[m];
        for (int oc = 0; oc < m; oc++) {
            for (int k = 0; k < per; k++) {
                nw[oc * per + k] = w.f[oc * per + k] * scale;
            }
            float b0 = old == null ? 0f : old[oc];
            nb[oc] = b0 * scale + shift;
        }
        String wn = conv.inputs[1] + "#aff" + (seq++);
        String bn = wn + "b";
        g.initializers.put(wn, Tensor.f32(w.dims, nw));
        g.initializers.put(bn, Tensor.f32(new int[]{m}, nb));
        conv.inputs = new String[]{conv.inputs[0], wn, bn};
        return true;
    }

    /** Scalar multiply then scalar add, each the sole consumer of the previous tensor. */
    private static AffineMatch scalarAffine(Graph g, Map<String, List<Node>> cons, String from) {
        List<Node> cs = cons.get(from);
        if (cs == null || cs.size() != 1 || !"Mul".equals(cs.get(0).op)) {
            return null;
        }
        Node mul = cs.get(0);
        if (mul.inputs.length != 2 || mul.outputs.length != 1) {
            return null;
        }
        String scaleName = mul.inputs[0].equals(from) ? mul.inputs[1] : mul.inputs[0];
        if (scaleName.equals(from)) {
            return null;
        }
        Float scale = scalarOf(g, scaleName);
        if (scale == null) {
            return null;
        }
        List<Node> next = cons.get(mul.outputs[0]);
        if (next == null || next.size() != 1 || !"Add".equals(next.get(0).op)) {
            return null;
        }
        Node add = next.get(0);
        if (add.inputs.length != 2 || add.outputs.length != 1) {
            return null;
        }
        String shiftName = add.inputs[0].equals(mul.outputs[0]) ? add.inputs[1] : add.inputs[0];
        if (shiftName.equals(mul.outputs[0])) {
            return null;
        }
        Float shift = scalarOf(g, shiftName);
        if (shift == null) {
            return null;
        }
        return new AffineMatch(mul, add, scale, shift);
    }

    private static Float scalarOf(Graph g, String name) {
        Tensor t = g.initializers.get(name);
        if (t == null || t.f == null || t.numel() != 1) {
            return null;
        }
        return t.f[0];
    }

    private static boolean exposed(Graph g, String name) {
        for (Sig s : g.outputs) {
            if (name.equals(s.name)) {
                return true;
            }
        }
        return g.redirects.containsValue(name);
    }

    private static final class AffineMatch {
        final Node mul;
        final Node add;
        final float scale;
        final float shift;

        AffineMatch(Node mul, Node add, float scale, float shift) {
            this.mul = mul;
            this.add = add;
            this.scale = scale;
            this.shift = shift;
        }
    }

    private static boolean isConvLike(String op) {
        return "Conv".equals(op) || "ConvTranspose".equals(op);
    }

    private static int outChannels(Node conv, Tensor w) {
        if ("Conv".equals(conv.op)) {
            return w.dims.length > 0 ? w.dims[0] : -1;
        }
        if (w.dims.length < 2) {
            return -1;
        }
        int group = (int) conv.lng("group", 1);
        return w.dims[1] * group;
    }

    private static boolean channelShape(int[] dims, int m) {
        int nonOne = 0;
        for (int d : dims) {
            if (d == 1) {
                continue;
            }
            if (d != m) {
                return false;
            }
            nonOne++;
        }
        return nonOne <= 1;
    }

    private static Map<String, List<Node>> consumers(Graph g) {
        Map<String, List<Node>> cons = new HashMap<>();
        for (Node n : g.nodes) {
            Set<String> seen = new HashSet<>();
            for (String in : n.inputs) {
                if (in == null || in.isEmpty() || !seen.add(in)) {
                    continue;
                }
                cons.computeIfAbsent(in, k -> new ArrayList<>()).add(n);
            }
        }
        return cons;
    }

    private static void dropUnusedInitializers(Graph g) {
        Set<String> used = new HashSet<>();
        for (Node n : g.nodes) {
            for (String in : n.inputs) {
                if (in != null && !in.isEmpty()) {
                    used.add(in);
                }
            }
        }
        used.addAll(g.redirects.values());
        g.initializers.keySet().retainAll(used);
    }
}
