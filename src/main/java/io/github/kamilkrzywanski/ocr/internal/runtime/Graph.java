package io.github.kamilkrzywanski.ocr.internal.runtime;

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

    public void optimize() {
        Optimizer.optimize(this);
    }
}
