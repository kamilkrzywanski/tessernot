package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OcrPoolTest {
    @Test
    void rejectsAnEmptyPoolBeforeLoadingModels() {
        OcrException config = assertThrows(OcrException.class, () -> OcrPool.open((OcrConfig) null, 1));
        assertTrue(config.getMessage().contains("config is null"), config.getMessage());
        OcrException directory = assertThrows(OcrException.class, () -> OcrPool.open((Path) null, 1));
        assertTrue(directory.getMessage().contains("model directory is null"), directory.getMessage());
        OcrConfig blank = OcrConfig.builder().build();
        OcrException zero = assertThrows(OcrException.class, () -> OcrPool.open(blank, 0));
        assertTrue(zero.getMessage().contains("pool size"), zero.getMessage());
        OcrException negative = assertThrows(OcrException.class, () -> OcrPool.open(blank, -2));
        assertTrue(negative.getMessage().contains("pool size"), negative.getMessage());
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void parallelCallsMatchASingleEngine() throws Exception {
        assumeTrue(modelsPresent(), "models/ is missing");
        OcrConfig cfg = OcrConfig.latin(Path.of("models")).toBuilder().threads(1).build();
        BufferedImage image = Samples.invoice();
        OcrResult alone;
        OcrResult aloneLine;
        try (OcrEngine engine = OcrEngine.create(cfg)) {
            alone = engine.recognize(image);
            aloneLine = engine.recognizeLine(image);
        }
        assertTrue(alone.text().contains("FAKTURA"), alone.text());

        try (OcrPool pool = OcrPool.open(cfg, 2)) {
            assertEquals(2, pool.size());
            assertEquals(cfg, pool.config());
            OcrException missing = assertThrows(OcrException.class, () -> pool.call(null));
            assertTrue(missing.getMessage().contains("task is null"), missing.getMessage());

            ExecutorService gang = Executors.newFixedThreadPool(2);
            try {
                List<Callable<OcrResult>> tasks = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    tasks.add(() -> pool.recognize(image));
                }
                for (Future<OcrResult> future : gang.invokeAll(tasks)) {
                    assertSamePage(alone, join(future));
                }
            } finally {
                gang.shutdownNow();
            }
            assertSamePage(aloneLine, pool.recognizeLine(image));

            OcrException boom = assertThrows(OcrException.class,
                    () -> pool.call(engine -> {
                        throw new OcrException("boom");
                    }));
            assertEquals("boom", boom.getMessage());
            assertSamePage(alone, pool.recognize(image));

            OcrException shut = assertThrows(OcrException.class,
                    () -> pool.call(engine -> {
                        engine.close();
                        return null;
                    }));
            assertTrue(shut.getMessage().contains("borrowed engine was closed"), shut.getMessage());
            assertEquals(1, pool.size());
            assertSamePage(alone, pool.recognize(image));

            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicLong releasedAt = new AtomicLong();
            AtomicLong closedAt = new AtomicLong();
            AtomicReference<Throwable> workerError = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    pool.call(engine -> {
                        entered.countDown();
                        try {
                            if (!release.await(30, TimeUnit.SECONDS)) {
                                throw new OcrException("timed out waiting for an engine");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new OcrException("interrupted", e);
                        }
                        releasedAt.set(System.nanoTime());
                        return null;
                    });
                } catch (Throwable error) {
                    workerError.set(error);
                }
            });
            worker.start();
            assertTrue(entered.await(30, TimeUnit.SECONDS));
            Thread closer = new Thread(() -> {
                pool.close();
                closedAt.set(System.nanoTime());
            });
            closer.start();
            release.countDown();
            worker.join(30_000);
            closer.join(30_000);
            assertFalse(worker.isAlive());
            assertFalse(closer.isAlive());
            assertEquals(null, workerError.get());
            assertTrue(releasedAt.get() > 0L);
            assertTrue(closedAt.get() >= releasedAt.get());
            assertEquals(0, pool.size());
            OcrException after = assertThrows(OcrException.class, () -> pool.recognize(image));
            assertTrue(after.getMessage().contains("engine pool is closed"), after.getMessage());
            pool.close();
        }

        try (OcrPool lone = OcrPool.open(cfg, 1)) {
            OcrException shut = assertThrows(OcrException.class, () -> lone.call(engine -> {
                engine.close();
                return null;
            }));
            assertTrue(shut.getMessage().contains("borrowed engine was closed"), shut.getMessage());
            assertEquals(0, lone.size());
            OcrException none = assertThrows(OcrException.class, () -> lone.recognize(image));
            assertTrue(none.getMessage().contains("engine pool has no engines"), none.getMessage());
        }
    }

    private static OcrResult join(Future<OcrResult> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static void assertSamePage(OcrResult expected, OcrResult actual) {
        assertEquals(expected.text(), actual.text());
        assertEquals(expected.lines().size(), actual.lines().size());
        for (int i = 0; i < expected.lines().size(); i++) {
            assertEquals(expected.lines().get(i).text(), actual.lines().get(i).text());
            assertEquals(expected.lines().get(i).bounds(), actual.lines().get(i).bounds());
        }
        assertEquals(expected.tables(), actual.tables());
    }

    private static boolean modelsPresent() {
        return Files.exists(Path.of("models", ModelFiles.DET))
                && Files.exists(Path.of("models", ModelFiles.REC))
                && Files.exists(Path.of("models", ModelFiles.CLS))
                && Files.exists(Path.of("models", ModelFiles.DICT));
    }
}
