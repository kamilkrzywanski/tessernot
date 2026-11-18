package io.github.kamilkrzywanski.ocr.internal.runtime;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded ONNX protobuf reader for PP-OCR graphs. Attribute ints are field 8 and floats are field 7.
 * Int64 initializers accept raw_data, little-endian field-7 blobs of length n*8, packed varints, and unpacked varints.
 */
public final class Onnx {
    private Onnx() {
    }

    public static Graph load(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            Path parent = path.getParent() == null ? Path.of(".") : path.getParent();
            return read(data, parent);
        } catch (IOException e) {
            throw new OcrException("cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    public static Graph read(byte[] data, Path baseDir) {
        Proto p = new Proto(data, 0, data.length);
        Graph graph = null;
        int opset = 0;
        while (!p.done()) {
            long tag = p.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            switch (field) {
                case 7 -> graph = parseGraph(p.sub(), baseDir);
                case 8 -> {
                    Proto s = p.sub();
                    String domain = "";
                    long version = 0;
                    while (!s.done()) {
                        long t = s.readVarint();
                        int f = (int) (t >>> 3);
                        int w = (int) (t & 7);
                        if (f == 1 && w == 2) {
                            domain = s.readString();
                        } else if (f == 2 && w == 0) {
                            version = s.readVarint();
                        } else {
                            s.skip(w);
                        }
                    }
                    if (domain.isEmpty() || "ai.onnx".equals(domain)) {
                        // A value outside int range truncates; 2^32 + 9 becomes opset 9.
                        if ((int) version != version) {
                            throw new OcrException("bad opset " + version);
                        }
                        opset = (int) version;
                    }
                }
                default -> p.skip(wire);
            }
        }
        if (graph == null) {
            throw new OcrException("ONNX model has no graph");
        }
        if (opset <= 0) {
            throw new OcrException("ONNX model has no ai.onnx opset");
        }
        graph.opset = opset;
        return graph;
    }

    private static Graph parseGraph(Proto p, Path baseDir) {
        Graph g = new Graph();
        int auto = 0;
        while (!p.done()) {
            long tag = p.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            switch (field) {
                case 1 -> {
                    Node node = parseNode(p.sub());
                    if (node.name.isEmpty()) {
                        g.nodes.add(new Node(node.op, node.op + "#" + (auto++), node.inputs, node.outputs, node.attrs));
                    } else {
                        g.nodes.add(node);
                    }
                }
                case 5 -> {
                    NamedTensor nt = parseTensor(p.sub(), baseDir);
                    g.initializers.put(nt.name, nt.tensor);
                }
                case 11 -> g.inputs.add(parseValueInfo(p.sub()));
                case 12 -> g.outputs.add(parseValueInfo(p.sub()));
                default -> p.skip(wire);
            }
        }
        return g;
    }

    private static Node parseNode(Proto p) {
        List<String> inputs = new ArrayList<>();
        List<String> outputs = new ArrayList<>();
        String name = "";
        String op = "";
        Map<String, Attr> attrs = new LinkedHashMap<>();
        while (!p.done()) {
            long tag = p.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            switch (field) {
                case 1 -> inputs.add(p.readString());
                case 2 -> outputs.add(p.readString());
                case 3 -> name = p.readString();
                case 4 -> op = p.readString();
                case 5 -> {
                    Attr a = parseAttr(p.sub());
                    attrs.put(a.name, a);
                }
                default -> p.skip(wire);
            }
        }
        return new Node(op, name, inputs.toArray(String[]::new), outputs.toArray(String[]::new), attrs);
    }

    private static Attr parseAttr(Proto p) {
        String name = "";
        int type = 0;
        Float fv = null;
        Long iv = null;
        String sv = null;
        Tensor tensor = null;
        List<Float> floats = new ArrayList<>();
        List<Long> ints = new ArrayList<>();
        while (!p.done()) {
            long tag = p.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            switch (field) {
                case 1 -> name = p.readString();
                case 2 -> fv = p.readFloat();
                case 3 -> iv = p.readVarint();
                case 4 -> sv = p.readString();
                case 5 -> tensor = parseTensor(p.sub(), null).tensor;
                case 7 -> readPackedFloats(p, wire, floats);
                case 8 -> readPackedVarints(p, wire, ints);
                case 20 -> type = (int) p.readVarint();
                default -> p.skip(wire);
            }
        }
        if (type == 0) {
            if (!ints.isEmpty()) {
                type = 7;
            } else if (!floats.isEmpty()) {
                type = 6;
            } else if (tensor != null) {
                type = 4;
            } else if (sv != null) {
                type = 3;
            } else if (fv != null) {
                type = 1;
            } else if (iv != null) {
                type = 2;
            }
        }
        return switch (type) {
            case 1 -> Attr.of(name, fv == null ? 0f : fv);
            case 2 -> Attr.of(name, iv == null ? 0L : iv);
            case 3 -> Attr.of(name, sv == null ? "" : sv);
            case 4 -> Attr.tensor(name, tensor);
            case 6 -> Attr.floats(name, toFloats(floats));
            case 7 -> Attr.ints(name, toLongs(ints));
            default -> Attr.of(name, sv == null ? "" : sv);
        };
    }

    private static void readPackedFloats(Proto p, int wire, List<Float> out) {
        if (wire == 5) {
            out.add(p.readFloat());
        } else if (wire == 2) {
            Proto s = p.sub();
            while (!s.done()) {
                out.add(s.readFloat());
            }
        } else {
            p.skip(wire);
        }
    }

    private static void readPackedVarints(Proto p, int wire, List<Long> out) {
        if (wire == 0) {
            out.add(p.readVarint());
        } else if (wire == 2) {
            Proto s = p.sub();
            while (!s.done()) {
                out.add(s.readVarint());
            }
        } else {
            p.skip(wire);
        }
    }

    private static Sig parseValueInfo(Proto p) {
        String name = "";
        int dtype = 0;
        int[] dims = null;
        while (!p.done()) {
            long tag = p.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (field == 1 && wire == 2) {
                name = p.readString();
            } else if (field == 2 && wire == 2) {
                Proto type = p.sub();
                while (!type.done()) {
                    long t = type.readVarint();
                    int f = (int) (t >>> 3);
                    int w = (int) (t & 7);
                    if (f == 1 && w == 2) {
                        Proto tensor = type.sub();
                        while (!tensor.done()) {
                            long tt = tensor.readVarint();
                            int tf = (int) (tt >>> 3);
                            int tw = (int) (tt & 7);
                            if (tf == 1 && tw == 0) {
                                dtype = (int) tensor.readVarint();
                            } else if (tf == 2 && tw == 2) {
                                dims = parseShape(tensor.sub(), name);
                            } else {
                                tensor.skip(tw);
                            }
                        }
                    } else {
                        type.skip(w);
                    }
                }
            } else {
                p.skip(wire);
            }
        }
        return new Sig(name, dtype, dims);
    }

    private static int[] parseShape(Proto p, String name) {
        List<Integer> dims = new ArrayList<>();
        while (!p.done()) {
            long tag = p.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (field == 1 && wire == 2) {
                Proto d = p.sub();
                Long value = null;
                boolean symbolic = false;
                while (!d.done()) {
                    long t = d.readVarint();
                    int f = (int) (t >>> 3);
                    int w = (int) (t & 7);
                    if (f == 1 && w == 0) {
                        value = d.readVarint();
                    } else if (f == 2 && w == 2) {
                        d.readString();
                        symbolic = true;
                    } else {
                        d.skip(w);
                    }
                }
                if (symbolic || value == null) {
                    dims.add(-1);
                } else {
                    dims.add(fitDim(value, name));
                }
            } else {
                p.skip(wire);
            }
        }
        int[] out = new int[dims.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = dims.get(i);
        }
        return out;
    }

    private record NamedTensor(String name, Tensor tensor) {
    }

    private static NamedTensor parseTensor(Proto p, Path baseDir) {
        List<Long> dims = new ArrayList<>();
        int dtype = 0;
        List<Float> floats = new ArrayList<>();
        List<Long> int32 = new ArrayList<>();
        List<Long> int64Scalars = new ArrayList<>();
        byte[] int64Blob = null;
        byte[] raw = null;
        String name = "";
        int location = 0;
        Map<String, String> external = new LinkedHashMap<>();
        List<Double> doubles = new ArrayList<>();
        while (!p.done()) {
            long tag = p.readVarint();
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            switch (field) {
                case 1 -> {
                    if (wire == 0) {
                        dims.add(p.readVarint());
                    } else if (wire == 1) {
                        dims.add(p.readFixed64());
                    } else if (wire == 2) {
                        Proto s = p.sub();
                        while (!s.done()) {
                            dims.add(s.readVarint());
                        }
                    } else {
                        p.skip(wire);
                    }
                }
                case 2 -> dtype = (int) p.readVarint();
                case 4 -> readPackedFloats(p, wire, floats);
                case 5 -> readPackedVarints(p, wire, int32);
                case 7 -> {
                    if (wire == 0) {
                        int64Scalars.add(p.readVarint());
                    } else if (wire == 1) {
                        int64Scalars.add(p.readFixed64());
                    } else if (wire == 2) {
                        int64Blob = p.sub().remainingBytes();
                    } else {
                        p.skip(wire);
                    }
                }
                case 8 -> name = p.readString();
                case 9 -> raw = p.readBytes();
                case 10 -> {
                    if (wire == 1) {
                        doubles.add(p.readDouble());
                    } else if (wire == 2) {
                        Proto s = p.sub();
                        while (!s.done()) {
                            doubles.add(s.readDouble());
                        }
                    } else {
                        p.skip(wire);
                    }
                }
                case 13 -> {
                    Proto s = p.sub();
                    String k = "";
                    String v = "";
                    while (!s.done()) {
                        long t = s.readVarint();
                        int f = (int) (t >>> 3);
                        int w = (int) (t & 7);
                        if (f == 1 && w == 2) {
                            k = s.readString();
                        } else if (f == 2 && w == 2) {
                            v = s.readString();
                        } else {
                            s.skip(w);
                        }
                    }
                    external.put(k, v);
                }
                case 14 -> location = (int) p.readVarint();
                default -> p.skip(wire);
            }
        }
        if (location == 1) {
            raw = readExternal(name, external, baseDir);
        }
        int[] d = new int[dims.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = fitDim(dims.get(i), name);
        }
        return new NamedTensor(name, materialize(name, dtype, d, raw, floats, int32, int64Scalars, int64Blob, doubles));
    }

    /**
     * Tensor dimensions are int64 on the wire and int in the runtime.
     * A negative value is not a dynamic dimension: truncating it invents a small shape,
     * and {@code Long.MIN_VALUE} becomes zero.
     */
    private static int fitDim(long value, String name) {
        if (value < 0) {
            throw new OcrException("negative dimension " + value + " in " + name);
        }
        if (value > Integer.MAX_VALUE) {
            throw new OcrException("dim too large in " + name);
        }
        return (int) value;
    }

    private static byte[] readExternal(String name, Map<String, String> external, Path baseDir) {
        String loc = external.get("location");
        if (loc == null || loc.isEmpty() || baseDir == null) {
            throw new OcrException("external data missing for " + name);
        }
        if (baseDir == null) {
            throw new OcrException("external data needs a model directory for " + name);
        }
        Path root = baseDir.toAbsolutePath().normalize();
        Path file = root.resolve(loc).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new OcrException("external data escapes the model directory for " + name);
        }
        try {
            Path realRoot = root.toRealPath();
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                throw new OcrException("external data escapes the model directory for " + name);
            }
            byte[] all = Files.readAllBytes(realFile);
            long off = externalLong(external, "offset", 0L, name);
            long len = externalLong(external, "length", all.length - off, name);
            // off + len overflows a long when both are near Long.MAX_VALUE.
            if (off < 0 || len < 0 || len > Integer.MAX_VALUE || off > all.length || len > all.length - off) {
                throw new OcrException("bad external range for " + name);
            }
            byte[] slice = new byte[(int) len];
            System.arraycopy(all, (int) off, slice, 0, slice.length);
            return slice;
        } catch (IOException e) {
            throw new OcrException("cannot read external data for " + name + ": " + e.getMessage(), e);
        }
    }

    private static long externalLong(Map<String, String> external, String key, long fallback, String name) {
        String value = external.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new OcrException("bad external range for " + name);
        }
    }

    private static Tensor materialize(
            String name,
            int dtype,
            int[] dims,
            byte[] raw,
            List<Float> floats,
            List<Long> int32,
            List<Long> int64Scalars,
            byte[] int64Blob,
            List<Double> doubles
    ) {
        int n = Tensor.numel(dims);
        if (n == 0) {
            return isFloat(dtype) ? Tensor.f32(dims, new float[0]) : Tensor.i64(dims, new long[0]);
        }
        int elem = elemSize(dtype);
        if (raw != null && elem > 0 && (long) n * elem == raw.length) {
            return fromRaw(name, dtype, dims, n, raw);
        }
        if (dtype == 7) {
            if (int64Blob != null) {
                // Protobuf packs int64_data as varints, and those may add up to eight bytes a
                // value by chance. Fixed eight-byte values are what a few writers store instead.
                long[] packed = tryVarints(int64Blob, n);
                if (packed != null) {
                    return Tensor.i64(dims, packed);
                }
                if (int64Blob.length == n * 8L) {
                    return Tensor.i64(dims, leLongs(int64Blob, n));
                }
            }
            if (int64Scalars.size() == n) {
                return Tensor.i64(dims, toLongs(int64Scalars));
            }
        }
        if ((dtype == 1 || dtype == 10 || dtype == 16 || dtype == 11) && floats.size() == n) {
            return Tensor.f32(dims, toFloats(floats));
        }
        if (dtype == 11 && doubles.size() == n) {
            float[] f = new float[n];
            for (int i = 0; i < n; i++) {
                f[i] = doubles.get(i).floatValue();
            }
            return Tensor.f32(dims, f);
        }
        if ((dtype == 6 || dtype == 2 || dtype == 3 || dtype == 9) && int32.size() == n) {
            return Tensor.i64(dims, toLongs(int32));
        }
        throw new OcrException("cannot decode tensor " + name
                + " dtype=" + dtype
                + " shape=" + java.util.Arrays.toString(dims)
                + " n=" + n
                + " raw=" + (raw == null ? -1 : raw.length)
                + " f32=" + floats.size()
                + " i64scalars=" + int64Scalars.size()
                + " i64blob=" + (int64Blob == null ? -1 : int64Blob.length));
    }

    private static boolean isFloat(int dtype) {
        return dtype == 1 || dtype == 10 || dtype == 11 || dtype == 16 || dtype == 0;
    }

    private static int elemSize(int dtype) {
        return switch (dtype) {
            case 1, 6, 12 -> 4;
            case 7, 11, 13 -> 8;
            case 10, 4, 5, 16 -> 2;
            case 2, 3, 9 -> 1;
            default -> 0;
        };
    }

    private static Tensor fromRaw(String name, int dtype, int[] dims, int n, byte[] raw) {
        return switch (dtype) {
            case 1 -> {
                float[] f = new float[n];
                for (int i = 0; i < n; i++) {
                    f[i] = Float.intBitsToFloat(intLE(raw, i * 4));
                }
                yield Tensor.f32(dims, f);
            }
            case 10 -> Tensor.f32(dims, fp16(raw, n, false));
            case 16 -> Tensor.f32(dims, fp16(raw, n, true));
            case 11 -> {
                float[] f = new float[n];
                for (int i = 0; i < n; i++) {
                    f[i] = (float) Double.longBitsToDouble(longLE(raw, i * 8));
                }
                yield Tensor.f32(dims, f);
            }
            case 7, 13 -> Tensor.i64(dims, leLongs(raw, n));
            case 6, 12 -> {
                long[] v = new long[n];
                for (int i = 0; i < n; i++) {
                    v[i] = intLE(raw, i * 4);
                }
                yield Tensor.i64(dims, v);
            }
            case 5 -> {
                long[] v = new long[n];
                for (int i = 0; i < n; i++) {
                    v[i] = (short) (raw[i * 2] & 0xff | (raw[i * 2 + 1] << 8));
                }
                yield Tensor.i64(dims, v);
            }
            case 2, 3, 9 -> {
                long[] v = new long[n];
                for (int i = 0; i < n; i++) {
                    int b = raw[i] & 0xff;
                    v[i] = dtype == 3 ? (byte) b : b == 0 ? 0 : (dtype == 9 ? 1 : b);
                }
                yield Tensor.i64(dims, v);
            }
            default -> throw new OcrException("unsupported raw dtype " + dtype + " in " + name);
        };
    }

    private static float[] fp16(byte[] raw, int n, boolean bfloat) {
        float[] f = new float[n];
        for (int i = 0; i < n; i++) {
            int h = (raw[i * 2] & 0xff) | ((raw[i * 2 + 1] & 0xff) << 8);
            f[i] = bfloat ? Float.intBitsToFloat(h << 16) : fp16ToFloat(h);
        }
        return f;
    }

    static float fp16ToFloat(int h) {
        int sign = (h & 0x8000) << 16;
        int exp = (h >>> 10) & 0x1f;
        int mant = h & 0x3ff;
        if (exp == 0) {
            if (mant == 0) {
                return Float.intBitsToFloat(sign);
            }
            exp = 1;
            while ((mant & 0x400) == 0) {
                mant <<= 1;
                exp--;
            }
            mant &= 0x3ff;
        } else if (exp == 31) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mant << 13));
        }
        int exp32 = exp + (127 - 15);
        return Float.intBitsToFloat(sign | (exp32 << 23) | (mant << 13));
    }

    private static int intLE(byte[] raw, int off) {
        return (raw[off] & 0xff)
                | ((raw[off + 1] & 0xff) << 8)
                | ((raw[off + 2] & 0xff) << 16)
                | ((raw[off + 3] & 0xff) << 24);
    }

    private static long longLE(byte[] raw, int off) {
        long lo = intLE(raw, off) & 0xffffffffL;
        long hi = intLE(raw, off + 4) & 0xffffffffL;
        return lo | (hi << 32);
    }

    private static long[] leLongs(byte[] raw, int n) {
        long[] v = new long[n];
        for (int i = 0; i < n; i++) {
            v[i] = longLE(raw, i * 8);
        }
        return v;
    }

    /** Exactly {@code expect} varints that use the whole blob, or null. */
    private static long[] tryVarints(byte[] blob, int expect) {
        // A varint is at least one byte: the shape of a small file must not size the array.
        if (expect > blob.length) {
            return null;
        }
        try {
            Proto s = new Proto(blob, 0, blob.length);
            long[] v = new long[expect];
            for (int i = 0; i < expect; i++) {
                if (s.done()) {
                    return null;
                }
                v[i] = s.readVarint();
            }
            return s.done() ? v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long[] toLongs(List<Long> list) {
        long[] v = new long[list.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = list.get(i);
        }
        return v;
    }

    private static float[] toFloats(List<Float> list) {
        float[] v = new float[list.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = list.get(i);
        }
        return v;
    }
}
