package io.github.kamilkrzywanski.ocr;

import java.util.concurrent.Semaphore;

/**
 * A share of the heap that callers take pieces of and wait for. A piece larger than the whole
 * budget takes all of it, so the largest job still runs, alone.
 */
final class MemoryBudget {
    /**
     * Detection maps at work in this JVM, over every engine: four tenths of the heap. One
     * detection needs a few hundred megabytes at 2048 px, so as many of them as there are threads
     * do not fit.
     */
    static final MemoryBudget DETECTION = new MemoryBudget(Runtime.getRuntime().maxMemory() / 10 * 4);

    private final int total;
    private final Semaphore permits;

    /** {@code bytes} below one kibibyte still leave one permit. */
    MemoryBudget(long bytes) {
        this.total = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, bytes >> 10));
        // Fair: a large page must not wait behind a stream of small ones.
        this.permits = new Semaphore(total, true);
    }

    /**
     * Three tenths of the heap for the pages one document has in flight: their bitmaps and the
     * recognizers that read them. With {@link #DETECTION} that leaves three tenths for the
     * weights, the word lists, the results and the collector.
     */
    static MemoryBudget pages() {
        return new MemoryBudget(Runtime.getRuntime().maxMemory() / 10 * 3);
    }

    /** Waits until {@code bytes} are free and takes them. Returns what to hand to {@link #release(int)}. */
    int acquire(long bytes) {
        int wanted = (int) Math.max(1L, Math.min(total, (bytes + 1023) >> 10));
        try {
            permits.acquire(wanted);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("interrupted", e);
        }
        return wanted;
    }

    void release(int taken) {
        permits.release(taken);
    }

    /** Kibibytes nobody holds. */
    int free() {
        return permits.availablePermits();
    }
}
