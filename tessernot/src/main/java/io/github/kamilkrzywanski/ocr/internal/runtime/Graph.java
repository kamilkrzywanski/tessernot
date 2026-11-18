package io.github.kamilkrzywanski.ocr.internal.runtime;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable graph. After {@link #optimize()} weights are read-only and safe to share across sessions. */
public final class Graph {
    public int opset = 14;
    public final List<Sig> inputs = new ArrayList<>();
    public final List<Sig> outputs = new ArrayList<>();
    public final Map<String, Tensor> initializers = new LinkedHashMap<>();
    public final List<Node> nodes = new ArrayList<>();
    /** Graph output name to the tensor that actually produces it after identity elimination. */
    public final Map<String, String> redirects = new LinkedHashMap<>();
    /** Set by {@link #optimize()}, and for a JIR file, whose graph was optimized when written. */
    public boolean optimized;

    /** A node the optimizer cannot read, such as a Conv without weights, fails with an {@link OcrException}. */
    public void optimize() {
        try {
            Optimizer.optimize(this);
        } catch (OcrException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OcrException("cannot optimize the graph: " + e, e);
        }
        optimized = true;
    }
}
