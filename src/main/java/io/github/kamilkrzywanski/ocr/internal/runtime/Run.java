package io.github.kamilkrzywanski.ocr.internal.runtime;

import java.util.concurrent.ExecutorService;

final class Run {
    final Arena arena;
    final boolean vector;
    final int threads;
    final ExecutorService pool;
    final boolean quantize;
    final int opset;
    final boolean trace;

    Run(Arena arena, boolean vector, int threads, ExecutorService pool, boolean quantize, int opset, boolean trace) {
        this.arena = arena;
        this.vector = vector;
        this.threads = threads;
        this.pool = pool;
        this.quantize = quantize;
        this.opset = opset;
        this.trace = trace;
    }
}
