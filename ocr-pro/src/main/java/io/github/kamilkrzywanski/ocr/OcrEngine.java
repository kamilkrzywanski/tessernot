package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.image.Bgr;
import io.github.kamilkrzywanski.ocr.internal.image.Geometry;
import io.github.kamilkrzywanski.ocr.internal.image.Images;
import io.github.kamilkrzywanski.ocr.internal.post.Charset;
import io.github.kamilkrzywanski.ocr.internal.post.Ctc;
import io.github.kamilkrzywanski.ocr.internal.post.DbPost;
import io.github.kamilkrzywanski.ocr.internal.post.InkGutters;
import io.github.kamilkrzywanski.ocr.internal.post.ReadingOrder;
import io.github.kamilkrzywanski.ocr.internal.post.RunnerUps;
import io.github.kamilkrzywanski.ocr.internal.post.Tables;
import io.github.kamilkrzywanski.ocr.internal.post.UnruledTables;
import io.github.kamilkrzywanski.ocr.internal.post.Vocabulary;
import io.github.kamilkrzywanski.ocr.internal.runtime.Graph;
import io.github.kamilkrzywanski.ocr.internal.runtime.Session;
import io.github.kamilkrzywanski.ocr.internal.runtime.Sig;
import io.github.kamilkrzywanski.ocr.internal.runtime.Tensor;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * One engine is not safe for concurrent {@link #recognize} calls; the methods are synchronized.
 * Concurrent callers use {@link OcrPool}, which lends each of them a separate engine.
 * <p>
 * Detection may split GEMM across {@code pool} on the calling thread. Page workers therefore use
 * forked sessions with no pool, made for the document and shared by its pages, and run lines
 * inline, so they never submit back to {@code pool}.
 * A multi-page PDF keeps at most {@code threads} bitmaps in flight. One page still detects on {@code pool}.
 * Do not call {@link #recognize} from an {@code ocr-worker} thread.
 */
public final class OcrEngine implements AutoCloseable {
    /**
     * Heap one detection takes per pixel of the scaled page: about 190 bytes of activations
     * (measured on 640 to 2048 px pages, see {@code RuntimeTest}), 12 of input and the rest for the
     * probability map and its copies.
     */
    static final long DET_BYTES_PER_PIXEL = 224;
    /** Heap one page in flight takes per pixel: the bitmap and its BGR copy. */
    private static final long PAGE_BYTES_PER_PIXEL = 8;
    /** Heap the recognizer and the classifier of one page worker take: what a session keeps, and a line at work. */
    private static final long PAGE_SESSION_BYTES = Session.RETAIN_BYTES + (32L << 20);
    /** What each session keeps of its activation buffers once a call has returned. */
    private static final long IDLE_BYTES = 8L << 20;

    private final OcrConfig cfg;
    /** {@link Corrections#POLISH}: CTC runner-ups, word lists, and the second reads in {@link #decode}. */
    /** The second reads of {@link Corrections#POLISH}; null without corrections. */
    private final PolishRules polish;
    private final ExecutorService pool;
    private Session det;
    private Session rec;
    private Session cls;
    private Charset charset;
    /** The runner-up rules of {@link Corrections#POLISH} for this charset; null without corrections. */
    private RunnerUps runnerUps;
    /** Lists for the CTC word-list search: the user's, plus the Polish profile's own. Null when there is none. */
    private Vocabulary words;
    /**
     * Detection for page workers: {@code threads} forks that live for one multi-page document. A
     * fork's arena holds the buffers of a whole detection map, so idle engines do not keep them.
     */
    private LinkedBlockingDeque<Session> detPool;
    /**
     * Sessions are taken from the front and returned to the front, so a few busy workers keep
     * using the same few sessions and the others stay empty.
     */
    private LinkedBlockingDeque<Session> recPool;
    private LinkedBlockingDeque<Session> clsPool;
    private String detInput;
    private String recInput;
    private String clsInput;
    private int clsHeight = 80;
    private int clsWidth = 160;
    private boolean closed;

    private OcrEngine(OcrConfig cfg, ModelSet models) {
        this.cfg = cfg;
        this.polish = cfg.corrections() == Corrections.POLISH
                ? new PolishRules(cfg.recThresh(), cfg.rotateVertical(), models.lexicon != null, models.postal)
                : null;
        ExecutorService workers = cfg.threads() > 1
                ? Executors.newFixedThreadPool(cfg.threads(), task -> {
                    Thread thread = new Thread(task, "ocr-worker");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
        this.pool = workers;
        try {
            openSessions(models);
        } catch (RuntimeException e) {
            if (workers != null) {
                workers.shutdownNow();
            }
            throw e;
        }
    }

    /** Sessions over the graphs of {@code models}: the weights are shared, the activation buffers are this engine's. */
    private void openSessions(ModelSet models) {
        if (models.det != null) {
            this.det = Session.create(models.det, new Session.Options(
                    cfg.useVector(), cfg.threads(), pool, cfg.quantize(), false, true));
            this.detInput = det.inputName();
        }
        if (models.rec != null) {
            this.rec = Session.create(models.rec, lineOptions());
            this.recInput = rec.inputName();
            this.charset = models.charset;
            this.runnerUps = models.runnerUps;
            this.words = models.words;
            int classes = staticDim(rec.outputSig(), rec.outputSig().dims == null ? -1 : rec.outputSig().dims.length - 1);
            if (classes > 0 && classes != charset.size()) {
                throw new OcrException("REC classes " + classes + " != charset " + charset.size());
            }
            int modelHeight = -1;
            for (Sig sig : rec.graph().inputs) {
                if (recInput.equals(sig.name)) {
                    modelHeight = staticDim(sig, 2);
                }
            }
            if (modelHeight > 0 && modelHeight != cfg.recHeight()) {
                throw new OcrException("REC height " + cfg.recHeight() + " != model input " + modelHeight);
            }
            this.recPool = pool == null ? null : sessions(rec);
        }
        if (models.cls != null) {
            this.cls = Session.create(models.cls, lineOptions());
            this.clsInput = cls.inputName();
            for (Sig sig : cls.graph().inputs) {
                if (clsInput.equals(sig.name) && sig.dims != null && sig.dims.length >= 2) {
                    int h = sig.dims[sig.dims.length - 2];
                    int w = sig.dims[sig.dims.length - 1];
                    if (h > 0) {
                        this.clsHeight = h;
                    }
                    if (w > 0) {
                        this.clsWidth = w;
                    }
                }
            }
            this.clsPool = pool == null ? null : sessions(cls);
        }
    }

    /**
     * An engine with the default models from {@code ocr-pro-models} on the class path and the
     * settings of {@link OcrConfig#defaults()}.
     *
     * @return the engine; close it when done
     * @throws OcrException when the models are not on the class path or do not load
     */
    public static OcrEngine load() {
        return create(OcrConfig.defaults());
    }

    /**
     * An engine with the default models from a directory and the settings of
     * {@link OcrConfig#defaults(Path)}.
     *
     * @param modelDirectory the directory with the files named in {@link ModelFiles}
     * @return the engine; close it when done
     * @throws OcrException when the directory is null or a model is missing or does not load
     */
    public static OcrEngine load(Path modelDirectory) {
        if (modelDirectory == null) {
            throw new OcrException("model directory is null");
        }
        return create(OcrConfig.defaults(modelDirectory));
    }

    /**
     * Loads the models of {@code config} and starts the worker threads. This is the slow step:
     * keep the engine and reuse it for every page.
     *
     * @param config the settings and the models
     * @return the engine; close it when done
     * @throws OcrException when {@code config} is null or a model is missing or does not load
     */
    public static OcrEngine create(OcrConfig config) {
        if (config == null) {
            throw new OcrException("config is null");
        }
        return new OcrEngine(config, ModelSet.load(config));
    }

    /** An engine over models that are already loaded: the engines of a pool share one {@link ModelSet}. */
    static OcrEngine create(OcrConfig config, ModelSet models) {
        return new OcrEngine(config, models);
    }

    /** {@return the configuration this engine was created with} */
    public OcrConfig config() {
        return cfg;
    }

    /**
     * Reads one page: detects its lines, reads each, and puts them in reading order with the
     * ruled tables of the page. One call runs at a time on an engine; a second caller waits. For
     * calls side by side use an {@link OcrPool}.
     *
     * @param image the page; it is not changed
     * @return the text, the lines and the tables of the page
     * @throws OcrException when {@code image} is null or the engine is closed
     */
    public synchronized OcrResult recognize(BufferedImage image) {
        ensureOpen();
        try {
            return run(image, true);
        } finally {
            trimSessions();
        }
    }

    /**
     * Skips detection and treats the whole bitmap as one text line.
     *
     * @param image a crop of one line; it is not changed
     * @return a result with that line, or with no line when nothing was read
     * @throws OcrException when {@code image} is null or the engine is closed
     */
    public synchronized OcrResult recognizeLine(BufferedImage image) {
        ensureOpen();
        try {
            return run(image, true, false);
        } finally {
            trimSessions();
        }
    }

    /**
     * Reads the pages of a document one after another: {@code pages} renders page {@code index}
     * and is called once per page, in page order, on the calling thread. With more than one thread,
     * the next pages are rendered while earlier ones are read. {@code handler} is called in page
     * order as soon as a page is done, before later pages finish; a null handler only collects the
     * list. PDF input is in {@code ocr-pro-pdf}: {@code OcrPdf.recognize(engine, path, dpi)}.
     * <p>
     * At most {@code threads} pages are in flight, and fewer when their bitmaps and recognizers
     * would take more than three tenths of the heap; detection waits for its share of the heap
     * too. A small heap therefore reads a document more slowly instead of running out of memory.
     *
     * @param pageCount the number of pages, 0 or more
     * @param pages renders the page of an index from 0; the bitmap is not changed
     * @param handler told about each page as soon as it is read, or null
     * @return the results in page order
     * @throws OcrException when {@code pageCount} is negative, {@code pages} is null, a page is
     *         null or the engine is closed
     */
    public synchronized List<OcrResult> recognizePages(int pageCount, IntFunction<BufferedImage> pages, PageHandler handler) {
        ensureOpen();
        if (pageCount < 0) {
            throw new OcrException("page count is negative: " + pageCount);
        }
        if (pages == null) {
            throw new OcrException("pages are null");
        }
        try {
            return readPages(pageCount, pages, handler);
        } finally {
            trimSessions();
        }
    }

    private List<OcrResult> readPages(int pageCount, IntFunction<BufferedImage> pages, PageHandler handler) {
        if (pageCount <= 1 || pool == null) {
            List<OcrResult> results = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                OcrResult result = run(pages.apply(i), true);
                results.add(result);
                if (handler != null) {
                    handler.onPage(i, pageCount, result);
                }
            }
            return results;
        }
        int[] index = {0};
        detPool = det == null ? null : forks(det);
        MemoryBudget inFlight = MemoryBudget.pages();
        try {
            return joinWindow(pool, cfg.threads(), pageCount, i -> {
                BufferedImage image = pages.apply(i);
                // Waits here, on the calling thread, until earlier pages have finished and
                // returned their share.
                int held = inFlight.acquire(image == null ? 0L
                        : PAGE_SESSION_BYTES + PAGE_BYTES_PER_PIXEL * image.getWidth() * image.getHeight());
                return () -> {
                    try {
                        return run(image, false);
                    } finally {
                        inFlight.release(held);
                    }
                };
            }, result -> {
                if (handler != null) {
                    handler.onPage(index[0], pageCount, result);
                }
                index[0]++;
            });
        } finally {
            detPool = null;
        }
    }

    /** Called in order while {@link #recognizePages(int, IntFunction, PageHandler)} is still running. */
    @FunctionalInterface
    public interface PageHandler {
        /**
         * One page is read. Runs on the thread that called {@code recognizePages}.
         *
         * @param index the page, from 0
         * @param pageCount the number of pages of the document
         * @param result the page
         */
        void onPage(int index, int pageCount, OcrResult result);
    }

    /**
     * Stops the worker threads and drops the sessions. It waits for a call that is running; a
     * second close does nothing, and every later call fails with {@link OcrException}.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // Drop the graphs after workers have stopped so a closed engine can release the weights.
        det = null;
        rec = null;
        cls = null;
        charset = null;
        runnerUps = null;
        words = null;
        detPool = null;
        recPool = null;
        clsPool = null;
    }

    /**
     * After a call, every session keeps at most {@link #IDLE_BYTES} of its activation buffers: an
     * idle engine holds its weights and little else. A session is taken out of its queue while it
     * is trimmed, so a worker that a failed call left behind cannot run it at the same time.
     */
    private void trimSessions() {
        trim(det);
        trim(recPool, rec);
        trim(clsPool, cls);
    }

    /** The recognition graph with its weights; the engines of one pool hold the same one. */
    synchronized Graph recognitionGraph() {
        return rec == null ? null : rec.graph();
    }

    /** Activation buffers the sessions of this engine hold between calls, in bytes. */
    synchronized long idleBytes() {
        long bytes = det == null ? 0L : det.idleBytes();
        bytes += idle(recPool, rec);
        bytes += idle(clsPool, cls);
        return bytes;
    }

    private static long idle(LinkedBlockingDeque<Session> queue, Session primary) {
        if (queue == null) {
            return primary == null ? 0L : primary.idleBytes();
        }
        long bytes = 0L;
        for (Session session : queue) {
            bytes += session.idleBytes();
        }
        return bytes;
    }

    private static void trim(LinkedBlockingDeque<Session> queue, Session primary) {
        if (queue == null) {
            trim(primary);
            return;
        }
        for (int i = queue.size(); i > 0; i--) {
            Session session = queue.poll();
            if (session == null) {
                return;
            }
            session.trim(IDLE_BYTES);
            queue.offerLast(session);
        }
    }

    private static void trim(Session session) {
        if (session != null) {
            session.trim(IDLE_BYTES);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new OcrException("engine is closed");
        }
    }

    /** {@return true after {@link #close()}; the pool uses this to drop a slot the caller shut down} */
    public synchronized boolean isClosed() {
        return closed;
    }

    private OcrResult run(BufferedImage image, boolean lineParallel) {
        return run(image, lineParallel, cfg.useDet() && det != null);
    }

    private OcrResult run(BufferedImage image, boolean lineParallel, boolean detect) {
        if (image == null) {
            throw new OcrException("image is null");
        }
        long totalStart = System.nanoTime();
        Bgr bgr = Images.toBgr(image);
        if (cfg.deskew()) {
            bgr = Images.deskew(bgr);
        }
        if (cfg.adaptiveThreshold()) {
            bgr = Images.sauvola(bgr);
        }
        long detStart = totalStart;
        int pageWidth = bgr.width();
        int pageHeight = bgr.height();
        List<DbPost.Box> boxes = detect ? detect(bgr, lineParallel) : fullPage(bgr);
        // Quarter turns counter-clockwise given to the page before it is read.
        int turns = 0;
        if (detect && cfg.pageOrientation()) {
            if (PageTurn.sideways(boxes)) {
                bgr = Images.rot90ccw(bgr);
                turns = 1;
                boxes = detect(bgr, lineParallel);
            }
            if (upsideDown(bgr, boxes)) {
                bgr = Images.rot180(bgr);
                turns += 2;
                boxes = detect(bgr, lineParallel);
            }
        }
        // The page turned level, when its lines run at an angle that moves a gutter by more
        // than a line from top to bottom.
        PageTurn.Level level = null;
        if (detect && cfg.pageOrientation()) {
            double skew = PageTurn.skew(boxes);
            if (PageTurn.wanders(skew, bgr.height(), boxes)) {
                level = PageTurn.Level.of(bgr.width(), bgr.height(), skew);
                bgr = Images.turn(bgr, level.cos(), level.sin(), level.levelWidth(), level.levelHeight());
                boxes = detect(bgr, lineParallel);
            }
        }
        long detectionNanos = System.nanoTime() - detStart;
        long recStart = System.nanoTime();
        List<TextLine> recognized = recognizeBoxes(bgr, boxes, lineParallel);
        if (polish != null) {
            recognized = splitAmounts(bgr, recognized);
        }
        boolean columns = cfg.layout() == Layout.AUTO;
        if (columns) {
            recognized = splitColumns(bgr, recognized, lineParallel);
        }
        long recognitionNanos = System.nanoTime() - recStart;
        List<List<TextLine>> rows = ReadingOrder.group(recognized, columns);
        if (cfg.dropFragments()) {
            // After the order is found: a fragment is still ink on the page, and leaving it out
            // of the layout moved column and row bounds on the test set.
            rows = withoutFragments(rows);
        }
        List<TextLine> flat = ReadingOrder.flatten(rows);
        List<TextTable> tables = readTables(bgr, flat);
        List<TextTable> unruled = UnruledTables.find(rows, tables);
        if (!unruled.isEmpty()) {
            tables = new ArrayList<>(tables);
            tables.addAll(unruled);
            tables.sort(Comparator.comparingInt((TextTable table) -> table.bounds().y)
                    .thenComparingInt(table -> table.bounds().x));
        }
        if (turns != 0 || level != null) {
            // Back into the pixels of the picture as it was given: off the levelled canvas
            // first, then out of the quarter turns.
            int quarters = turns;
            PageTurn.Level levelled = level;
            PageTurn.Back back = (x, y) -> {
                float[] p = levelled == null ? new float[] {x, y} : levelled.back(x, y);
                return PageTurn.back(p[0], p[1], quarters, pageWidth, pageHeight);
            };
            List<TextLine> lines = new ArrayList<>(flat.size());
            for (TextLine line : flat) {
                lines.add(PageTurn.back(line, back));
            }
            flat = lines;
            List<TextTable> turned = new ArrayList<>(tables.size());
            for (TextTable table : tables) {
                turned.add(PageTurn.back(table, back));
            }
            tables = turned;
        }
        return new OcrResult(
                ReadingOrder.join(rows),
                flat,
                tables,
                detectionNanos,
                recognitionNanos,
                System.nanoTime() - totalStart,
                PageTurn.rotation(turns));
    }

    /**
     * Whether most of the long lines of the page read upside down to the line classifier. Needs
     * a classifier and at least {@link PageTurn#MIN_VOTERS} long lines. A line votes with the
     * classifier's more likely answer, not with {@link OcrConfig#clsThresh()}: that threshold
     * guards one line against a wrong turn, and on small print the classifier seldom reaches it,
     * while the lines of a page decide together.
     */
    private boolean upsideDown(Bgr page, List<DbPost.Box> boxes) {
        if (cls == null) {
            return false;
        }
        List<DbPost.Box> voters = PageTurn.voters(boxes);
        if (voters.size() < PageTurn.MIN_VOTERS) {
            return false;
        }
        Session session = clsPool == null ? cls : borrow(clsPool);
        try {
            int flipped = 0;
            int votes = 0;
            for (DbPost.Box box : voters) {
                Bgr crop = crop(page, box.quad());
                if (crop != null) {
                    votes++;
                    if (turnProbabilities(PageTurn.window(crop), session)[2] > 0.5f) {
                        flipped++;
                    }
                }
            }
            return votes >= PageTurn.MIN_VOTERS && flipped * 2 > votes;
        } finally {
            if (clsPool != null) {
                clsPool.offerFirst(session);
            }
        }
    }

    /** {@link OcrConfig.Builder#dropFragments}: a fragment is at most this many characters... */
    private static final int FRAGMENT_CHARS = 10;
    /** ...and its mean character probability is below this. */
    private static final float FRAGMENT_SCORE = 0.8f;

    /**
     * Without short, unsure lines. On the dev and test sets these were mostly stamps, CJK
     * characters read from ornaments, and stray marks; the result barely moved between 0.7 and 0.9.
     */
    private static List<List<TextLine>> withoutFragments(List<List<TextLine>> rows) {
        List<List<TextLine>> out = new ArrayList<>(rows.size());
        for (List<TextLine> row : rows) {
            List<TextLine> kept = new ArrayList<>(row.size());
            for (TextLine line : row) {
                if (line.text().strip().length() > FRAGMENT_CHARS || line.score() >= FRAGMENT_SCORE) {
                    kept.add(line);
                }
            }
            if (!kept.isEmpty()) {
                out.add(kept);
            }
        }
        return out;
    }

    /**
     * A box the detector drew over two text columns, where the gutter is narrow, is cut on the
     * gutter and each half is read again. Otherwise the two lines would come out as one line in
     * the middle of the left column.
     */
    private List<TextLine> splitColumns(Bgr page, List<TextLine> lines, boolean lineParallel) {
        if (!cfg.useRec() || rec == null || lines.size() < 2) {
            return lines;
        }
        // Gutters seen in the ink first: they also find lines merged over three columns, where
        // the boxes alone show no gutter. Then the geometric check on what is left.
        List<TextLine> inked = cut(page, lines, InkGutters.cuts(page, lines), lineParallel);
        Map<TextLine, float[]> geometric = new IdentityHashMap<>();
        for (Map.Entry<TextLine, Float> cut : ReadingOrder.cuts(inked).entrySet()) {
            geometric.put(cut.getKey(), new float[]{cut.getValue()});
        }
        return cut(page, inked, geometric, lineParallel);
    }

    /**
     * Fraction of a cut line's characters its pieces must read back. Below it the cut lost text,
     * as on a false gutter, and the line stays whole. A lone dash or dot at a real gutter that
     * does not read costs less than that, and the pieces still win.
     */
    static final float CUT_KEEP = 0.7f;

    /** True when the pieces read from a cut line keep too little of its text; null is a piece that did not read. */
    static boolean keepWhole(String original, List<String> pieces) {
        int before = inkChars(original);
        int after = 0;
        for (String piece : pieces) {
            if (piece != null) {
                after += inkChars(piece);
            }
        }
        return after < before * CUT_KEEP;
    }

    private static int inkChars(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                n++;
            }
        }
        return n;
    }

    /**
     * Each line in {@code cuts} is cut at the given fractions of its length and its pieces read
     * again. A piece that does not read is dropped, as a line is. The line stays whole instead
     * when the pieces keep less than {@link #CUT_KEEP} of its characters, see {@link #keepWhole}.
     */
    private List<TextLine> cut(Bgr page, List<TextLine> lines, Map<TextLine, float[]> cuts, boolean lineParallel) {
        if (cuts.isEmpty()) {
            return lines;
        }
        List<DbPost.Box> pieces = new ArrayList<>(cuts.size() * 3);
        List<TextLine> whole = new ArrayList<>(cuts.size());
        List<TextLine> out = new ArrayList<>(lines.size() + cuts.size() * 2);
        for (TextLine line : lines) {
            float[] ts = cuts.get(line);
            if (ts == null) {
                out.add(line);
                continue;
            }
            whole.add(line);
            float[] q = line.quad();
            float prevTopX = q[0];
            float prevTopY = q[1];
            float prevBottomX = q[6];
            float prevBottomY = q[7];
            for (int i = 0; i <= ts.length; i++) {
                float t = i < ts.length ? ts[i] : 1f;
                float topX = q[0] + (q[2] - q[0]) * t;
                float topY = q[1] + (q[3] - q[1]) * t;
                float bottomX = q[6] + (q[4] - q[6]) * t;
                float bottomY = q[7] + (q[5] - q[7]) * t;
                pieces.add(new DbPost.Box(new float[]{prevTopX, prevTopY, topX, topY, bottomX, bottomY, prevBottomX, prevBottomY},
                        line.detScore()));
                prevTopX = topX;
                prevTopY = topY;
                prevBottomX = bottomX;
                prevBottomY = bottomY;
            }
        }
        List<TextLine> read = recognizeEach(page, pieces, lineParallel);
        int at = 0;
        for (TextLine line : whole) {
            int count = cuts.get(line).length + 1;
            List<TextLine> own = read.subList(at, at + count);
            at += count;
            List<String> texts = new ArrayList<>(own.size());
            for (TextLine piece : own) {
                texts.add(piece == null ? null : piece.text());
            }
            if (keepWhole(line.text(), texts)) {
                out.add(line);
                continue;
            }
            for (TextLine piece : own) {
                if (piece != null) {
                    out.add(piece);
                }
            }
        }
        return out;
    }

    /**
     * Borrows one recognizer after line recognition has already returned its session.
     * A ruled box is sliced and read again; the joined page text keeps the original line.
     */
    private List<TextTable> readTables(Bgr page, List<TextLine> lines) {
        if (!cfg.useRec() || rec == null || charset == null) {
            return Tables.find(page, lines, null);
        }
        boolean borrowed = recPool != null;
        Session session = borrowed ? borrow(recPool) : rec;
        try {
            return Tables.find(page, lines, crop -> readSlice(session, crop));
        } finally {
            if (borrowed) {
                recPool.offerFirst(session);
            }
        }
    }

    private String readSlice(Session session, Bgr crop) {
        if (crop.width() < 2 || crop.height() < 2) {
            return "";
        }
        float[] data = Images.recTensor(crop, cfg.recHeight(), cfg.recMinWidth(), cfg.recMaxWidth(), cfg.mean(), cfg.std());
        int width = data.length / (3 * cfg.recHeight());
        Tensor input = Tensor.f32(new int[]{1, 3, cfg.recHeight(), width}, data);
        Tensor output = only(session.run(Map.of(recInput, input)));
        Ctc.Result result = Ctc.greedy(output, charset, runnerUps, words);
        if (result.text().isEmpty() || result.score() < cfg.recThresh()) {
            return "";
        }
        // The piece is not the line. The word-list search the line already had runs in the
        // decoder here too; the joined page text is unchanged.
        return result.text();
    }

    /**
     * Detection holds its share of {@link MemoryBudget#DETECTION} from the scaled input to the
     * boxes. Pages of one document, and the engines of a pool, detect side by side only as far as
     * the heap allows; the rest wait here.
     */
    private List<DbPost.Box> detect(Bgr bgr, boolean lineParallel) {
        int[] size = Images.detSize(bgr.width(), bgr.height(), cfg.detLimitSideLen(), cfg.detLimitType());
        int held = MemoryBudget.DETECTION.acquire(DET_BYTES_PER_PIXEL * size[0] * size[1]);
        try {
            return detect(bgr, size, lineParallel);
        } finally {
            MemoryBudget.DETECTION.release(held);
        }
    }

    private List<DbPost.Box> detect(Bgr bgr, int[] size, boolean lineParallel) {
        // Page workers already occupy pool, so they keep this resize on their own thread.
        float[] input = Images.resizeNormalize(bgr, size[0], size[1], cfg.mean(), cfg.std(),
                lineParallel ? pool : null, cfg.threads());
        Tensor tensor = Tensor.f32(new int[]{1, 3, size[1], size[0]}, input);
        // A page worker borrows a fork from the queue of its document. The field is read once, so
        // a worker left behind by an interrupted join returns its fork to the queue it took it
        // from after the document has dropped that queue. One that arrives after the drop takes
        // a fresh fork and never the primary, which runs on the pool the worker belongs to.
        LinkedBlockingDeque<Session> forks = lineParallel ? null : detPool;
        Session session = lineParallel ? det : forks != null ? borrow(forks) : det.fork();
        Tensor output;
        try {
            output = only(session.run(Map.of(detInput, tensor)));
        } finally {
            if (forks != null) {
                forks.offerFirst(session);
            }
        }
        Planar map = planar(output);
        DbPost.asProbability(map.data);
        List<DbPost.Box> found = DbPost.find(
                map.data, map.width, map.height,
                cfg.detThresh(), cfg.boxThresh(), cfg.unclipRatio(), cfg.useDilation(), cfg.maxCandidates());
        float scaleX = bgr.width() / (float) map.width;
        float scaleY = bgr.height() / (float) map.height;
        List<DbPost.Box> scaled = new ArrayList<>(found.size());
        for (DbPost.Box box : found) {
            float[] quad = scaleClip(box.quad(), scaleX, scaleY, bgr.width(), bgr.height());
            if (quad != null) {
                scaled.add(new DbPost.Box(quad, box.score()));
            }
        }
        return scaled;
    }

    /** The lines that read, in box order. */
    private List<TextLine> recognizeBoxes(Bgr page, List<DbPost.Box> boxes, boolean lineParallel) {
        List<TextLine> lines = new ArrayList<>(boxes.size());
        for (TextLine line : recognizeEach(page, boxes, lineParallel)) {
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** One entry per box, in order: its line, or null when the box did not read. */
    private List<TextLine> recognizeEach(Bgr page, List<DbPost.Box> boxes, boolean lineParallel) {
        if (!cfg.useRec() || rec == null) {
            List<TextLine> lines = new ArrayList<>(boxes.size());
            for (DbPost.Box box : boxes) {
                lines.add(textLine(box, "", 0f));
            }
            return lines;
        }
        if (lineParallel && pool != null && boxes.size() > 1) {
            List<Callable<TextLine>> tasks = new ArrayList<>(boxes.size());
            for (DbPost.Box box : boxes) {
                tasks.add(() -> {
                    Session recSession = borrow(recPool);
                    Session clsSession = clsPool == null ? null : borrow(clsPool);
                    try {
                        return decode(page, box, recSession, clsSession);
                    } finally {
                        if (clsSession != null) {
                            clsPool.offerFirst(clsSession);
                        }
                        recPool.offerFirst(recSession);
                    }
                });
            }
            return awaitTasks(tasks);
        }
        if (!lineParallel && recPool != null) {
            Session recSession = borrow(recPool);
            Session clsSession = clsPool == null ? null : borrow(clsPool);
            try {
                return sequential(page, boxes, recSession, clsSession);
            } finally {
                if (clsSession != null) {
                    clsPool.offerFirst(clsSession);
                }
                recPool.offerFirst(recSession);
            }
        }
        return sequential(page, boxes, rec, cls);
    }

    private List<TextLine> sequential(Bgr page, List<DbPost.Box> boxes, Session recSession, Session clsSession) {
        List<TextLine> lines = new ArrayList<>(boxes.size());
        for (DbPost.Box box : boxes) {
            lines.add(decode(page, box, recSession, clsSession));
        }
        return lines;
    }

    /**
     * An amount and a VAT rate that one box holds become two lines, see {@link PolishRules#splitAmounts}.
     * The second reads borrow one recognizer after line recognition has returned its sessions.
     */
    private List<TextLine> splitAmounts(Bgr page, List<TextLine> lines) {
        if (!cfg.useRec() || rec == null || !PolishRules.anyAmount(lines)) {
            return lines;
        }
        boolean borrowed = recPool != null;
        Session session = borrowed ? borrow(recPool) : rec;
        try {
            return polish.splitAmounts(lines, reader(page, session, null));
        } finally {
            if (borrowed) {
                recPool.offerFirst(session);
            }
        }
    }

    /** What the rules of {@link Corrections#POLISH} may ask for: this page, read with the sessions of one worker. */
    private PolishRules.LineReader reader(Bgr page, Session recSession, Session clsSession) {
        return new PolishRules.LineReader() {
            @Override
            public Ctc.Result read(Bgr crop) {
                return recognizeCrop(crop, recSession);
            }

            @Override
            public Bgr upright(Bgr crop) {
                return clsSession == null ? crop : orient(crop, clsSession);
            }

            @Override
            public Bgr crop(float[] quad) {
                return OcrEngine.this.crop(page, quad);
            }
        };
    }

    private TextLine decode(Bgr page, DbPost.Box box, Session recSession, Session clsSession) {
        Bgr crop = crop(page, box.quad());
        if (crop == null) {
            return null;
        }
        // Quarter turns counter-clockwise the crop is given before it is read.
        int turns = 0;
        if (cfg.rotateVertical() && crop.height() >= crop.width() * 1.5f) {
            crop = Images.rot90ccw(crop);
            turns = 1;
        }
        if (clsSession != null) {
            int more = quarterTurns(crop, clsSession);
            crop = turned(crop, more);
            turns = (turns + more) & 3;
        }
        if (polish != null) {
            // A neighbor line split off by a tall white gap is dropped before the resize.
            crop = Images.mainInkBand(crop);
        }
        Ctc.Result result = recognizeCrop(crop, recSession);
        if (result.text().isEmpty() || result.score() < cfg.recThresh()) {
            return null;
        }
        if (polish != null) {
            result = polish.reread(crop, box, result, reader(page, recSession, clsSession));
        }
        TextLine line = textLine(box, result.text(), result.score());
        List<TextWord> words = Words.of(result, box.quad(), turns);
        return words.isEmpty() ? line
                : new TextLine(line.text(), line.score(), line.detScore(), line.quad(), line.bounds(), words);
    }


    private Ctc.Result recognizeCrop(Bgr crop, Session recSession) {
        float[] data = Images.recTensor(crop, cfg.recHeight(), cfg.recMinWidth(), cfg.recMaxWidth(), cfg.mean(), cfg.std());
        int width = data.length / (3 * cfg.recHeight());
        Tensor input = Tensor.f32(new int[]{1, 3, cfg.recHeight(), width}, data);
        Tensor output = only(recSession.run(Map.of(recInput, input)));
        // The timesteps span the padded input; the crop fills its left part.
        float filled = Images.recWidth(crop, cfg.recHeight(), cfg.recMaxWidth()) / (float) width;
        return Ctc.greedy(output, charset, runnerUps, words).scaled(1f / filled);
    }

    private Bgr orient(Bgr crop, Session clsSession) {
        return turned(crop, quarterTurns(crop, clsSession));
    }

    /** The crop after that many quarter turns counter-clockwise. */
    private static Bgr turned(Bgr crop, int quarterTurns) {
        return switch (quarterTurns & 3) {
            case 1 -> Images.rot90ccw(crop);
            case 2 -> Images.rot180(crop);
            case 3 -> Images.rot90cw(crop);
            default -> crop;
        };
    }

    /** What the classifier says the crop needs to read upright: 0, or 1 to 3 quarter turns counter-clockwise. */
    private int quarterTurns(Bgr crop, Session clsSession) {
        float[] turns = turnProbabilities(crop, clsSession);
        int arg = 0;
        for (int i = 1; i < 4; i++) {
            if (turns[i] > turns[arg]) {
                arg = i;
            }
        }
        // A classifier of two answers turns a line only when it is sure.
        return turns[4] == 2f && arg == 2 && turns[2] <= cfg.clsThresh() ? 0 : arg;
    }

    /**
     * The classifier's probability that the crop needs 0, 1, 2 or 3 quarter turns
     * counter-clockwise, and in the fifth place how many answers the classifier has.
     */
    private float[] turnProbabilities(Bgr crop, Session clsSession) {
        float[] data = Images.clsTensor(crop, clsHeight, clsWidth, cfg.mean(), cfg.std());
        Tensor input = Tensor.f32(new int[]{1, 3, clsHeight, clsWidth}, data);
        Tensor output = only(clsSession.run(Map.of(clsInput, input)));
        int classes = output.dims[output.dims.length - 1];
        float[] row = new float[classes];
        int offset = output.f.length - classes;
        System.arraycopy(output.f, Math.max(0, offset), row, 0, classes);
        float[] probs = Ctc.softmaxRow(row);
        float[] turns = new float[5];
        turns[4] = classes;
        if (classes == 2) {
            turns[0] = probs[0];
            turns[2] = probs[1];
        } else if (classes >= 4) {
            System.arraycopy(probs, 0, turns, 0, 4);
        } else {
            turns[0] = 1f;
        }
        return turns;
    }

    private Bgr crop(Bgr page, float[] quad) {
        if (axisAligned(quad)) {
            if (covers(page, quad)) {
                return page;
            }
            return cropRect(page, quad);
        }
        int width = (int) Math.round(Math.max(
                Geometry.distance(quad[0], quad[1], quad[2], quad[3]),
                Geometry.distance(quad[6], quad[7], quad[4], quad[5])));
        int height = (int) Math.round(Math.max(
                Geometry.distance(quad[0], quad[1], quad[6], quad[7]),
                Geometry.distance(quad[2], quad[3], quad[4], quad[5])));
        if (width < 2 || height < 2) {
            return null;
        }
        return Geometry.warp(page, quad, width, height);
    }

    private static Bgr cropRect(Bgr page, float[] quad) {
        int x0 = page.width();
        int y0 = page.height();
        int x1 = 0;
        int y1 = 0;
        for (int i = 0; i < 4; i++) {
            int x = Math.round(quad[i * 2]);
            int y = Math.round(quad[i * 2 + 1]);
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
        }
        x0 = Math.max(0, Math.min(page.width() - 1, x0));
        y0 = Math.max(0, Math.min(page.height() - 1, y0));
        x1 = Math.max(0, Math.min(page.width() - 1, x1));
        y1 = Math.max(0, Math.min(page.height() - 1, y1));
        int width = Math.max(1, x1 - x0 + 1);
        int height = Math.max(1, y1 - y0 + 1);
        byte[] out = new byte[width * height * 3];
        byte[] src = page.data();
        for (int y = 0; y < height; y++) {
            System.arraycopy(src, ((y0 + y) * page.width() + x0) * 3, out, y * width * 3, width * 3);
        }
        return new Bgr(out, width, height);
    }

    static boolean axisAligned(float[] quad) {
        return Math.abs(quad[1] - quad[3]) <= 2f
                && Math.abs(quad[7] - quad[5]) <= 2f
                && Math.abs(quad[0] - quad[6]) <= 2f
                && Math.abs(quad[2] - quad[4]) <= 2f;
    }

    private static boolean covers(Bgr page, float[] quad) {
        float minX = quad[0];
        float maxX = quad[0];
        float minY = quad[1];
        float maxY = quad[1];
        for (int i = 1; i < 4; i++) {
            minX = Math.min(minX, quad[i * 2]);
            maxX = Math.max(maxX, quad[i * 2]);
            minY = Math.min(minY, quad[i * 2 + 1]);
            maxY = Math.max(maxY, quad[i * 2 + 1]);
        }
        return minX <= 1f && minY <= 1f && maxX >= page.width() - 2f && maxY >= page.height() - 2f;
    }

    private static List<DbPost.Box> fullPage(Bgr bgr) {
        float right = Math.max(0, bgr.width() - 1);
        float bottom = Math.max(0, bgr.height() - 1);
        return List.of(new DbPost.Box(new float[]{0, 0, right, 0, right, bottom, 0, bottom}, 1f));
    }

    private static TextLine textLine(DbPost.Box box, String text, float score) {
        float[] quad = box.quad();
        float minX = quad[0];
        float maxX = quad[0];
        float minY = quad[1];
        float maxY = quad[1];
        for (int i = 1; i < 4; i++) {
            minX = Math.min(minX, quad[i * 2]);
            maxX = Math.max(maxX, quad[i * 2]);
            minY = Math.min(minY, quad[i * 2 + 1]);
            maxY = Math.max(maxY, quad[i * 2 + 1]);
        }
        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(minY);
        int w = Math.max(1, (int) Math.ceil(maxX) - x);
        int h = Math.max(1, (int) Math.ceil(maxY) - y);
        return new TextLine(text, score, box.score(), quad, new Rectangle(x, y, w, h));
    }

    private static float[] scaleClip(float[] quad, float scaleX, float scaleY, int width, int height) {
        float[] out = new float[8];
        float maxX = Math.max(0, width - 1);
        float maxY = Math.max(0, height - 1);
        for (int i = 0; i < 4; i++) {
            float x = quad[i * 2] * scaleX;
            float y = quad[i * 2 + 1] * scaleY;
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                return null;
            }
            out[i * 2] = Math.max(0f, Math.min(maxX, x));
            out[i * 2 + 1] = Math.max(0f, Math.min(maxY, y));
        }
        return out;
    }

    private static Planar planar(Tensor tensor) {
        int rank = tensor.rank();
        if (rank == 2) {
            int width = tensor.dims[1];
            int height = tensor.dims[0];
            DbPost.mapLength(width, height, tensor.f.length);
            return new Planar(tensor.f, width, height);
        }
        if (rank == 3) {
            return copyMap(tensor.f, tensor.dims[2], tensor.dims[1]);
        }
        if (rank == 4) {
            if (tensor.dims[0] != 1) {
                throw new OcrException("detection batch " + tensor.dims[0] + " is not supported");
            }
            return copyMap(tensor.f, tensor.dims[3], tensor.dims[2]);
        }
        throw new OcrException("detection map rank " + tensor.shapeString());
    }

    /** Copies one probability map. The byte count is checked before the array exists. */
    private static Planar copyMap(float[] data, int width, int height) {
        int pixels = DbPost.mapLength(width, height, data.length);
        float[] map = new float[pixels];
        System.arraycopy(data, 0, map, 0, pixels);
        return new Planar(map, width, height);
    }

    private Session.Options lineOptions() {
        return new Session.Options(cfg.useVector(), 1, null, cfg.quantize(), false, true);
    }

    private LinkedBlockingDeque<Session> sessions(Session primary) {
        LinkedBlockingDeque<Session> queue = new LinkedBlockingDeque<>(cfg.threads());
        queue.add(primary);
        for (int i = 1; i < cfg.threads(); i++) {
            queue.add(primary.fork());
        }
        return queue;
    }

    /** {@code threads} forks and not the primary, which runs on {@code pool} and stays with the calling thread. */
    private LinkedBlockingDeque<Session> forks(Session primary) {
        LinkedBlockingDeque<Session> queue = new LinkedBlockingDeque<>(cfg.threads());
        for (int i = 0; i < cfg.threads(); i++) {
            queue.add(primary.fork());
        }
        return queue;
    }

    private static Session borrow(LinkedBlockingDeque<Session> queue) {
        try {
            Session session = queue.poll(10, TimeUnit.MINUTES);
            if (session == null) {
                throw new OcrException("timed out waiting for a model session");
            }
            return session;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("interrupted", e);
        }
    }

    private <T> List<T> collect(List<Future<T>> futures) {
        try {
            List<T> out = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                out.add(future.get());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new OcrException(cause.getMessage(), cause);
        }
    }

    /**
     * Submits at most {@code window} jobs ahead of the joined prefix and joins in submission order.
     * A failure still waits for every job already submitted, so borrowed recognizer sessions return
     * before the engine lock is released.
     */
    static <T> List<T> joinWindow(ExecutorService pool, int window, int count, IntFunction<Callable<T>> jobs) {
        return joinWindow(pool, window, count, jobs, null);
    }

    /**
     * Submits at most {@code window} jobs ahead of the joined prefix and joins in submission order.
     * {@code ready} runs on the caller after each join, before the next page is waited on.
     * A failure still waits for every job already submitted, so borrowed recognizer sessions return
     * before the engine lock is released.
     */
    static <T> List<T> joinWindow(
            ExecutorService pool, int window, int count, IntFunction<Callable<T>> jobs, Consumer<T> ready
    ) {
        if (count == 0) {
            return List.of();
        }
        if (count < 0 || window < 1) {
            throw new OcrException("window must be positive");
        }
        List<Future<T>> futures = new ArrayList<>(count);
        List<T> results = new ArrayList<>(count);
        int[] joined = {0};
        try {
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(jobs.apply(i)));
                if (futures.size() - joined[0] >= window) {
                    deliver(futures, results, joined, ready);
                }
            }
            while (joined[0] < futures.size()) {
                deliver(futures, results, joined, ready);
            }
            return results;
        } catch (Throwable error) {
            boolean interrupted = finish(futures, joined[0], error);
            if (interrupted || error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw unwrap(error);
        }
    }

    /** Advances {@code joined} before {@code ready}, so a throw does not await the same future again. */
    private static <T> void deliver(
            List<Future<T>> futures, List<T> results, int[] joined, Consumer<T> ready
    ) throws Exception {
        T value = await(futures.get(joined[0]));
        results.add(value);
        joined[0]++;
        if (ready != null) {
            ready.accept(value);
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Error serious) {
                throw serious;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    /** Waits for futures already submitted. The same failure is not suppressed onto itself. */
    private static boolean finish(List<? extends Future<?>> futures, int from, Throwable primary) {
        boolean interrupted = Thread.interrupted();
        for (int i = from; i < futures.size(); i++) {
            try {
                futures.get(i).get();
            } catch (InterruptedException e) {
                interrupted = true;
            } catch (Throwable error) {
                Throwable cause = error;
                if (error instanceof ExecutionException && error.getCause() != null) {
                    cause = error.getCause();
                }
                if (cause != primary) {
                    primary.addSuppressed(cause);
                }
            }
        }
        return interrupted;
    }

    private static RuntimeException unwrap(Throwable error) {
        if (error instanceof ExecutionException execution && execution.getCause() != null) {
            error = execution.getCause();
        }
        if (error instanceof InterruptedException) {
            return new OcrException("interrupted", error);
        }
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }
        if (error instanceof Error serious) {
            throw serious;
        }
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return new OcrException(message, error);
    }

    private <T> List<T> awaitTasks(List<Callable<T>> tasks) {
        try {
            return collect(pool.invokeAll(tasks));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("interrupted", e);
        }
    }

    private static Tensor only(Map<String, Tensor> outputs) {
        if (outputs.isEmpty()) {
            throw new OcrException("model returned no outputs");
        }
        return outputs.values().iterator().next();
    }

    private static int staticDim(Sig sig, int index) {
        if (sig.dims == null || index < 0 || index >= sig.dims.length) {
            return -1;
        }
        return sig.dims[index];
    }

    private record Planar(float[] data, int width, int height) {
    }
}
