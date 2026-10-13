package io.github.kamilkrzywanski.ocr.runtime;

public final class Attr {
    public enum Kind { INT, FLOAT, STRING, INTS, FLOATS, TENSOR }

    public final String name;
    public final Kind kind;
    public final long i;
    public final float f;
    public final String s;
    public final long[] ints;
    public final float[] floats;
    public final Tensor tensor;

    private Attr(String name, Kind kind, long i, float f, String s, long[] ints, float[] floats, Tensor tensor) {
        this.name = name;
        this.kind = kind;
        this.i = i;
        this.f = f;
        this.s = s;
        this.ints = ints;
        this.floats = floats;
        this.tensor = tensor;
    }

    public static Attr of(String name, long v) {
        return new Attr(name, Kind.INT, v, 0f, null, null, null, null);
    }

    public static Attr of(String name, float v) {
        return new Attr(name, Kind.FLOAT, 0L, v, null, null, null, null);
    }

    public static Attr of(String name, String v) {
        return new Attr(name, Kind.STRING, 0L, 0f, v, null, null, null);
    }

    public static Attr ints(String name, long... v) {
        return new Attr(name, Kind.INTS, 0L, 0f, null, v, null, null);
    }

    public static Attr floats(String name, float... v) {
        return new Attr(name, Kind.FLOATS, 0L, 0f, null, null, v, null);
    }

    public static Attr tensor(String name, Tensor t) {
        return new Attr(name, Kind.TENSOR, 0L, 0f, null, null, null, t);
    }

    public long[] asLongs() {
        if (kind == Kind.INTS) {
            return ints;
        }
        if (kind == Kind.INT) {
            return new long[]{i};
        }
        return null;
    }
}
