package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.image.Bgr;
import io.github.kamilkrzywanski.ocr.image.Geometry;
import io.github.kamilkrzywanski.ocr.image.Images;
import io.github.kamilkrzywanski.ocr.pdf.PdfPages;
import io.github.kamilkrzywanski.ocr.post.Charset;
import io.github.kamilkrzywanski.ocr.post.Ctc;
import io.github.kamilkrzywanski.ocr.post.DbPost;
import io.github.kamilkrzywanski.ocr.post.Lexicon;
import io.github.kamilkrzywanski.ocr.post.PostalCities;
import io.github.kamilkrzywanski.ocr.post.ReadingOrder;
import io.github.kamilkrzywanski.ocr.post.Tables;
import io.github.kamilkrzywanski.ocr.runtime.Session;
import io.github.kamilkrzywanski.ocr.runtime.Sig;
import io.github.kamilkrzywanski.ocr.runtime.Tensor;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One engine is not safe for concurrent {@link #recognize} calls; the methods are synchronized.
 * Concurrent callers use {@link OcrPool}, which lends each of them a separate engine.
 * <p>
 * Detection may split GEMM across {@code pool} on the calling thread. Page workers therefore use
 * {@link Session#fork()} with no pool and run lines inline, so they never submit back to {@code pool}.
 * A multi-page PDF keeps at most {@code threads} bitmaps in flight. One page still detects on {@code pool}.
 * Do not call {@link #recognize} from an {@code ocr-worker} thread.
 */
public final class OcrEngine implements AutoCloseable {
    /** A Polish postal code and the city token after it. */
    private static final Pattern POSTAL_CITY = Pattern.compile("^(\\d{2}-\\d{3})\\s+(\\S.*)$");
    /**
     * Black-and-white cut for a dot-matrix city that is still full of digits.
     * On the measured invoice 195–240 read Łódź apart from the capital L.
     * 190 turns the last letter into ż, and 245 collapses the code.
     */
    private static final int POSTAL_INK = 220;

    private final OcrConfig cfg;
    private final ExecutorService pool;
    private Session det;
    private Session rec;
    private Session cls;
    private Charset charset;
    private Lexicon lexicon;
    private PostalCities postal;
    private ArrayBlockingQueue<Session> recPool;
    private ArrayBlockingQueue<Session> clsPool;
    private String detInput;
    private String recInput;
    private String clsInput;
    private int clsHeight = 80;
    private int clsWidth = 160;
    private boolean closed;

    private OcrEngine(OcrConfig cfg) {
        this.cfg = cfg;
        ExecutorService workers = cfg.threads > 1
                ? Executors.newFixedThreadPool(cfg.threads, task -> {
                    Thread thread = new Thread(task, "ocr-worker");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
        this.pool = workers;
        try {
            loadModels();
        } catch (RuntimeException e) {
            if (workers != null) {
                workers.shutdownNow();
            }
            throw e;
        }
    }

    private void loadModels() {
        if (cfg.useDet) {
            requireModel(cfg.detModel, "detection model");
            this.det = Session.load(cfg.detModel, new Session.Options(
                    cfg.useVector, cfg.threads, pool, cfg.quantize, cfg.opDebug, true));
            this.detInput = det.inputName();
        } else {
            this.det = null;
            this.detInput = null;
        }
        if (cfg.useRec) {
            requireModel(cfg.recModel, "recognition model");
            requireModel(cfg.dict, "recognition dict");
            this.rec = Session.load(cfg.recModel, lineOptions());
            this.recInput = rec.inputName();
            this.charset = Charset.load(cfg.dict, cfg.useSpaceChar);
            Path models = cfg.dict.getParent();
            this.lexicon = models == null ? null : Lexicon.optional(models);
            this.postal = models == null ? null : PostalCities.optional(models);
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
            if (modelHeight > 0 && modelHeight != cfg.recHeight) {
                throw new OcrException("REC height " + cfg.recHeight + " != model input " + modelHeight);
            }
            this.recPool = pool == null ? null : sessions(rec);
        } else {
            this.rec = null;
            this.recInput = null;
            this.charset = null;
            this.recPool = null;
        }
        if (cfg.useCls) {
            requireModel(cfg.clsModel, "classifier model");
            this.cls = Session.load(cfg.clsModel, lineOptions());
            this.clsInput = cls.inputName();
            int height = 80;
            int width = 160;
            for (Sig sig : cls.graph().inputs) {
                if (clsInput.equals(sig.name) && sig.dims != null && sig.dims.length >= 2) {
                    int h = sig.dims[sig.dims.length - 2];
                    int w = sig.dims[sig.dims.length - 1];
                    if (h > 0) {
                        height = h;
                    }
                    if (w > 0) {
                        width = w;
                    }
                }
            }
            this.clsHeight = height;
            this.clsWidth = width;
            this.clsPool = pool == null ? null : sessions(cls);
        } else {
            this.cls = null;
            this.clsInput = null;
            this.clsHeight = 80;
            this.clsWidth = 160;
            this.clsPool = null;
        }
    }

    public static OcrEngine load(Path modelDirectory) {
        if (modelDirectory == null) {
            throw new OcrException("model directory is null");
        }
        return create(OcrConfig.latin(modelDirectory));
    }

    public static OcrEngine create(OcrConfig config) {
        if (config == null) {
            throw new OcrException("config is null");
        }
        return new OcrEngine(config);
    }

    public OcrConfig config() {
        return cfg;
    }

    public synchronized OcrResult recognize(BufferedImage image) {
        ensureOpen();
        return run(image, true);
    }

    /** Skips detection and treats the whole bitmap as one text line. */
    public synchronized OcrResult recognizeLine(BufferedImage image) {
        ensureOpen();
        return run(image, true, false);
    }

    public synchronized List<OcrResult> recognizePdf(Path pdf, float dpi) {
        return recognizePdf(pdf, dpi, null);
    }

    /**
     * Same as {@link #recognizePdf(Path, float)}. {@code handler} is called in page order as soon
     * as that page is done, before later pages finish. A null handler only collects the list.
     */
    public synchronized List<OcrResult> recognizePdf(Path pdf, float dpi, PageHandler handler) {
        ensureOpen();
        try (PdfPages.Source source = PdfPages.open(pdf, dpi)) {
            int pages = source.pages();
            if (pages <= 1 || pool == null) {
                List<OcrResult> results = new ArrayList<>(pages);
                for (int i = 0; i < pages; i++) {
                    BufferedImage image = source.render(i);
                    if (i + 1 == pages) {
                        source.close();
                    }
                    OcrResult result = run(image, true);
                    results.add(result);
                    if (handler != null) {
                        handler.onPage(i, pages, result);
                    }
                }
                return results;
            }
            int[] index = {0};
            return joinWindow(pool, cfg.threads, pages, i -> {
                BufferedImage image = source.render(i);
                if (i + 1 == pages) {
                    source.close();
                }
                return () -> run(image, false);
            }, result -> {
                if (handler != null) {
                    handler.onPage(index[0], pages, result);
                }
                index[0]++;
            });
        }
    }

    /** Called in order while {@link #recognizePdf(Path, float, PageHandler)} is still running. */
    @FunctionalInterface
    public interface PageHandler {
        void onPage(int index, int pageCount, OcrResult result);
    }

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
        recPool = null;
        clsPool = null;
    }

    private static void requireModel(Path path, String label) {
        ModelFiles.requireFile(path, label);
    }

    private void ensureOpen() {
        if (closed) {
            throw new OcrException("engine is closed");
        }
    }

    /** True after {@link #close()}. The pool uses this to drop a slot the caller shut down. */
    synchronized boolean isClosed() {
        return closed;
    }

    private OcrResult run(BufferedImage image, boolean lineParallel) {
        return run(image, lineParallel, cfg.useDet && det != null);
    }

    private OcrResult run(BufferedImage image, boolean lineParallel, boolean detect) {
        if (image == null) {
            throw new OcrException("image is null");
        }
        long totalStart = System.nanoTime();
        Bgr bgr = Images.toBgr(image);
        if (cfg.deskew) {
            bgr = Images.deskew(bgr);
        }
        if (cfg.adaptiveThreshold) {
            bgr = Images.sauvola(bgr);
        }
        long detStart = totalStart;
        List<DbPost.Box> boxes = detect ? detect(bgr, lineParallel) : fullPage(bgr);
        long detectionNanos = System.nanoTime() - detStart;
        long recStart = System.nanoTime();
        List<TextLine> recognized = recognizeBoxes(bgr, boxes, lineParallel);
        recognized = splitAmounts(bgr, recognized);
        long recognitionNanos = System.nanoTime() - recStart;
        List<List<TextLine>> rows = ReadingOrder.group(recognized);
        List<TextLine> flat = ReadingOrder.flatten(rows);
        return new OcrResult(
                ReadingOrder.join(rows),
                flat,
                readTables(bgr, flat),
                detectionNanos,
                recognitionNanos,
                System.nanoTime() - totalStart);
    }

    /**
     * A box that reads as an amount and a VAT rate is cut on the vertical rule between
     * them when each side reads back as that same token. {@code 8.13 23} becomes two lines.
     * Anything else, including a half that does not match, stays one line.
     */
    private List<TextLine> splitAmounts(Bgr page, List<TextLine> lines) {
        if (!cfg.useRec || rec == null || lines.isEmpty()) {
            return lines;
        }
        boolean any = false;
        for (TextLine line : lines) {
            if (AmountSplit.parts(line.text()) != null) {
                any = true;
                break;
            }
        }
        if (!any) {
            return lines;
        }
        boolean borrowed = recPool != null;
        Session session = borrowed ? borrow(recPool) : rec;
        try {
            List<TextLine> out = new ArrayList<>(lines.size() + 1);
            for (TextLine line : lines) {
                TextLine[] pair = splitAmount(page, line, session);
                if (pair == null) {
                    out.add(line);
                } else {
                    out.add(pair[0]);
                    out.add(pair[1]);
                }
            }
            return out;
        } finally {
            if (borrowed) {
                recPool.offer(session);
            }
        }
    }

    private TextLine[] splitAmount(Bgr page, TextLine line, Session session) {
        String[] parts = AmountSplit.parts(line.text());
        if (parts == null || line.bounds() == null) {
            return null;
        }
        // The detector quad leans a few pixels, so the upright crop is the bounds.
        // That rectangle is the one whose rule separates 8.13 from 23.
        Rectangle bounds = line.bounds();
        float x0 = bounds.x;
        float y0 = bounds.y;
        float x1 = bounds.x + bounds.width - 1f;
        float y1 = bounds.y + bounds.height - 1f;
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }
        Bgr crop = crop(page, new float[] {x0, y0, x1, y0, x1, y1, x0, y1});
        if (crop == null) {
            return null;
        }
        int[] cut = ruleCut(crop);
        if (cut == null) {
            return null;
        }
        Bgr leftImage = sliceX(crop, 0, cut[0]);
        Bgr rightImage = sliceX(crop, cut[1], crop.width());
        if (leftImage == null || rightImage == null) {
            return null;
        }
        leftImage = Images.mainInkBand(leftImage);
        rightImage = Images.mainInkBand(rightImage);
        Ctc.Result left = recognizeCrop(leftImage, session);
        Ctc.Result right = recognizeCrop(rightImage, session);
        if (!parts[0].equals(left.text()) || !parts[1].equals(right.text())) {
            return null;
        }
        float span = x1 - x0;
        if (crop.width() < 1) {
            return null;
        }
        float cutLeft = x0 + cut[0] * span / crop.width();
        float cutRight = x0 + cut[1] * span / crop.width();
        if (cutLeft <= x0 + 2f || cutRight >= x1 - 2f || cutRight <= cutLeft) {
            return null;
        }
        return new TextLine[] {
                lineBox(parts[0], left.score(), line.detScore(), x0, y0, cutLeft, y1),
                lineBox(parts[1], right.score(), line.detScore(), cutRight, y0, x1, y1)
        };
    }

    /** Left edge of the rule and the first column after it, or null. */
    private static int[] ruleCut(Bgr crop) {
        int width = crop.width();
        int height = crop.height();
        if (width < 40 || height < 16) {
            return null;
        }
        int from = width / 5;
        int to = width - from;
        byte[] data = crop.data();
        int[] dark = new int[width];
        for (int y = 0; y < height; y++) {
            int row = y * width * 3;
            for (int x = from; x < to; x++) {
                if (luma(data, row + x * 3) < 160) {
                    dark[x]++;
                }
            }
        }
        int best = -1;
        int bestDark = 0;
        for (int x = from; x < to; x++) {
            if (dark[x] > bestDark) {
                bestDark = dark[x];
                best = x;
            }
        }
        if (best < 0 || bestDark * 2 < height) {
            return null;
        }
        int left = best;
        while (left > from && dark[left - 1] * 3 >= bestDark) {
            left--;
        }
        int right = best;
        while (right + 1 < to && dark[right + 1] * 3 >= bestDark) {
            right++;
        }
        if (right - left > 4 || left < 8 || width - right < 8) {
            return null;
        }
        return new int[] {left, right + 1};
    }

    private static Bgr sliceX(Bgr src, int x0, int x1) {
        int width = x1 - x0;
        int height = src.height();
        if (width < 2 || height < 2) {
            return null;
        }
        byte[] out = new byte[width * height * 3];
        byte[] in = src.data();
        for (int y = 0; y < height; y++) {
            System.arraycopy(in, (y * src.width() + x0) * 3, out, y * width * 3, width * 3);
        }
        return new Bgr(out, width, height);
    }

    private static TextLine lineBox(String text, float score, float detScore,
                                    float x0, float y0, float x1, float y1) {
        float[] quad = {x0, y0, x1, y0, x1, y1, x0, y1};
        int x = (int) Math.floor(Math.min(x0, x1));
        int y = (int) Math.floor(Math.min(y0, y1));
        int w = Math.max(1, (int) Math.ceil(Math.max(x0, x1)) - x);
        int h = Math.max(1, (int) Math.ceil(Math.max(y0, y1)) - y);
        return new TextLine(text, score, detScore, quad, new Rectangle(x, y, w, h));
    }

    /**
     * Borrows one recognizer after line recognition has already returned its session.
     * A ruled box is sliced and read again; the joined page text keeps the original line.
     */
    private List<TextTable> readTables(Bgr page, List<TextLine> lines) {
        if (!cfg.useRec || rec == null || charset == null) {
            return Tables.find(page, lines, null);
        }
        boolean borrowed = recPool != null;
        Session session = borrowed ? borrow(recPool) : rec;
        try {
            return Tables.find(page, lines, crop -> readSlice(session, crop));
        } finally {
            if (borrowed) {
                recPool.offer(session);
            }
        }
    }

    private String readSlice(Session session, Bgr crop) {
        if (crop.width() < 2 || crop.height() < 2) {
            return "";
        }
        float[] data = Images.recTensor(crop, cfg.recHeight, cfg.recMinWidth, cfg.recMaxWidth, cfg.mean, cfg.std);
        int width = data.length / (3 * cfg.recHeight);
        Tensor input = Tensor.f32(new int[]{1, 3, cfg.recHeight, width}, data);
        Tensor output = only(session.run(Map.of(recInput, input)));
        Ctc.Result result = Ctc.greedy(output, charset);
        if (result.text().isEmpty() || result.score() < cfg.recThresh) {
            return "";
        }
        // The piece is not the line. The same dictionary pass the line already had
        // puts the ł back on Wysyłka; the joined page text is unchanged.
        return applyLexicon(result.text());
    }

    private List<DbPost.Box> detect(Bgr bgr, boolean lineParallel) {
        int[] size = Images.detSize(bgr.width(), bgr.height(), cfg.detLimitSideLen, cfg.detLimitType);
        // Page workers already occupy pool, so they keep this resize on their own thread.
        float[] input = Images.resizeNormalize(bgr, size[0], size[1], cfg.mean, cfg.std,
                lineParallel ? pool : null, cfg.threads);
        Tensor tensor = Tensor.f32(new int[]{1, 3, size[1], size[0]}, input);
        Session session = lineParallel ? det : det.fork();
        Tensor output = only(session.run(Map.of(detInput, tensor)));
        Planar map = planar(output);
        DbPost.asProbability(map.data);
        List<DbPost.Box> found = DbPost.find(
                map.data, map.width, map.height,
                cfg.detThresh, cfg.boxThresh, cfg.unclipRatio, cfg.useDilation, cfg.maxCandidates);
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

    private List<TextLine> recognizeBoxes(Bgr page, List<DbPost.Box> boxes, boolean lineParallel) {
        if (!cfg.useRec || rec == null) {
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
                            clsPool.offer(clsSession);
                        }
                        recPool.offer(recSession);
                    }
                });
            }
            List<TextLine> lines = new ArrayList<>();
            for (TextLine line : awaitTasks(tasks)) {
                if (line != null) {
                    lines.add(line);
                }
            }
            return lines;
        }
        if (!lineParallel && recPool != null) {
            Session recSession = borrow(recPool);
            Session clsSession = clsPool == null ? null : borrow(clsPool);
            try {
                return sequential(page, boxes, recSession, clsSession);
            } finally {
                if (clsSession != null) {
                    clsPool.offer(clsSession);
                }
                recPool.offer(recSession);
            }
        }
        return sequential(page, boxes, rec, cls);
    }

    private List<TextLine> sequential(Bgr page, List<DbPost.Box> boxes, Session recSession, Session clsSession) {
        List<TextLine> lines = new ArrayList<>();
        for (DbPost.Box box : boxes) {
            TextLine line = decode(page, box, recSession, clsSession);
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private TextLine decode(Bgr page, DbPost.Box box, Session recSession, Session clsSession) {
        Bgr crop = crop(page, box.quad());
        if (crop == null) {
            return null;
        }
        if (cfg.rotateVertical && crop.height() >= crop.width() * 1.5f) {
            crop = Images.rot90ccw(crop);
        }
        if (clsSession != null) {
            crop = orient(crop, clsSession);
        }
        // A neighbor line split off by a tall white gap is dropped before the resize.
        crop = Images.mainInkBand(crop);
        Ctc.Result result = recognizeCrop(crop, recSession);
        if (result.text().isEmpty() || result.score() < cfg.recThresh) {
            return null;
        }
        String text = applyLexicon(result.text());
        float score = result.score();
        Bgr indexed = Images.cutLeftIndex(crop);
        if (indexed != crop) {
            // The row number is real ink in the same box as the name. A second read is kept
            // only when it is this text without that leading number, so a letter stem stays.
            // On the measured invoice the cut drops ł and ó. The lexicon puts them back.
            // Without the lists the first read already has both, so a shorter read that
            // only lost those two marks still confirms the cut.
            String stripped = text.replaceFirst("^[0-9]+\\s+", "");
            if (!stripped.equals(text)) {
                Ctc.Result again = recognizeCrop(indexed, recSession);
                if (!again.text().isEmpty() && again.score() >= cfg.recThresh) {
                    String second = applyLexicon(again.text());
                    if (IndexKeep.agrees(stripped, second)) {
                        text = stripped;
                        score = again.score();
                    }
                }
            }
        }
        Matcher coded = POSTAL_CITY.matcher(text.trim());
        if (coded.matches() && hasDigit(coded.group(2))) {
            Ctc.Result again = recognizeCrop(inkThreshold(crop, POSTAL_INK), recSession);
            if (!again.text().isEmpty() && again.score() >= cfg.recThresh) {
                String second = applyLexicon(again.text()).trim();
                Matcher city = POSTAL_CITY.matcher(second);
                if (city.matches() && city.group(1).equals(coded.group(1))
                        && !hasDigit(city.group(2)) && city.group(2).length() >= 3) {
                    text = second;
                    score = again.score();
                }
            }
        }
        // Żyt's caron is a light gap in the top bar and disappears at height 48.
        // A dot above that gap is kept only when the second read adds ż or ź on this Z and nothing else.
        // On the measured scans that restores Żyt. SPRZEDAZ, Ziemia and Zapłacono stay.
        if (text.indexOf('Z') >= 0) {
            Bgr painted = NotchedZ.paint(crop, result.text());
            if (painted != null) {
                Ctc.Result again = recognizeCrop(painted, recSession);
                if (!again.text().isEmpty() && again.score() >= cfg.recThresh) {
                    String merged = NotchedZ.keep(text, applyLexicon(again.text()));
                    if (merged != null) {
                        text = merged;
                        score = again.score();
                    }
                }
            }
        }
        // One official spelling for this code replaces the city when the letters
        // already match and the list does not remove a diacritic. 90-031 is Łódź.
        // A code shared by several places is absent, so that line stays.
        if (postal != null) {
            text = postal.apply(text);
        }
        // Without the word lists, white above and below lets the ł stroke survive
        // the resize to 48, a 4px shift down opens the gap in "i magazyn", and
        // eight white rows under the line open the gap before Wspólnicy as well.
        // Each second string is kept only when the only changes are that stroke
        // or a space between letters. The read with more of those repairs wins,
        // so the ł in Spółka is not kept at the cost of the missing space.
        // A plain margin also turns x into × and drops ł in Kłodzka, so those
        // reads are discarded. The lists already repair these lines, and a postal
        // line belongs to the threshold read above. Table cells never get here.
        text = marginText(crop, recSession, text);
        // A VAT field label one edit from exactly one known label takes that spelling.
        // Lines with a digit, and any label that is already exact, stay as read.
        text = FieldLabels.apply(text);
        text = ParenSpace.apply(text);
        // The j in j.m. hangs out of a short header box. A second read 16 px to the
        // left and below is kept only when the whole line was ".x." and the new
        // text adds one letter in front of that dot. A cell rule read as 1 stays out.
        Ctc.Result hooked = rereadDot(page, box, recSession, clsSession, text);
        if (hooked != null) {
            text = hooked.text();
            score = hooked.score();
        }
        // A cell rule at the side of the crop is read as [ or |. Whitening those
        // tall edge strokes and reading again is kept only when the text loses
        // exactly that one fence character on a side. [VAT (%)| becomes VAT (%).
        Ctc.Result ruled = rereadEdge(crop, recSession, clsSession, text);
        if (ruled != null) {
            text = ruled.text();
            score = ruled.score();
        }
        return textLine(box, text, score);
    }

    private Ctc.Result rereadEdge(Bgr crop, Session recSession, Session clsSession, String text) {
        if (!EdgeRule.candidate(text)) {
            return null;
        }
        Bgr blank = blankEdgeRules(crop);
        if (blank == null) {
            return null;
        }
        if (clsSession != null) {
            blank = orient(blank, clsSession);
        }
        blank = Images.mainInkBand(blank);
        Ctc.Result result = recognizeCrop(blank, recSession);
        if (result.text().isEmpty() || result.score() < cfg.recThresh) {
            return null;
        }
        String second = ParenSpace.apply(FieldLabels.apply(applyLexicon(result.text())));
        if (EdgeRule.keep(text, second) == null) {
            return null;
        }
        return new Ctc.Result(second, result.score());
    }

    /** Tall dark columns in the outer 16 px become white. Null when none qualify. */
    private static Bgr blankEdgeRules(Bgr src) {
        int width = src.width();
        int height = src.height();
        if (width < 48 || height < 16) {
            return null;
        }
        int edge = Math.min(16, width / 5);
        byte[] in = src.data();
        int[] dark = new int[width];
        for (int y = 0; y < height; y++) {
            int row = y * width * 3;
            for (int x = 0; x < edge; x++) {
                if (luma(in, row + x * 3) < 160) {
                    dark[x]++;
                }
            }
            for (int x = width - edge; x < width; x++) {
                if (luma(in, row + x * 3) < 160) {
                    dark[x]++;
                }
            }
        }
        boolean[] cut = new boolean[width];
        boolean any = false;
        for (int x = 0; x < edge; x++) {
            if (dark[x] * 4 > height) {
                cut[x] = true;
                any = true;
            }
        }
        for (int x = width - edge; x < width; x++) {
            if (dark[x] * 4 > height) {
                cut[x] = true;
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        byte[] out = in.clone();
        for (int x = 0; x < width; x++) {
            if (!cut[x]) {
                continue;
            }
            for (int y = 0; y < height; y++) {
                int offset = (y * width + x) * 3;
                out[offset] = (byte) 255;
                out[offset + 1] = (byte) 255;
                out[offset + 2] = (byte) 255;
            }
        }
        return new Bgr(out, width, height);
    }

    private static int luma(byte[] data, int offset) {
        return ((data[offset + 2] & 255) * 54 + (data[offset + 1] & 255) * 183 + (data[offset] & 255) * 19) >> 8;
    }

    private Ctc.Result rereadDot(Bgr page, DbPost.Box box, Session recSession, Session clsSession, String text) {
        if (!DotKeep.candidate(text) || !axisAligned(box.quad())) {
            return null;
        }
        Bgr again = crop(page, DotKeep.grow(box.quad()));
        if (again == null) {
            return null;
        }
        if (cfg.rotateVertical && again.height() >= again.width() * 1.5f) {
            again = Images.rot90ccw(again);
        }
        if (clsSession != null) {
            again = orient(again, clsSession);
        }
        again = Images.mainInkBand(again);
        Ctc.Result result = recognizeCrop(again, recSession);
        if (result.text().isEmpty() || result.score() < cfg.recThresh) {
            return null;
        }
        String second = ParenSpace.apply(FieldLabels.apply(applyLexicon(result.text())));
        if (DotKeep.keep(text, second) == null) {
            return null;
        }
        return new Ctc.Result(second, result.score());
    }


    private String marginText(Bgr crop, Session recSession, String text) {
        if (lexicon != null || !MarginKeep.candidate(text) || POSTAL_CITY.matcher(text.trim()).matches()) {
            return text;
        }
        String best = text;
        int bestRepairs = 0;
        Bgr[] alts = {
                padVertical(crop, 4, 4),
                shiftDown(crop, 4),
                padVertical(crop, 0, 8)
        };
        for (Bgr alt : alts) {
            String again = readMargin(alt, recSession);
            int repairs = MarginKeep.repairs(text, again);
            if (repairs > bestRepairs) {
                best = again;
                bestRepairs = repairs;
            }
        }
        return best;
    }

    private String readMargin(Bgr image, Session recSession) {
        Ctc.Result again = recognizeCrop(image, recSession);
        if (again.text().isEmpty() || again.score() < cfg.recThresh) {
            return "";
        }
        return again.text();
    }

    /** White rows above and below. The ink itself is not scaled yet. */
    private static Bgr padVertical(Bgr src, int top, int bottom) {
        int width = src.width();
        int height = src.height();
        byte[] out = new byte[width * (height + top + bottom) * 3];
        Arrays.fill(out, (byte) 255);
        System.arraycopy(src.data(), 0, out, top * width * 3, width * height * 3);
        return new Bgr(out, width, height + top + bottom);
    }

    /** Move the ink down by {@code dy} rows. The bottom rows fall outside the crop. */
    private static Bgr shiftDown(Bgr src, int dy) {
        int width = src.width();
        int height = src.height();
        if (dy <= 0 || dy >= height) {
            return src;
        }
        byte[] out = new byte[width * height * 3];
        Arrays.fill(out, (byte) 255);
        System.arraycopy(src.data(), 0, out, dy * width * 3, (height - dy) * width * 3);
        return new Bgr(out, width, height);
    }

    private String applyLexicon(String text) {
        return lexicon == null ? text : lexicon.apply(text);
    }

    private static boolean hasDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** One global cut. Gray ink becomes black and the paper becomes white. */
    private static Bgr inkThreshold(Bgr src, int threshold) {
        byte[] in = src.data();
        byte[] out = new byte[in.length];
        int pixels = src.width() * src.height();
        for (int i = 0; i < pixels; i++) {
            int o = i * 3;
            int luma = ((in[o + 2] & 255) * 54 + (in[o + 1] & 255) * 183 + (in[o] & 255) * 19) >> 8;
            byte v = (byte) (luma < threshold ? 0 : 255);
            out[o] = v;
            out[o + 1] = v;
            out[o + 2] = v;
        }
        return new Bgr(out, src.width(), src.height());
    }

    private Ctc.Result recognizeCrop(Bgr crop, Session recSession) {
        float[] data = Images.recTensor(crop, cfg.recHeight, cfg.recMinWidth, cfg.recMaxWidth, cfg.mean, cfg.std);
        int width = data.length / (3 * cfg.recHeight);
        Tensor input = Tensor.f32(new int[]{1, 3, cfg.recHeight, width}, data);
        Tensor output = only(recSession.run(Map.of(recInput, input)));
        return Ctc.greedy(output, charset);
    }

    private Bgr orient(Bgr crop, Session clsSession) {
        float[] data = Images.clsTensor(crop, clsHeight, clsWidth, cfg.mean, cfg.std);
        Tensor input = Tensor.f32(new int[]{1, 3, clsHeight, clsWidth}, data);
        Tensor output = only(clsSession.run(Map.of(clsInput, input)));
        int classes = output.dims[output.dims.length - 1];
        float[] row = new float[classes];
        int offset = output.f.length - classes;
        System.arraycopy(output.f, Math.max(0, offset), row, 0, classes);
        float[] probs = Ctc.softmaxRow(row);
        int arg = 0;
        for (int i = 1; i < classes; i++) {
            if (probs[i] > probs[arg]) {
                arg = i;
            }
        }
        if (classes == 2) {
            if (arg == 1 && probs[1] > cfg.clsThresh) {
                return Images.rot180(crop);
            }
            return crop;
        }
        if (classes >= 4) {
            return switch (arg) {
                case 1 -> Images.rot90ccw(crop);
                case 2 -> Images.rot180(crop);
                case 3 -> Images.rot90cw(crop);
                default -> crop;
            };
        }
        return crop;
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

    private static boolean axisAligned(float[] quad) {
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
        return new Session.Options(cfg.useVector, 1, null, cfg.quantize, cfg.opDebug, true);
    }

    private ArrayBlockingQueue<Session> sessions(Session primary) {
        ArrayBlockingQueue<Session> queue = new ArrayBlockingQueue<>(cfg.threads);
        queue.add(primary);
        for (int i = 1; i < cfg.threads; i++) {
            queue.add(primary.fork());
        }
        return queue;
    }

    private static Session borrow(ArrayBlockingQueue<Session> queue) {
        try {
            Session session = queue.poll(10, TimeUnit.MINUTES);
            if (session == null) {
                throw new OcrException("timed out waiting for a recognizer session");
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
