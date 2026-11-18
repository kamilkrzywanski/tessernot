package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.eval.Samples;
import io.github.kamilkrzywanski.ocr.internal.runtime.Session;
import io.github.kamilkrzywanski.ocr.internal.runtime.Tensor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MemoryBudgetTest {
    @Test
    void takesAndReturnsKibibytes() {
        MemoryBudget budget = new MemoryBudget(10L << 10);
        assertEquals(10, budget.free());
        int held = budget.acquire(4L << 10);
        assertEquals(4, held);
        assertEquals(6, budget.free());
        // A part of a kibibyte counts as a whole one, and nothing still takes one.
        int part = budget.acquire(1025);
        assertEquals(2, part);
        int nothing = budget.acquire(0);
        assertEquals(1, nothing);
        budget.release(held);
        budget.release(part);
        budget.release(nothing);
        assertEquals(10, budget.free());
    }

    @Test
    void aPieceLargerThanTheBudgetTakesAllOfIt() {
        MemoryBudget budget = new MemoryBudget(10L << 10);
        int held = budget.acquire(1L << 40);
        assertEquals(10, held);
        assertEquals(0, budget.free());
        budget.release(held);
        assertEquals(1, new MemoryBudget(0).acquire(Long.MAX_VALUE));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void waitsUntilEnoughIsReturned() throws Exception {
        MemoryBudget budget = new MemoryBudget(10L << 10);
        int first = budget.acquire(6L << 10);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch taken = new CountDownLatch(1);
        Thread second = new Thread(() -> {
            started.countDown();
            int held = budget.acquire(6L << 10);
            taken.countDown();
            budget.release(held);
        });
        second.start();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        // The second piece does not fit beside the first one.
        assertFalse(taken.await(200, TimeUnit.MILLISECONDS));
        budget.release(first);
        assertTrue(taken.await(10, TimeUnit.SECONDS));
        second.join(10_000);
        assertEquals(10, budget.free());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void anInterruptedWaitFailsAndKeepsTheFlag() throws Exception {
        MemoryBudget budget = new MemoryBudget(1L << 10);
        int held = budget.acquire(1L << 10);
        AtomicReference<Throwable> seen = new AtomicReference<>();
        AtomicReference<Boolean> flag = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                budget.acquire(1L << 10);
            } catch (Throwable error) {
                seen.set(error);
                flag.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        waiter.interrupt();
        waiter.join(10_000);
        assertTrue(seen.get() instanceof OcrException, String.valueOf(seen.get()));
        assertEquals(Boolean.TRUE, flag.get());
        budget.release(held);
        assertEquals(1, budget.free());
    }

    /** The detection budget is only as good as the estimate of what one detection takes. */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void oneDetectionStaysWithinItsEstimate() {
        Path model = Path.of("models", ModelFiles.DET);
        assumeTrue(Files.exists(model));
        for (int[] size : new int[][]{{640, 640}, {992, 736}, {416, 1248}}) {
            Session det = Session.load(model, new Session.Options(true, 1, null, false, false, true));
            long pixels = (long) size[0] * size[1];
            Tensor input = Tensor.f32(new int[]{1, 3, size[1], size[0]}, new float[(int) (3 * pixels)]);
            Tensor map = det.run(Map.of(det.inputName(), input)).values().iterator().next();
            // The activations, the input tensor, the output and the copy the engine makes of it.
            long used = det.allocatedBytes() + 4L * input.f.length + 2 * 4L * map.f.length;
            assertTrue(used <= OcrEngine.DET_BYTES_PER_PIXEL * pixels,
                    size[0] + "x" + size[1] + " took " + used / pixels + " bytes per pixel");
            assertTrue(det.idleBytes() <= Session.RETAIN_BYTES, "kept " + det.idleBytes());
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void anIdleEngineKeepsLittleMoreThanItsWeights() {
        assumeTrue(Files.exists(Path.of("models", ModelFiles.DET)));
        OcrConfig config = OcrConfig.defaults(Path.of("models")).toBuilder().threads(3).build();
        try (OcrEngine engine = OcrEngine.create(config)) {
            assertEquals(0L, engine.idleBytes());
            OcrResult one = engine.recognize(Samples.invoice());
            assertFalse(one.lines().isEmpty());
            // One detection session, and a recognizer and a classifier per thread.
            long ceiling = 7 * (8L << 20);
            assertTrue(engine.idleBytes() <= ceiling, "after a page: " + engine.idleBytes());
            List<OcrResult> pages = engine.recognizePages(3, i -> Samples.invoice(), null);
            assertEquals(3, pages.size());
            assertEquals(one.text(), pages.get(2).text());
            assertTrue(engine.idleBytes() <= ceiling, "after a document: " + engine.idleBytes());
            assertThrows(OcrException.class, () -> engine.recognize(null));
            assertTrue(engine.idleBytes() <= ceiling, "after a failed call: " + engine.idleBytes());
        }
    }

    @Test
    void aPictureTooLargeForTheHeapIsShrunkUntilItsPageFits() {
        long gigabyte = 1L << 30;
        // A4 at 300 dpi, 8.7 megapixels: about 70 MB of a page share of 307 MB.
        assertEquals(1, MemoryBudget.shrink(2472, 3508, gigabyte));
        // A receipt at 100 megapixels: 800 MB as it is, 200 MB at half its size.
        assertEquals(2, MemoryBudget.shrink(7176, 13968, gigabyte));
        assertEquals(1, MemoryBudget.shrink(7176, 13968, 4 * gigabyte));
        // A third when a half is still too much.
        assertEquals(3, MemoryBudget.shrink(7176, 13968, gigabyte / 2));
        assertEquals(1, MemoryBudget.shrink(0, 0, gigabyte));
    }

    @Test
    void aDetectionTooLargeForTheHeapRunsOnASmallerCopy() {
        long gigabyte = 1L << 30;
        long perPixel = OcrEngine.DET_BYTES_PER_PIXEL;
        int[] a4 = {1280, 2048};
        // An A4 page at the default limit is left alone from 768 MB up, also when the collector
        // keeps a survivor space out of what the JVM reports (954 MB of -Xmx1g).
        assertSame(a4, MemoryBudget.detectionSize(a4, gigabyte, perPixel));
        assertSame(a4, MemoryBudget.detectionSize(a4, 954L << 20, perPixel));
        assertSame(a4, MemoryBudget.detectionSize(a4, 768L << 20, perPixel));
        // In 512 MB it is detected smaller, in the same shape, on multiples of 32.
        int[] half = MemoryBudget.detectionSize(a4, gigabyte / 2, perPixel);
        assertArrayEquals(new int[] {1056, 1664}, half);
        assertTrue(perPixel * half[0] * half[1] <= gigabyte / 2 / 4 * 3);
        // A square page at the limit takes three quarters of 1 GB, not seven eighths.
        assertArrayEquals(new int[] {1888, 1888}, MemoryBudget.detectionSize(new int[] {2048, 2048}, gigabyte, perPixel));
        // Never under one block of 32 a side.
        assertArrayEquals(new int[] {320, 32}, MemoryBudget.detectionSize(new int[] {2048, 64}, 1L << 20, perPixel));
    }
}
