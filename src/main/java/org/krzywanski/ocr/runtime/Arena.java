package org.krzywanski.ocr.runtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Float and long buffers. Alloc does not zero memory; the caller overwrites the elements it uses
 * or fills explicitly. Size 0 returns a shared empty array and is not pooled.
 * <p>
 * Float requests above 256 elements round up to a power of two and share that bucket. A line
 * session would otherwise keep a distinct buffer for every layer shape it has ever seen.
 */
public final class Arena {
    private static final float[] EMPTY_F = new float[0];
    private static final long[] EMPTY_I = new long[0];
    private static final int EXACT_LIMIT = 256;

    private final Map<Integer, ArrayDeque<float[]>> floats = new HashMap<>();
    private final Map<Integer, ArrayDeque<long[]>> longs = new HashMap<>();

    /** Bucket length for a float request. Small arrays stay exact. */
    static int floatCapacity(int n) {
        if (n <= EXACT_LIMIT) {
            return n;
        }
        int capacity = EXACT_LIMIT;
        while (capacity < n) {
            if (capacity > Integer.MAX_VALUE >>> 1) {
                return n;
            }
            capacity <<= 1;
        }
        return capacity;
    }

    public float[] allocF(int n) {
        if (n == 0) {
            return EMPTY_F;
        }
        int capacity = floatCapacity(n);
        ArrayDeque<float[]> queue = floats.get(capacity);
        if (queue != null && !queue.isEmpty()) {
            return queue.pollFirst();
        }
        return new float[capacity];
    }

    /** Returns a temporary float buffer to the pool. The caller must not use it afterwards. */
    public void freeF(float[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        floats.computeIfAbsent(data.length, key -> new ArrayDeque<>()).addLast(data);
    }

    public long[] allocI(int n) {
        if (n == 0) {
            return EMPTY_I;
        }
        ArrayDeque<long[]> queue = longs.get(n);
        if (queue != null && !queue.isEmpty()) {
            return queue.pollFirst();
        }
        return new long[n];
    }

    public void free(Tensor tensor) {
        if (tensor == null || !tensor.pooled) {
            return;
        }
        tensor.pooled = false;
        if (tensor.dtype == Tensor.F32 && tensor.f != null && tensor.f.length > 0) {
            floats.computeIfAbsent(tensor.f.length, key -> new ArrayDeque<>()).addLast(tensor.f);
        } else if (tensor.i != null && tensor.i.length > 0) {
            longs.computeIfAbsent(tensor.i.length, key -> new ArrayDeque<>()).addLast(tensor.i);
        }
    }
}
