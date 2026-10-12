package org.krzywanski.ocr.runtime;

import org.krzywanski.ocr.OcrException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/** Little-endian graph snapshot. Magic {@code JIR1}, version 1. Optimized before save; load does not optimize again. */
public final class Jir {
    private Jir() {
    }

    public static void save(Graph graph, Path path) {
        try {
            Files.write(path, write(graph));
        } catch (IOException e) {
            throw new OcrException("cannot write " + path + ": " + e.getMessage(), e);
        }
    }

    public static Graph load(Path path) {
        try {
            return read(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new OcrException("cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    public static byte[] write(Graph g) {
        Map<String, Integer> ids = new LinkedHashMap<>();
        ids.put("", 0);
        java.util.function.Function<String, Integer> intern = s -> {
            String key = s == null ? "" : s;
            Integer have = ids.get(key);
            if (have != null) {
                return have;
            }
            int id = ids.size();
            ids.put(key, id);
            return id;
        };
        for (Sig s : g.inputs) intern.apply(s.name);
        for (Sig s : g.outputs) intern.apply(s.name);
        for (String name : g.initializers.keySet()) intern.apply(name);
        for (var e : g.redirects.entrySet()) {
            intern.apply(e.getKey());
            intern.apply(e.getValue());
        }
        for (Node n : g.nodes) {
            intern.apply(n.op);
            intern.apply(n.name);
            intern.apply(n.activation);
            for (String in : n.inputs) intern.apply(in);
            for (String out : n.outputs) intern.apply(out);
            for (Attr a : n.attrs.values()) {
                intern.apply(a.name);
                if (a.kind == Attr.Kind.STRING) intern.apply(a.s);
            }
        }
        String[] table = new String[ids.size()];
        for (var e : ids.entrySet()) {
            table[e.getValue()] = e.getKey();
        }
        Buf buf = new Buf();
        buf.bytes(new byte[]{'J', 'I', 'R', '1'});
        buf.i32(1);
        buf.i32(g.opset);
        buf.i32(0);
        buf.i32(table.length);
        for (String s : table) {
            byte[] raw = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.i32(raw.length);
            buf.bytes(raw);
        }
        buf.i32(g.redirects.size());
        for (var e : g.redirects.entrySet()) {
            buf.i32(ids.get(e.getKey()));
            buf.i32(ids.get(e.getValue()));
        }
        writeSigs(buf, g.inputs, ids);
        writeSigs(buf, g.outputs, ids);
        buf.i32(g.initializers.size());
        for (var e : g.initializers.entrySet()) {
            buf.i32(ids.get(e.getKey()));
            writeTensor(buf, e.getValue());
        }
        buf.i32(g.nodes.size());
        for (Node n : g.nodes) {
            buf.i32(ids.get(n.op));
            buf.i32(ids.get(n.name));
            buf.i32(n.inputs.length);
            for (String in : n.inputs) buf.i32(ids.get(in));
            buf.i32(n.outputs.length);
            for (String out : n.outputs) buf.i32(ids.get(out));
            buf.i32(n.activation == null ? -1 : ids.get(n.activation));
            buf.f32(n.alpha);
            buf.f32(n.beta);
            buf.i32(n.attrs.size());
            for (Attr a : n.attrs.values()) {
                buf.i32(ids.get(a.name));
                switch (a.kind) {
                    case INT -> {
                        buf.i32(0);
                        buf.i64(a.i);
                    }
                    case FLOAT -> {
                        buf.i32(1);
                        buf.f32(a.f);
                    }
                    case STRING -> {
                        buf.i32(2);
                        buf.i32(ids.get(a.s == null ? "" : a.s));
                    }
                    case INTS -> {
                        buf.i32(3);
                        buf.i32(a.ints.length);
                        for (long v : a.ints) buf.i64(v);
                    }
                    case FLOATS -> {
                        buf.i32(4);
                        buf.i32(a.floats.length);
                        for (float v : a.floats) buf.f32(v);
                    }
                    case TENSOR -> {
                        buf.i32(5);
                        writeTensor(buf, a.tensor);
                    }
                }
            }
        }
        byte[] body = buf.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body);
        Buf all = new Buf();
        all.bytes(body);
        all.i32((int) crc.getValue());
        return all.toByteArray();
    }

    public static Graph read(byte[] data) {
        if (data.length < 8 || data[0] != 'J' || data[1] != 'I' || data[2] != 'R' || data[3] != '1') {
            throw new OcrException("not a JIR file");
        }
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - 4);
        int stored = intLE(data, data.length - 4);
        if ((int) crc.getValue() != stored) {
            throw new OcrException("JIR checksum mismatch");
        }
        In in = new In(data, 0, data.length - 4);
        in.skip(4);
        int version = in.i32();
        if (version != 1) {
            throw new OcrException("unsupported JIR version " + version);
        }
        Graph g = new Graph();
        g.opset = in.i32();
        in.i32();
        int nStrings = count(in.i32());
        String[] table = new String[nStrings];
        for (int i = 0; i < nStrings; i++) {
            int len = count(in.i32());
            table[i] = new String(in.bytes(len), java.nio.charset.StandardCharsets.UTF_8);
        }
        int nRedir = count(in.i32());
        for (int i = 0; i < nRedir; i++) {
            g.redirects.put(in.str(table), in.str(table));
        }
        readSigs(in, table, g.inputs);
        readSigs(in, table, g.outputs);
        int nInit = count(in.i32());
        for (int i = 0; i < nInit; i++) {
            String name = in.str(table);
            g.initializers.put(name, readTensor(in));
        }
        int nNodes = count(in.i32());
        for (int i = 0; i < nNodes; i++) {
            String op = in.str(table);
            String name = in.str(table);
            int nIn = count(in.i32());
            String[] inputs = new String[nIn];
            for (int k = 0; k < nIn; k++) inputs[k] = in.str(table);
            int nOut = count(in.i32());
            String[] outputs = new String[nOut];
            for (int k = 0; k < nOut; k++) outputs[k] = in.str(table);
            int act = in.i32();
            float alpha = in.f32();
            float beta = in.f32();
            int nAttr = count(in.i32());
            Map<String, Attr> attrs = new LinkedHashMap<>();
            for (int a = 0; a < nAttr; a++) {
                String an = in.str(table);
                int kind = in.i32();
                Attr attr = switch (kind) {
                    case 0 -> Attr.of(an, in.i64());
                    case 1 -> Attr.of(an, in.f32());
                    case 2 -> Attr.of(an, in.str(table));
                    case 3 -> {
                        int n = count(in.i32());
                        long[] v = new long[n];
                        for (int k = 0; k < n; k++) v[k] = in.i64();
                        yield Attr.ints(an, v);
                    }
                    case 4 -> {
                        int n = count(in.i32());
                        float[] v = new float[n];
                        for (int k = 0; k < n; k++) v[k] = in.f32();
                        yield Attr.floats(an, v);
                    }
                    case 5 -> Attr.tensor(an, readTensor(in));
                    default -> throw new OcrException("bad JIR attr kind " + kind);
                };
                attrs.put(an, attr);
            }
            Node node = new Node(op, name, inputs, outputs, attrs);
            node.activation = act < 0 ? null : str(table, act);
            node.alpha = alpha;
            node.beta = beta;
            g.nodes.add(node);
        }
        if (in.pos != in.end) {
            throw new OcrException("trailing JIR bytes");
        }
        return g;
    }

    private static void writeSigs(Buf buf, List<Sig> sigs, Map<String, Integer> ids) {
        buf.i32(sigs.size());
        for (Sig s : sigs) {
            buf.i32(ids.get(s.name));
            buf.i32(s.dtype);
            if (s.dims == null) {
                buf.i32(-1);
            } else {
                buf.i32(s.dims.length);
                for (int d : s.dims) buf.i32(d);
            }
        }
    }

    private static void readSigs(In in, String[] table, List<Sig> sigs) {
        int n = count(in.i32());
        for (int i = 0; i < n; i++) {
            String name = in.str(table);
            int dtype = in.i32();
            int rank = in.i32();
            int[] dims = null;
            if (rank >= 0) {
                dims = new int[count(rank)];
                for (int d = 0; d < rank; d++) dims[d] = in.i32();
            }
            sigs.add(new Sig(name, dtype, dims));
        }
    }

    private static void writeTensor(Buf buf, Tensor t) {
        buf.i32(t.dtype == Tensor.F32 ? Tensor.F32 : Tensor.I64);
        buf.i32(t.dims.length);
        for (int d : t.dims) buf.i32(d);
        if (t.dtype == Tensor.F32) {
            buf.i32(t.f.length);
            for (float v : t.f) buf.f32(v);
        } else {
            buf.i32(t.i.length);
            for (long v : t.i) buf.i64(v);
        }
    }

    private static Tensor readTensor(In in) {
        int dtype = in.i32();
        int rank = count(in.i32());
        int[] dims = new int[rank];
        for (int i = 0; i < rank; i++) dims[i] = in.i32();
        int n = count(in.i32());
        if (dtype == Tensor.F32) {
            float[] f = new float[n];
            for (int i = 0; i < n; i++) f[i] = in.f32();
            return Tensor.f32(dims, f);
        }
        long[] v = new long[n];
        for (int i = 0; i < n; i++) v[i] = in.i64();
        return Tensor.i64(dims, v);
    }

    private static int count(int n) {
        if (n < 0) {
            throw new OcrException("bad JIR");
        }
        return n;
    }

    private static String str(String[] table, int id) {
        if (id < 0 || id >= table.length) {
            throw new OcrException("bad JIR string " + id);
        }
        return table[id];
    }

    private static int intLE(byte[] d, int off) {
        return (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8) | ((d[off + 2] & 0xff) << 16) | ((d[off + 3] & 0xff) << 24);
    }

    private static final class Buf {
        private final List<byte[]> parts = new ArrayList<>();
        private int size;

        void bytes(byte[] b) {
            parts.add(b);
            size += b.length;
        }

        void i32(int v) {
            bytes(new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)});
        }

