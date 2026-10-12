package org.krzywanski.ocr;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Several independent engines so callers on different threads can recognize at the same time.
 * <p>
 * Each slot is a full {@link OcrEngine} built from the same config, including the same thread
 * count, so a page reads like a lone engine. A call waits when every slot is busy. One PDF stays
 * on the engine that picked it up, including that engine's page window.
 * <p>
 * Do not close the engine handed to {@link #call(Function)}. That slot is removed. Do not call
 * this pool again from inside {@code call} when every slot could already be busy: the inner call
 * waits up to ten minutes and then fails.
 * {@link #close()} waits until borrowed engines come back, then closes them.
 */
public final class OcrPool implements AutoCloseable {
    /** Same bound as a thread waiting for a recognizer session inside one engine. */
    private static final long BORROW_MINUTES = 10;

    private final OcrConfig config;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private final Deque<OcrEngine> free = new ArrayDeque<>();
    private int busy;
    private boolean closed;

    private OcrPool(OcrConfig config, int size) {
        this.config = config;
        List<OcrEngine> created = new ArrayList<>(size);
        try {
            for (int i = 0; i < size; i++) {
                created.add(OcrEngine.create(config));
            }
        } catch (RuntimeException e) {
            for (OcrEngine engine : created) {
                engine.close();
            }
            throw e;
        }
        free.addAll(created);
    }

    /**
     * @param size how many calls may run at once; each one loads its own models
     */
    public static OcrPool open(OcrConfig config, int size) {
        if (config == null) {
            throw new OcrException("config is null");
        }
        if (size < 1) {
            throw new OcrException("pool size must be >= 1");
        }
        return new OcrPool(config, size);
    }

    /** Latin models from {@code modelDirectory}, one engine per slot. */
    public static OcrPool open(Path modelDirectory, int size) {
        if (modelDirectory == null) {
            throw new OcrException("model directory is null");
        }
        return open(OcrConfig.latin(modelDirectory), size);
    }

    public OcrConfig config() {
        return config;
    }

    /** Engines that can still be borrowed. A slot whose engine the caller closed is gone. */
    public int size() {
        lock.lock();
        try {
            return free.size() + busy;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code task} on a borrowed engine. The engine is only for this call.
     * The slot returns to the pool afterwards unless the task closed it.
     */
    public <T> T call(Function<OcrEngine, T> task) {
        if (task == null) {
            throw new OcrException("task is null");
        }
        OcrEngine engine = borrow();
        try {
            T value = task.apply(engine);
            if (engine.isClosed()) {
                throw new OcrException("borrowed engine was closed");
            }
            return value;
        } finally {
            release(engine);
        }
    }

    public OcrResult recognize(BufferedImage image) {
        return call(engine -> engine.recognize(image));
    }

    /** Skips detection and treats the whole bitmap as one text line. */
    public OcrResult recognizeLine(BufferedImage image) {
        return call(engine -> engine.recognizeLine(image));
    }

    public List<OcrResult> recognizePdf(Path pdf, float dpi) {
        return call(engine -> engine.recognizePdf(pdf, dpi));
    }

    /**
     * Same as {@link #recognizePdf(Path, float)}. {@code handler} runs on the borrowing thread,
     * in page order, as {@link OcrEngine#recognizePdf(Path, float, OcrEngine.PageHandler)} does.
     */
    public List<OcrResult> recognizePdf(Path pdf, float dpi, OcrEngine.PageHandler handler) {
        return call(engine -> engine.recognizePdf(pdf, dpi, handler));
    }

    /**
     * Stops lending. In-flight calls finish and return their engines first.
     * A second close does nothing.
     */
    @Override
    public void close() {
        boolean interrupted = false;
        List<OcrEngine> owned;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            while (busy > 0) {
                try {
                    ready.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            owned = new ArrayList<>(free);
            free.clear();
        } finally {
            lock.unlock();
        }
        RuntimeException failure = null;
        for (OcrEngine engine : owned) {
            try {
                engine.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private OcrEngine borrow() {
        lock.lock();
        try {
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(BORROW_MINUTES);
            while (free.isEmpty() && !closed) {
                if (busy == 0) {
                    throw new OcrException("engine pool has no engines");
                }
                long left = deadline - System.nanoTime();
                if (left <= 0L) {
                    throw new OcrException("timed out waiting for an engine");
                }
                if (!ready.await(left, TimeUnit.NANOSECONDS)) {
                    continue;
                }
            }
            if (closed) {
                throw new OcrException("engine pool is closed");
            }
            busy++;
            return free.removeFirst();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("interrupted", e);
        } finally {
            lock.unlock();
        }
    }

    /** An engine the caller already closed stays out. Closing the pool still waits for this slot. */
    private void release(OcrEngine engine) {
        lock.lock();
        try {
            if (!engine.isClosed()) {
                free.addLast(engine);
            }
            busy--;
            ready.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
