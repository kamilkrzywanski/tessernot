package io.github.kamilkrzywanski.ocr.internal.runtime;

import java.util.Arrays;

/** Graph input or output. A dim of {@code -1} is dynamic. {@code dims == null} means the rank is unknown. */
public final class Sig {
    public final String name;
    public final int dtype;
    public final int[] dims;

    public Sig(String name, int dtype, int[] dims) {
        this.name = name;
        this.dtype = dtype;
        this.dims = dims == null ? null : dims.clone();
    }

    @Override
    public String toString() {
        return name + " " + (dtype == Tensor.F32 ? "f32" : dtype == Tensor.I64 ? "i64" : ("dt" + dtype))
                + " " + (dims == null ? "?" : Arrays.toString(dims));
    }
}
