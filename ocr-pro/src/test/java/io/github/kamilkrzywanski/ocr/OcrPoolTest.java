package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.eval.Samples;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    void enginesShareTheWeightsAndTheThreadBudget() {
        assumeTrue(modelsPresent(), "models/ is missing");
        OcrConfig six = OcrConfig.defaults(Path.of("models")).toBuilder().threads(6).build();
        try (OcrPool pool = OcrPool.open(six, 2)) {
            assertEquals(3, pool.config().threads());
            assertEquals(six.detLimitSideLen(), pool.config().detLimitSideLen());
            // Two engines at once: the inner call takes the second slot.
            Boolean same = pool.call(first -> pool.call(second -> {
                assertTrue(first != second);
                assertEquals(3, first.config().threads());
                return first.recognitionGraph() == second.recognitionGraph();
            }));
            assertEquals(Boolean.TRUE, same);
        }
        try (OcrPool pool = OcrPool.open(six, 4)) {
            assertEquals(1, pool.config().threads());
        }
        OcrConfig two = six.toBuilder().threads(2).build();
        try (OcrPool pool = OcrPool.open(two, 4)) {
            assertEquals(1, pool.config().threads(), "every engine keeps at least one thread");
            assertEquals(4, pool.size());
        }
        try (OcrPool pool = OcrPool.open(two, 1)) {
            assertTrue(two == pool.config(), "a pool of one keeps the configuration as given");
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void parallelCallsMatchASingleEngine() throws Exception {
        assumeTrue(modelsPresent(), "models/ is missing");
        OcrConfig cfg = OcrConfig.defaults(Path.of("models")).toBuilder().threads(1).build();
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

            // close() must wait for the borrowed engine: the worker holds it until the closer
            // thread is parked inside close(), and reads a line on it after that.
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<OcrResult> lateRead = new AtomicReference<>();
            AtomicReference<Throwable> workerError = new AtomicReference<>();
            AtomicBoolean closeReturned = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                try {
                    pool.call(engine -> {
                        entered.countDown();
                        try {
                            if (!release.await(60, TimeUnit.SECONDS)) {
                                throw new OcrException("the test never released the engine");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new OcrException("interrupted", e);
                        }
                        lateRead.set(engine.recognizeLine(image));
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
                closeReturned.set(true);
            });
            closer.start();
            // Parked on the pool's condition, or gone if close() did not wait.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (closer.getState() != Thread.State.WAITING && closer.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(Thread.State.WAITING, closer.getState(), "close() waits while an engine is borrowed");
            assertFalse(closeReturned.get());
            assertEquals(1, pool.size(), "the borrowed engine is still counted");
            OcrException closing = assertThrows(OcrException.class, () -> pool.recognize(image));
            assertTrue(closing.getMessage().contains("engine pool is closed"), closing.getMessage());
            release.countDown();
            worker.join(60_000);
            closer.join(60_000);
            assertFalse(worker.isAlive());
            assertFalse(closer.isAlive());
            assertEquals(null, workerError.get());
            assertTrue(closeReturned.get());
            assertSamePage(aloneLine, lateRead.get());
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
