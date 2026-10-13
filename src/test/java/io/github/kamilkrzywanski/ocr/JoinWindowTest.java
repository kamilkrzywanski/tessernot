package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JoinWindowTest {
    @Test
    void emptyWorkIsEmpty() {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            assertEquals(List.of(), OcrEngine.joinWindow(pool, 2, 0, index -> () -> index));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void submitsOnlyAsManyAsTheWindowAndKeepsOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        AtomicReference<List<Integer>> results = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                results.set(OcrEngine.joinWindow(pool, 2, 5, index -> () -> {
                    started.incrementAndGet();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new OcrException("release timed out");
                    }
                    return index;
                }));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        runner.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (started.get() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            Thread.sleep(80);
            assertEquals(2, started.get());
            release.countDown();
            runner.join(5_000);
            assertFalse(runner.isAlive());
            assertNull(failure.get());
            assertEquals(List.of(0, 1, 2, 3, 4), results.get());
        } finally {
            release.countDown();
            runner.join(5_000);
            pool.shutdownNow();
        }
    }

    @Test
    void readyRunsBeforeTheNextJobStarts() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Integer> seen = new ArrayList<>();
            List<Integer> results = OcrEngine.joinWindow(pool, 1, 3, index -> () -> {
                if (index > 0) {
                    assertEquals(index, seen.size());
                }
                return index;
            }, seen::add);
            assertEquals(List.of(0, 1, 2), seen);
            assertEquals(seen, results);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void readyFailureStillJoinsWorkAlreadySubmitted() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            AtomicInteger ran = new AtomicInteger();
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> OcrEngine.joinWindow(
                    pool, 2, 4, index -> () -> {
                        ran.incrementAndGet();
                        return index;
                    }, value -> {
                        if (value == 0) {
                            throw new IllegalStateException("stop");
                        }
                    }));
            assertEquals("stop", error.getMessage());
            assertEquals(7, pool.submit(() -> ran.get() + 5).get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void slowFirstJobStillReturnsSubmissionOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Integer> results = OcrEngine.joinWindow(pool, 2, 4, index -> () -> {
                if (index == 0) {
                    Thread.sleep(80);
                }
                return index;
            });
            assertEquals(List.of(0, 1, 2, 3), results);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failureWaitsForJobsAlreadySubmitted() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger ran = new AtomicInteger();
        try {
            OcrException error = assertThrows(OcrException.class, () -> OcrEngine.joinWindow(pool, 2, 4, index -> () -> {
                ran.incrementAndGet();
                if (index == 0) {
                    throw new OcrException("boom");
                }
                return index;
            }));
            assertEquals("boom", error.getMessage());
            assertEquals(2, ran.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void factoryFailureStillJoinsTheSubmittedPrefix() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger ran = new AtomicInteger();
        try {
            OcrException error = assertThrows(OcrException.class, () -> OcrEngine.joinWindow(pool, 2, 4, index -> {
                if (index == 2) {
                    throw new OcrException("render");
                }
                return () -> {
                    ran.incrementAndGet();
                    return index;
                };
            }));
            assertEquals("render", error.getMessage());
            assertEquals(2, ran.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
