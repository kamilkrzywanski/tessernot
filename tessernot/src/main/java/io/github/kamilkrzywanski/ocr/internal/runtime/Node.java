package io.github.kamilkrzywanski.ocr.internal.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Node {
    public final String op;
    public final String name;
    public String[] inputs;
    public String[] outputs;
    public final Map<String, Attr> attrs;
    /**
     * Fused epilogue: Relu, Sigmoid, HardSigmoid, HardSwish. Null when absent.
     * Attrs {@code out_scale} and {@code out_shift} then apply {@code y * scale + shift}.
     */
    public String activation;
    public float alpha = 0.2f;
    public float beta = 0.5f;

    public Node(String op, String name, String[] inputs, String[] outputs, Map<String, Attr> attrs) {
        this.op = op;
        this.name = name == null ? "" : name;
        this.inputs = inputs.clone();
        this.outputs = outputs.clone();
        this.attrs = attrs == null ? new LinkedHashMap<>() : attrs;
    }

    public static Node of(String op, String name, String[] inputs, String[] outputs) {
        return new Node(op, name, inputs, outputs, new LinkedHashMap<>());
    }

    public long lng(String key, long def) {
        Attr a = attrs.get(key);
        if (a == null) {
            return def;
        }
        if (a.kind == Attr.Kind.INT) {
            return a.i;
        }
        if (a.kind == Attr.Kind.INTS && a.ints.length > 0) {
            return a.ints[0];
        }
        return def;
    }

    public float flt(String key, float def) {
        Attr a = attrs.get(key);
        if (a == null) {
            return def;
        }
        if (a.kind == Attr.Kind.FLOAT) {
            return a.f;
        }
        if (a.kind == Attr.Kind.INT) {
            return a.i;
        }
        return def;
    }

    public String str(String key, String def) {
        Attr a = attrs.get(key);
        if (a == null || a.s == null) {
            return def;
        }
        return a.s;
    }

    public long[] longs(String key, long[] def) {
        Attr a = attrs.get(key);
        if (a == null) {
            return def;
        }
        long[] v = a.asLongs();
        return v == null ? def : v;
    }
}
