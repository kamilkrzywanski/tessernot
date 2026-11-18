package io.github.kamilkrzywanski.ocr;

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
import java.util.function.IntFunction;

/**
 * Several engines so callers on different threads can recognize at the same time.
 * <p>
 * The engines share what is loaded once and never changes: the weights, the charset and the word
 * lists. Each has its own sessions and worker threads. The thread count of the config is the
 * budget of the whole pool: every engine gets an equal share of it, at least one thread, so a
 * pool of four on twelve processors runs twelve threads when all four are busy, not forty-eight.
 * The text is the same as with a lone engine; a single call is slower than on an engine that has
 * every thread to itself. A call waits when every slot is busy. One PDF stays on the engine that
 * picked it up, including that engine's page window.
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
        int share = Math.max(1, config.threads() / size);
        this.config = share == config.threads() ? config : config.toBuilder().threads(share).build();
        ModelSet models = ModelSet.load(this.config);
        List<OcrEngine> created = new ArrayList<>(size);
        try {
            for (int i = 0; i < size; i++) {
                created.add(OcrEngine.create(this.config, models));
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
     * Loads the models once and creates {@code size} engines over them.
     *
     * @param config its thread count is shared out between the engines, see {@link #config()}
     * @param size how many calls may run at once
     * @return the pool; close it when done
     * @throws OcrException when {@code config} is null, {@code size} is below 1 or a model does not load
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

    /**
     * Default models from {@code tessernot-models} on the class path, no corrections, one engine per
     * slot, and the processors of the machine shared out between them.
     *
     * @param size how many calls may run at once
     * @return the pool; close it when done
     * @throws OcrException when {@code size} is below 1 or the models are not on the class path
     */
    public static OcrPool open(int size) {
        return open(OcrConfig.defaults(), size);
    }

    /**
     * Default models from {@code modelDirectory}, no corrections, one engine per slot.
     *
     * @param modelDirectory the directory with the files named in {@link ModelFiles}
     * @param size how many calls may run at once
     * @return the pool; close it when done
     * @throws OcrException when the directory is null, {@code size} is below 1 or a model does not load
     */
    public static OcrPool open(Path modelDirectory, int size) {
        if (modelDirectory == null) {
            throw new OcrException("model directory is null");
        }
        return open(OcrConfig.defaults(modelDirectory), size);
    }

    /**
     * The configuration every engine of this pool runs with: the one the pool was opened with,
     * with its thread count divided by the pool size.
     *
     * @return the configuration of the engines
     */
    public OcrConfig config() {
        return config;
    }

    /** {@return the engines that can still be borrowed; a slot whose engine the caller closed is gone} */
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
     *
     * @param <T> what the task returns
     * @param task the work; it must not keep the engine after it returns
     * @return the value of the task
     * @throws OcrException when {@code task} is null, the pool is closed or has no engine left,
     *         no engine comes free in ten minutes, or the task closed its engine
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

    /**
     * Reads one page on a borrowed engine, see {@link OcrEngine#recognize(BufferedImage)}.
     *
     * @param image the page; it is not changed
     * @return the text, the lines and the tables of the page
     * @throws OcrException as {@link #call(Function)} and the engine do
     */
    public OcrResult recognize(BufferedImage image) {
        return call(engine -> engine.recognize(image));
    }

    /**
     * Skips detection and treats the whole bitmap as one text line.
     *
     * @param image a crop of one line; it is not changed
     * @return a result with that line, or with no line when nothing was read
     * @throws OcrException as {@link #call(Function)} and the engine do
     */
    public OcrResult recognizeLine(BufferedImage image) {
        return call(engine -> engine.recognizeLine(image));
    }

    /**
     * Same as {@link OcrEngine#recognizePages(int, java.util.function.IntFunction, OcrEngine.PageHandler)}
     * on one borrowed engine. {@code handler} runs on the borrowing thread, in page order.
     *
     * @param pageCount the number of pages, 0 or more
     * @param pages renders the page of an index from 0
     * @param handler told about each page as soon as it is read, or null
     * @return the results in page order
     * @throws OcrException as {@link #call(Function)} and the engine do
     */
    public List<OcrResult> recognizePages(int pageCount, IntFunction<BufferedImage> pages, OcrEngine.PageHandler handler) {
        return call(engine -> engine.recognizePages(pageCount, pages, handler));
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