        void i64(long v) {
            byte[] b = new byte[8];
            for (int i = 0; i < 8; i++) {
                b[i] = (byte) (v >>> (8 * i));
            }
            bytes(b);
        }

        void f32(float v) {
            i32(Float.floatToIntBits(v));
        }

        byte[] toByteArray() {
            byte[] out = new byte[size];
            int p = 0;
            for (byte[] part : parts) {
                System.arraycopy(part, 0, out, p, part.length);
                p += part.length;
            }
            return out;
        }
    }

    private static final class In {
        final byte[] data;
        int pos;
        final int end;

        In(byte[] data, int pos, int end) {
            this.data = data;
            this.pos = pos;
            this.end = end;
        }

        void skip(int n) {
            need(n);
            pos += n;
        }

        int i32() {
            need(4);
            int v = intLE(data, pos);
            pos += 4;
            return v;
        }

        String str(String[] table) {
            return Jir.str(table, i32());
        }

        void need(int n) {
            if (n < 0 || pos < 0 || (long) pos + n > end) {
                throw new OcrException(n < 0 ? "bad JIR" : "truncated JIR");
            }
        }

        long i64() {
            long lo = i32() & 0xffffffffL;
            long hi = i32() & 0xffffffffL;
            return lo | (hi << 32);
        }

        float f32() {
            return Float.intBitsToFloat(i32());
        }

        byte[] bytes(int n) {
            need(n);
            byte[] b = new byte[n];
            System.arraycopy(data, pos, b, 0, n);
            pos += n;
            return b;
        }
    }
}
