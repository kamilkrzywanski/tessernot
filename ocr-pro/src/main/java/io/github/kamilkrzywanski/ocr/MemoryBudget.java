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
     * By how much a picture is shrunk, each way, before it is read: 1 for almost every picture.
     * A page in flight takes about 8 bytes a pixel (the caller's bitmap and the engine's copy),
     * and the pages of a document may take three tenths of the heap; a single picture that would
     * take more than that share on its own is read at half its size, or a third, until it fits.
     * A receipt photographed at 100 megapixels then reads in a 1 GB heap, where it ran out of
     * memory, and still has lines many times higher than the recognizer needs.
     */
    static int shrink(long width, long height, long heapBytes) {
        long budget = heapBytes / 10 * 3;
        int factor = 1;
        while (8L * (width / factor) * (height / factor) > budget && factor < 64) {
            factor++;
        }
        return factor;
    }

    /**
     * The size one detection runs at in this heap: {@code size} itself for almost every page.
     * A detection that would take more than three quarters of the heap, at {@code bytesPerPixel}
     * of the scaled page, is run on a smaller copy of the page that takes no more, both sides
     * still multiples of 32. With the default limit of 2048 px an A4 page (1280 by 2048) is left
     * alone from 768 MB up and detected at 1056 by 1664 in 512 MB, where it ran out of memory.
     * <p>
     * Measured on an A4 and a square page with heaps of 512 MB to 1 GB and limits of 960 to
     * 2048: a detection read through with three quarters of the heap, twice out of twice, and
     * ran out of memory from 107 %. At 384 MB the engine runs out as often as not, whatever the
     * detection takes: that heap is too small for the rest.
     *
     * @param size width and height the detector would be given, multiples of 32
     * @return {@code size}, or a smaller width and height
     */
    static int[] detectionSize(int[] size, long heapBytes, long bytesPerPixel) {
        long most = Math.max(32 * 32, heapBytes / 4 * 3 / bytesPerPixel);
        long pixels = (long) size[0] * size[1];
        if (pixels <= most) {
            return size;
        }
        double scale = Math.sqrt(most / (double) pixels);
        return new int[] {Math.max(32, (int) (size[0] * scale) / 32 * 32), Math.max(32, (int) (size[1] * scale) / 32 * 32)};
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
