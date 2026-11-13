package io.github.kamilkrzywanski.ocr.internal.runtime;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;

/**
 * Float and long buffers. Alloc does not zero memory; the caller overwrites the elements it uses
 * or fills explicitly. Size 0 returns a shared empty array and is not pooled.
 * <p>
 * Float requests above 256 elements round up to one of four sizes per doubling and share that
 * bucket. A line session would otherwise keep a distinct buffer for every layer shape it has ever
 * seen. A request takes the smallest idle buffer that fits, up to twice its size, before a new
 * array is made: the large maps of the first layers then serve the smaller ones that follow,
 * instead of lying idle beside them.
 * <p>
 * Idle buffers stay until {@link #trim(long)}, which a session calls after every run, so what an
 * arena keeps between runs has a ceiling.
 */
public final class Arena {
    private static final float[] EMPTY_F = new float[0];
    private static final long[] EMPTY_I = new long[0];
    private static final int EXACT_LIMIT = 256;

    private final TreeMap<Integer, ArrayDeque<float[]>> floats = new TreeMap<>();
    private final TreeMap<Integer, ArrayDeque<long[]>> longs = new TreeMap<>();
    /** Bytes of the idle buffers in both maps. */
    private long idleBytes;
    /** Bytes of every array this arena has created. */
    private long allocatedBytes;

    /**
     * Bucket length for a float request. Small arrays stay exact; above that there are four sizes
     * per doubling, so a buffer is at most a quarter larger than what was asked for.
     */
    static int floatCapacity(int n) {
        if (n <= EXACT_LIMIT) {
            return n;
        }
        int high = Integer.highestOneBit(n - 1);
        if (high > Integer.MAX_VALUE >>> 2) {
            return n;
        }
        int step = high >>> 2;
        return (n + step - 1) / step * step;
    }

    public float[] allocF(int n) {
        if (n == 0) {
            return EMPTY_F;
        }
        int capacity = floatCapacity(n);
        // The smallest idle buffer that fits, up to twice the size: one many times too large
        // would be missing when its own size is asked for.
        long limit = 2L * capacity;
        for (Map.Entry<Integer, ArrayDeque<float[]>> entry : floats.tailMap(capacity, true).entrySet()) {
            if (entry.getKey() > limit) {
                break;
            }
            float[] idle = entry.getValue().pollFirst();
            if (idle != null) {
                idleBytes -= 4L * idle.length;
                return idle;
            }
        }
        allocatedBytes += 4L * capacity;
        return new float[capacity];
    }

    /** Returns a temporary float buffer to the pool. The caller must not use it afterwards. */
    public void freeF(float[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        floats.computeIfAbsent(data.length, key -> new ArrayDeque<>()).addLast(data);
        idleBytes += 4L * data.length;
    }

    public long[] allocI(int n) {
        if (n == 0) {
            return EMPTY_I;
        }
        ArrayDeque<long[]> queue = longs.get(n);
        if (queue != null && !queue.isEmpty()) {
            idleBytes -= 8L * n;
            return queue.pollFirst();
        }
        allocatedBytes += 8L * n;
        return new long[n];
    }

    public void free(Tensor tensor) {
        if (tensor == null || !tensor.pooled) {
            return;
        }
        tensor.pooled = false;
        if (tensor.dtype == Tensor.F32 && tensor.f != null && tensor.f.length > 0) {
            freeF(tensor.f);
        } else if (tensor.i != null && tensor.i.length > 0) {
            longs.computeIfAbsent(tensor.i.length, key -> new ArrayDeque<>()).addLast(tensor.i);
            idleBytes += 8L * tensor.i.length;
        }
    }

    /** Bytes of the buffers that wait here for the next request. */
    public long idleBytes() {
        return idleBytes;
    }

    /** Bytes of every array this arena has created since it was made. */
    public long allocatedBytes() {
        return allocatedBytes;
    }

    /**
     * Drops idle buffers, the largest first, until at most {@code keepBytes} wait here. The small
     * ones are the many a run asks for most often, and the large ones are what an idle session
     * would otherwise hold on to.
     */
    public void trim(long keepBytes) {
        while (idleBytes > keepBytes) {
            Map.Entry<Integer, ArrayDeque<float[]>> f = floats.lastEntry();
            if (f != null && f.getValue().isEmpty()) {
                floats.remove(f.getKey());
                continue;
            }
            Map.Entry<Integer, ArrayDeque<long[]>> l = longs.lastEntry();
            if (l != null && l.getValue().isEmpty()) {
                longs.remove(l.getKey());
                continue;
            }
            if (f == null && l == null) {
                idleBytes = 0;
                return;
            }
            long fBytes = f == null ? -1L : 4L * f.getKey();
            long lBytes = l == null ? -1L : 8L * l.getKey();
            if (fBytes >= lBytes) {
                f.getValue().pollLast();
                idleBytes -= fBytes;
                if (f.getValue().isEmpty()) {
                    floats.remove(f.getKey());
                }
            } else {
                l.getValue().pollLast();
                idleBytes -= lBytes;
                if (l.getValue().isEmpty()) {
                    longs.remove(l.getKey());
                }
            }
        }
    }
}
