package io.github.kamilkrzywanski.ocr.runtime;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;

/**
 * Interprets one graph. The graph and its initializers are shared and must not be mutated after load.
 * Each session owns an arena. Do not run one session from two threads.
 */
public final class Session {
    private static final boolean PROFILE = Boolean.getBoolean("ocr.profile");

    private final Graph graph;
    private final Arena arena = new Arena();
    private final boolean vector;
    private final int threads;
    private final ExecutorService pool;
    private final boolean quantize;
    private final boolean trace;

    private Session(Graph graph, Options options) {
        this.graph = graph;
        this.vector = options.vector && VectorSupport.available();
        this.threads = options.threads;
        this.pool = options.pool;
        this.quantize = options.quantize;
        this.trace = options.trace || Boolean.getBoolean("ocr.trace");
    }

    public static Session load(Path path, Options options) {
        try {
            byte[] data = Files.readAllBytes(path);
            if (data.length == 0) {
                throw new OcrException("cannot load " + path + ": file is empty");
            }
            Graph graph;
            if (data.length >= 4 && data[0] == 'J' && data[1] == 'I' && data[2] == 'R' && data[3] == '1') {
                graph = Jir.read(data);
            } else {
                Path parent = path.getParent() == null ? Path.of(".") : path.getParent();
                graph = Onnx.read(data, parent);
                if (options.optimize) {
                    graph.optimize();
                }
            }
            return new Session(graph, options);
        } catch (java.io.IOException e) {
            throw new OcrException("cannot load " + path + ": " + e.getMessage(), e);
        }
    }

    public static Session create(Graph graph, Options options) {
        if (options.optimize) {
            graph.optimize();
        }
        return new Session(graph, options);
    }

    /** Own arena, no nested thread pool. Weights stay shared. */
    public Session fork() {
        return new Session(graph, new Options(vector, 1, null, quantize, trace, false));
    }

    public Graph graph() {
        return graph;
    }

    public boolean vector() {
        return vector;
    }

    public String inputName() {
        for (Sig sig : graph.inputs) {
            if (!graph.initializers.containsKey(sig.name)) {
                return sig.name;
            }
        }
        if (graph.inputs.isEmpty()) {
            throw new OcrException("graph has no inputs");
        }
        return graph.inputs.get(0).name;
    }

    public Sig outputSig() {
        if (graph.outputs.isEmpty()) {
            throw new OcrException("graph has no outputs");
        }
        return graph.outputs.get(0);
    }

    public Map<String, Integer> histogram() {
        Map<String, Integer> counts = new TreeMap<>();
        for (Node node : graph.nodes) {
            counts.merge(node.op, 1, Integer::sum);
        }
        return counts;
    }

    public void saveJir(Path path) {
        Jir.save(graph, path);
    }

    public Map<String, Tensor> run(Map<String, Tensor> inputs) {
        Map<String, Tensor> env = new HashMap<>(graph.initializers.size() + inputs.size() + graph.nodes.size());
        env.putAll(graph.initializers);
        for (Sig sig : graph.inputs) {
            boolean hasInit = graph.initializers.containsKey(sig.name);
            Tensor provided = inputs.get(sig.name);
            if (provided == null) {
                if (!hasInit) {
                    throw new OcrException("missing input " + sig.name);
                }
                continue;
            }
            if (sig.dims != null && provided.rank() != sig.dims.length) {
                throw new OcrException("input " + sig.name + " rank " + provided.rank()
                        + " != " + sig.dims.length);
            }
            env.put(sig.name, provided);
        }
        Map<String, Integer> lastUse = new HashMap<>();
        for (int i = 0; i < graph.nodes.size(); i++) {
            Node node = graph.nodes.get(i);
            for (String in : node.inputs) {
                if (in != null && !in.isEmpty()) {
                    lastUse.put(in, i);
                }
            }
        }
        Set<String> immortal = new HashSet<>(graph.initializers.keySet());
        immortal.addAll(inputs.keySet());
        for (Sig sig : graph.outputs) {
            immortal.add(sig.name);
            immortal.add(graph.redirects.getOrDefault(sig.name, sig.name));
        }
        Run run = new Run(arena, vector, threads, pool, quantize, graph.opset, trace);
        Map<String, long[]> profile = PROFILE ? new HashMap<>() : null;
        for (int i = 0; i < graph.nodes.size(); i++) {
            Node node = graph.nodes.get(i);
            long started = (trace || PROFILE) ? System.nanoTime() : 0L;
            try {
                Ops.exec(node, env, run);
            } catch (RuntimeException ex) {
                if (ex instanceof OcrException oe && oe.getMessage() != null && oe.getMessage().startsWith("Op ")) {
                    throw oe;
                }
                throw new OcrException("Op " + node.op + " (" + node.name + ") inputs=["
                        + describe(node, env) + "] " + ex.getMessage(), ex);
            }
            if (trace || PROFILE) {
                long elapsed = System.nanoTime() - started;
                if (trace) {
                    Tensor produced = node.outputs.length == 0 ? null : env.get(node.outputs[0]);
                    System.err.printf(Locale.ROOT, "op %-20s %-24s -> %s  %.3f ms%n",
                            node.op, node.name,
                            produced == null ? "-" : produced.shapeString(),
                            elapsed / 1_000_000.0);
                }
                if (PROFILE) {
                    long[] bucket = profile.computeIfAbsent(profileKey(node, env), key -> new long[2]);
                    bucket[0] += elapsed;
                    bucket[1]++;
                }
            }
            for (String in : node.inputs) {
                if (in == null || in.isEmpty() || immortal.contains(in)) {
                    continue;
                }
                Integer last = lastUse.get(in);
                if (last != null && last == i) {
                    arena.free(env.get(in));
                }
            }
            for (String out : node.outputs) {
                if (out == null || out.isEmpty() || immortal.contains(out) || lastUse.containsKey(out)) {
                    continue;
                }
                arena.free(env.get(out));
            }
        }
        Map<String, Tensor> result = new LinkedHashMap<>();
        for (Sig sig : graph.outputs) {
            String key = graph.redirects.getOrDefault(sig.name, sig.name);
            Tensor value = env.get(key);
            if (value == null) {
                throw new OcrException("missing output " + sig.name + " (resolved " + key + ")");
            }
            Tensor copy = value.copy();
            if (value.pooled) {
                arena.free(value);
            }
            result.put(sig.name, copy);
        }
        if (PROFILE) {
            profile.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .forEach(entry -> System.err.printf(Locale.ROOT, "profile %-48s %8.1f ms  %d%n",
                            entry.getKey(), entry.getValue()[0] / 1_000_000.0, entry.getValue()[1]));
        }
        return result;
    }

    /** Groups a profiled node by op and, for Conv, by kernel and output shape. */
    private static String profileKey(Node node, Map<String, Tensor> env) {
        Tensor producedEarly = node.outputs.length == 0 ? null : env.get(node.outputs[0]);
        String shape = producedEarly == null ? "" : " " + producedEarly.shapeString();
        if ((!"Conv".equals(node.op) && !"ConvTranspose".equals(node.op)) || node.inputs.length < 2) {
            return node.op + shape;
        }
        Tensor weight = env.get(node.inputs[1]);
        Tensor produced = producedEarly;
        if (weight == null || weight.rank() != 4) {
            return node.op + shape;
        }
        long[] strides = node.longs("strides", null);
        String stride = strides == null ? "" : " s" + strides[0] + (strides.length > 1 ? "x" + strides[1] : "");
        return node.op + " " + weight.dims[2] + "x" + weight.dims[3]
                + stride
                + " g" + node.lng("group", 1)
                + " m" + weight.dims[0]
                + (produced == null ? "" : " " + produced.shapeString());
    }

    private static String describe(Node node, Map<String, Tensor> env) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < node.inputs.length; i++) {
            if (i > 0) {
                sb.append("; ");
            }
            String name = node.inputs[i];
            if (name == null || name.isEmpty()) {
                sb.append("<empty>");
                continue;
            }
            sb.append(name);
            Tensor t = env.get(name);
            if (t != null) {
                sb.append(Arrays.toString(t.dims));
            }
        }
        return sb.toString();
    }

    public record Options(
            boolean vector,
            int threads,
            ExecutorService pool,
            boolean quantize,
            boolean trace,
            boolean optimize
    ) {
        public Options {
            if (threads < 1) {
                throw new OcrException("threads must be >= 1");
            }
        }

        public static Options scalar() {
            return new Options(false, 1, null, false, false, false);
        }
    }
}
