package io.github.kamilkrzywanski.ocr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Inference settings. Thresholds follow RapidOCR mobile:
 * DB thresh 0.35 / box 0.5 / unclip 1.6, REC height 48, BGR normalize 0.5/0.5. Detection runs on a
 * page scaled to at most 2048 px on the longer side: small print, dense columns and thin receipt
 * type need it. 960 is about twice as fast on a large page and needs less heap.
 */
public final class OcrConfig {
    private final ModelSource detModel;
    private final ModelSource recModel;
    private final ModelSource clsModel;
    private final ModelSource dict;
    private final boolean useVector;
    private final int threads;
    private final int detLimitSideLen;
    private final DetLimit detLimitType;
    private final float detThresh;
    private final float boxThresh;
    private final float unclipRatio;
    private final boolean useDilation;
    private final int maxCandidates;
    private final float clsThresh;
    private final boolean useCls;
    private final boolean useDet;
    private final boolean useRec;
    private final int recHeight;
    private final int recMinWidth;
    private final int recMaxWidth;
    private final float recThresh;
    private final boolean useSpaceChar;
    private final boolean rotateVertical;
    private final boolean deskew;
    private final boolean adaptiveThreshold;
    private final boolean quantize;
    private final float mean;
    private final float std;
    private final Corrections corrections;
    private final Layout layout;
    private final Alphabet alphabet;
    private final List<ModelSource> wordLists;
    private final boolean dropFragments;
    private final boolean pageOrientation;

    private OcrConfig(Builder b) {
        this.detModel = b.detModel;
        this.recModel = b.recModel;
        this.clsModel = b.clsModel;
        this.dict = b.dict;
        this.useVector = b.useVector;
        this.threads = b.threads;
        this.detLimitSideLen = b.detLimitSideLen;
        this.detLimitType = b.detLimitType;
        this.detThresh = b.detThresh;
        this.boxThresh = b.boxThresh;
        this.unclipRatio = b.unclipRatio;
        this.useDilation = b.useDilation;
        this.maxCandidates = b.maxCandidates;
        this.clsThresh = b.clsThresh;
        this.useCls = b.useCls;
        this.useDet = b.useDet;
        this.useRec = b.useRec;
        this.recHeight = b.recHeight;
        this.recMinWidth = b.recMinWidth;
        this.recMaxWidth = b.recMaxWidth;
        this.recThresh = b.recThresh;
        this.useSpaceChar = b.useSpaceChar;
        this.rotateVertical = b.rotateVertical;
        this.deskew = b.deskew;
        this.adaptiveThreshold = b.adaptiveThreshold;
        this.quantize = b.quantize;
        this.mean = b.mean;
        this.std = b.std;
        this.corrections = b.corrections;
        this.layout = b.layout;
        this.alphabet = b.alphabet;
        this.wordLists = List.copyOf(b.wordLists);
        this.dropFragments = b.dropFragments;
        this.pageOrientation = b.pageOrientation;
    }

    /** {@return the text detection model, or null when none is set} */
    public ModelSource detModel() { return detModel; }

    /** {@return the text recognition model, or null when none is set} */
    public ModelSource recModel() { return recModel; }

    /** {@return the model that tells an upside-down line from an upright one, or null when none is set} */
    public ModelSource clsModel() { return clsModel; }

    /** {@return the character dictionary of the recognition model, one character per line, or null when none is set} */
    public ModelSource dict() { return dict; }

    /**
     * {@return whether the kernels may use the Vector API}
     * It is used only when the JVM runs with {@code --add-modules jdk.incubator.vector}; without the
     * flag, or when this is false, the scalar kernels run. Default: true.
     */
    public boolean useVector() { return useVector; }

    /**
     * {@return the worker threads of one engine}
     * Detection of one page uses them all, and that many lines, or pages of a PDF, are read at a
     * time. Default: the processors of the machine. An {@link OcrPool} divides this number between
     * its engines.
     */
    public int threads() { return threads; }

    /**
     * {@return the side length in pixels that {@link #detLimitType()} holds the page to before detection}
     * Default: 2048. 960 is faster and needs less heap, and misses small print.
     */
    public int detLimitSideLen() { return detLimitSideLen; }

    /**
     * {@return which side of the page {@link #detLimitSideLen()} limits}
     * Default: {@link DetLimit#MAX}.
     */
    public DetLimit detLimitType() { return detLimitType; }

    /**
     * {@return the probability above which a pixel of the detector's map counts as text}
     * Default: 0.35.
     */
    public float detThresh() { return detThresh; }

    /**
     * {@return the mean probability inside a box below which the box is dropped}
     * Default: 0.5.
     */
    public float boxThresh() { return boxThresh; }

    /**
     * {@return how far a detected box is grown outward, since the detector outlines the core of a line}
     * Default: 1.6.
     */
    public float unclipRatio() { return unclipRatio; }

    /**
     * {@return whether the text map is dilated by 2×2 before boxes are traced}
     * Default: true.
     */
    public boolean useDilation() { return useDilation; }

    /**
     * {@return the most outlines taken from the text map of one page}
     * Default: 1000.
     */
    public int maxCandidates() { return maxCandidates; }

    /**
     * {@return the probability above which the classifier's answer turns a line by 180 degrees}
     * Default: 0.9.
     */
    public float clsThresh() { return clsThresh; }

    /**
     * {@return whether the orientation classifier runs on every line}
     * Default: true.
     */
    public boolean useCls() { return useCls; }

    /**
     * {@return whether lines are detected}
     * Without detection the whole image is read as one line. Default: true.
     */
    public boolean useDet() { return useDet; }

    /**
     * {@return whether lines are recognized}
     * Without recognition every line has its box and an empty text. Default: true.
     */
    public boolean useRec() { return useRec; }

    /**
     * {@return the height in pixels a line is scaled to for the recognizer}
     * It must be the input height of the model. Default: 48.
     */
    public int recHeight() { return recHeight; }

    /**
     * {@return the width in pixels a shorter line is padded to on the right for the recognizer}
     * Default: 320.
     */
    public int recMinWidth() { return recMinWidth; }

    /**
     * {@return the widest recognizer input in pixels}
     * A longer line is squeezed to it. Default: 4096.
     */
    public int recMaxWidth() { return recMaxWidth; }

    /**
     * {@return the line score below which a line is dropped, see {@link TextLine#score()}}
     * Default: 0.5.
     */
    public float recThresh() { return recThresh; }

    /**
     * {@return whether a space is added to the end of the dictionary, as PP-OCR dictionaries expect}
     * Default: true.
     */
    public boolean useSpaceChar() { return useSpaceChar; }

    /**
     * {@return whether a box at least 1.5 times as tall as it is wide is turned by a quarter before reading}
     * Default: true.
     */
    public boolean rotateVertical() { return rotateVertical; }

    /**
     * {@return whether the page is turned level before detection}
     * Default: false; with these models it often hurts.
     */
    public boolean deskew() { return deskew; }

    /**
     * {@return whether the page is made black and white (Sauvola) before detection}
     * Default: false; with these models it often hurts.
     */
    public boolean adaptiveThreshold() { return adaptiveThreshold; }

    /** Experimental int8 path for 1×1 convolutions; slower and less exact in pure Java, so not part of the API. */
    boolean quantize() { return quantize; }

    /**
     * {@return the value subtracted from a channel scaled to 0..1 before the networks}
     * Default: 0.5.
     */
    public float mean() { return mean; }

    /**
     * {@return the value a channel is divided by after {@link #mean()} is subtracted}
     * Default: 0.5.
     */
    public float std() { return std; }

    /**
     * {@return the text repairs applied after a line is read}
     * Default: {@link Corrections#NONE}.
     */
    public Corrections corrections() { return corrections; }

    /**
     * {@return how the lines of a page are put in reading order}
     * Default: {@link Layout#AUTO}.
     */
    public Layout layout() { return layout; }

    /**
     * {@return which characters the recognition model may output}
     * Default: {@link Alphabet#ALL}.
     */
    public Alphabet alphabet() { return alphabet; }

    /** {@return the word lists of the word search, unmodifiable; see {@link Builder#wordList(ModelSource)}} */
    public List<ModelSource> wordLists() { return wordLists; }

    /** {@return whether short, unsure lines are left out, see {@link Builder#dropFragments(boolean)}} */
    public boolean dropFragments() { return dropFragments; }

    /**
     * {@return whether a page that lies on its side or upside down is turned before it is read}
     * A page is on its side when most of its text stands in boxes on their short side, and
     * upside down when most of its long lines read that way to the orientation classifier. The
     * result still gives every line in the pixels of the picture as it was given, and
     * {@link OcrResult#rotation()} tells the turn. Default: true. Turn it off for pages that
     * are set in vertical lines on purpose.
     */
    public boolean pageOrientation() { return pageOrientation; }

    /** {@return a builder with the default settings and no models} */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * PP-OCRv5 detection and angle classifier, plus PP-OCRv6 small recognition, from
     * {@code ocr-pro-models} on the class path. With {@code ocr-pro-models-pl} there too,
     * {@link Corrections#POLISH} also finds its word lists. Settings as in {@link #defaults(Path)}.
     *
     * @return the default configuration
     */
    public static OcrConfig defaults() {
        return builder()
                .detModel(ModelSource.bundled(ModelFiles.DET))
                .recModel(ModelSource.bundled(ModelFiles.REC))
                .clsModel(ModelSource.bundled(ModelFiles.CLS))
                .dict(ModelSource.bundled(ModelFiles.DICT))
                .build();
    }

    /**
     * PP-OCRv5 detection and angle classifier, plus PP-OCRv6 small recognition, from a directory.
     * No text corrections and automatic layout; see {@link Builder#corrections(Corrections)} and
     * {@link Builder#layout(Layout)}.
     * An existing file is rejected. A missing directory is left for the loader.
     *
     * @param modelDirectory the directory with the files named in {@link ModelFiles}
     * @return the default configuration
     * @throws OcrException when {@code modelDirectory} is null or a file
     */
    public static OcrConfig defaults(Path modelDirectory) {
        if (modelDirectory == null) {
            throw new OcrException("model directory is null");
        }
        ModelFiles.requireDirectory(modelDirectory);
        return builder()
                .detModel(modelDirectory.resolve(ModelFiles.DET))
                .recModel(modelDirectory.resolve(ModelFiles.REC))
                .clsModel(modelDirectory.resolve(ModelFiles.CLS))
                .dict(modelDirectory.resolve(ModelFiles.DICT))
                .build();
    }

    /** {@return a builder that starts from these settings} */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Collects the settings of an {@link OcrConfig}. Every setter returns this builder; the
     * getter of the same name on {@link OcrConfig} says what the setting does and gives its default.
     */
    public static final class Builder {
        private ModelSource detModel;
        private ModelSource recModel;
        private ModelSource clsModel;
        private ModelSource dict;
        private boolean useVector = true;
        private int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        private int detLimitSideLen = 2048;
        private DetLimit detLimitType = DetLimit.MAX;
        private float detThresh = 0.35f;
        private float boxThresh = 0.5f;
        private float unclipRatio = 1.6f;
        private boolean useDilation = true;
        private int maxCandidates = 1000;
        private float clsThresh = 0.9f;
        private boolean useCls = true;
        private boolean useDet = true;
        private boolean useRec = true;
        private int recHeight = 48;
        private int recMinWidth = 320;
        private int recMaxWidth = 4096;
        private float recThresh = 0.5f;
        private boolean useSpaceChar = true;
        private boolean rotateVertical = true;
        private boolean deskew = false;
        private boolean adaptiveThreshold = false;
        private boolean quantize = false;
        private float mean = 0.5f;
        private float std = 0.5f;
        private Corrections corrections = Corrections.NONE;
        private Layout layout = Layout.AUTO;
        private Alphabet alphabet = Alphabet.ALL;
        private final List<ModelSource> wordLists = new ArrayList<>();
        private boolean dropFragments = false;
        private boolean pageOrientation = true;

        /** A builder with the default settings and no models, as {@link OcrConfig#builder()} returns. */
        public Builder() {
        }

        private Builder(OcrConfig c) {
            this.detModel = c.detModel;
            this.recModel = c.recModel;
            this.clsModel = c.clsModel;
            this.dict = c.dict;
            this.useVector = c.useVector;
            this.threads = c.threads;
            this.detLimitSideLen = c.detLimitSideLen;
            this.detLimitType = c.detLimitType;
            this.detThresh = c.detThresh;
            this.boxThresh = c.boxThresh;
            this.unclipRatio = c.unclipRatio;
            this.useDilation = c.useDilation;
            this.maxCandidates = c.maxCandidates;
            this.clsThresh = c.clsThresh;
            this.useCls = c.useCls;
            this.useDet = c.useDet;
            this.useRec = c.useRec;
            this.recHeight = c.recHeight;
            this.recMinWidth = c.recMinWidth;
            this.recMaxWidth = c.recMaxWidth;
            this.recThresh = c.recThresh;
            this.useSpaceChar = c.useSpaceChar;
            this.rotateVertical = c.rotateVertical;
            this.deskew = c.deskew;
            this.adaptiveThreshold = c.adaptiveThreshold;
            this.quantize = c.quantize;
            this.mean = c.mean;
            this.std = c.std;
            this.corrections = c.corrections;
            this.layout = c.layout;
            this.alphabet = c.alphabet;
            this.wordLists.addAll(c.wordLists);
            this.dropFragments = c.dropFragments;
            this.pageOrientation = c.pageOrientation;
        }

        /**
         * Sets {@link OcrConfig#detModel()}. The file is read when the engine is created.
         *
         * @param v the model file, or null for none
         * @return this builder
         */
        public Builder detModel(Path v) { detModel = v == null ? null : ModelSource.of(v); return this; }

        /**
         * Sets {@link OcrConfig#recModel()}. The file is read when the engine is created.
         *
         * @param v the model file, or null for none
         * @return this builder
         */
        public Builder recModel(Path v) { recModel = v == null ? null : ModelSource.of(v); return this; }

        /**
         * Sets {@link OcrConfig#clsModel()}. The file is read when the engine is created.
         *
         * @param v the model file, or null for none
         * @return this builder
         */
        public Builder clsModel(Path v) { clsModel = v == null ? null : ModelSource.of(v); return this; }

        /**
         * Sets {@link OcrConfig#dict()}. The file is read when the engine is created.
         *
         * @param v the dictionary file, or null for none
         * @return this builder
         */
        public Builder dict(Path v) { dict = v == null ? null : ModelSource.of(v); return this; }

        /**
         * Sets {@link OcrConfig#detModel()}.
         *
         * @param v the model, or null for none
         * @return this builder
         */
        public Builder detModel(ModelSource v) { detModel = v; return this; }

        /**
         * Sets {@link OcrConfig#recModel()}.
         *
         * @param v the model, or null for none
         * @return this builder
         */
        public Builder recModel(ModelSource v) { recModel = v; return this; }

        /**
         * Sets {@link OcrConfig#clsModel()}.
         *
         * @param v the model, or null for none
         * @return this builder
         */
        public Builder clsModel(ModelSource v) { clsModel = v; return this; }

        /**
         * Sets {@link OcrConfig#dict()}.
         *
         * @param v the dictionary, or null for none
         * @return this builder
         */
        public Builder dict(ModelSource v) { dict = v; return this; }

        /**
         * Sets {@link OcrConfig#useVector()}.
         *
         * @param v false for the scalar kernels
         * @return this builder
         */
        public Builder useVector(boolean v) { useVector = v; return this; }

        /**
         * Sets {@link OcrConfig#threads()}.
         *
         * @param v the thread count, at least 1
         * @return this builder
         */
        public Builder threads(int v) { threads = v; return this; }

        /**
         * Sets {@link OcrConfig#detLimitSideLen()}.
         *
         * @param v the side length in pixels, at least 1
         * @return this builder
         */
        public Builder detLimitSideLen(int v) { detLimitSideLen = v; return this; }

        /**
         * Sets {@link OcrConfig#detLimitType()}.
         *
         * @param v the side to limit
         * @return this builder
         */
        public Builder detLimitType(DetLimit v) { detLimitType = v; return this; }

        /**
         * Sets {@link OcrConfig#detThresh()}.
         *
         * @param v the threshold, 0 to 1
         * @return this builder
         */
        public Builder detThresh(float v) { detThresh = v; return this; }

        /**
         * Sets {@link OcrConfig#boxThresh()}.
         *
         * @param v the threshold, 0 to 1
         * @return this builder
         */
        public Builder boxThresh(float v) { boxThresh = v; return this; }

        /**
         * Sets {@link OcrConfig#unclipRatio()}.
         *
         * @param v the ratio
         * @return this builder
         */
        public Builder unclipRatio(float v) { unclipRatio = v; return this; }

        /**
         * Sets {@link OcrConfig#useDilation()}.
         *
         * @param v true to dilate
         * @return this builder
         */
        public Builder useDilation(boolean v) { useDilation = v; return this; }

        /**
         * Sets {@link OcrConfig#maxCandidates()}.
         *
         * @param v the count, at least 1
         * @return this builder
         */
        public Builder maxCandidates(int v) { maxCandidates = v; return this; }

        /**
         * Sets {@link OcrConfig#clsThresh()}.
         *
         * @param v the threshold, 0 to 1
         * @return this builder
         */
        public Builder clsThresh(float v) { clsThresh = v; return this; }

        /**
         * Sets {@link OcrConfig#useCls()}.
         *
         * @param v false to skip the classifier
         * @return this builder
         */
        public Builder useCls(boolean v) { useCls = v; return this; }

        /**
         * Sets {@link OcrConfig#useDet()}.
         *
         * @param v false to skip detection
         * @return this builder
         */
        public Builder useDet(boolean v) { useDet = v; return this; }

        /**
         * Sets {@link OcrConfig#useRec()}.
         *
         * @param v false to skip recognition
         * @return this builder
         */
        public Builder useRec(boolean v) { useRec = v; return this; }

        /**
         * Sets {@link OcrConfig#recHeight()}.
         *
         * @param v the height in pixels
         * @return this builder
         */
        public Builder recHeight(int v) { recHeight = v; return this; }

        /**
         * Sets {@link OcrConfig#recMinWidth()}.
         *
         * @param v the width in pixels
         * @return this builder
         */
        public Builder recMinWidth(int v) { recMinWidth = v; return this; }

        /**
         * Sets {@link OcrConfig#recMaxWidth()}.
         *
         * @param v the width in pixels, at least 1
         * @return this builder
         */
        public Builder recMaxWidth(int v) { recMaxWidth = v; return this; }

        /**
         * Sets {@link OcrConfig#recThresh()}.
         *
         * @param v the threshold, 0 to 1
         * @return this builder
         */
        public Builder recThresh(float v) { recThresh = v; return this; }

        /**
         * Sets {@link OcrConfig#useSpaceChar()}.
         *
         * @param v false when the dictionary lists its own space
         * @return this builder
         */
        public Builder useSpaceChar(boolean v) { useSpaceChar = v; return this; }

        /**
         * Sets {@link OcrConfig#rotateVertical()}.
         *
         * @param v false to read tall boxes as they are
         * @return this builder
         */
        public Builder rotateVertical(boolean v) { rotateVertical = v; return this; }

        /**
         * Sets {@link OcrConfig#deskew()}.
         *
         * @param v true to level the page first
         * @return this builder
         */
        public Builder deskew(boolean v) { deskew = v; return this; }

        /**
         * Sets {@link OcrConfig#adaptiveThreshold()}.
         *
         * @param v true to binarize the page first
         * @return this builder
         */
        public Builder adaptiveThreshold(boolean v) { adaptiveThreshold = v; return this; }

        Builder quantize(boolean v) { quantize = v; return this; }

        /**
         * Sets {@link OcrConfig#mean()}.
         *
         * @param v the mean
         * @return this builder
         */
        public Builder mean(float v) { mean = v; return this; }

        /**
         * Sets {@link OcrConfig#std()}.
         *
         * @param v the divisor, above 0
         * @return this builder
         */
        public Builder std(float v) { std = v; return this; }

        /**
         * Sets {@link OcrConfig#corrections()}.
         *
         * @param v the repairs
         * @return this builder
         */
        public Builder corrections(Corrections v) { corrections = v; return this; }

        /**
         * Sets {@link OcrConfig#layout()}.
         *
         * @param v the layout analysis
         * @return this builder
         */
        public Builder layout(Layout v) { layout = v; return this; }

        /**
         * Sets {@link OcrConfig#alphabet()}.
         *
         * @param v the alphabet
         * @return this builder
         */
        public Builder alphabet(Alphabet v) { alphabet = v; return this; }

        /**
         * Adds a word list in any language: UTF-8, one word per line, plain or gzip, in any order and
         * case. A recognized word that no list holds is searched again on the recognizer's output,
         * and a listed word with the same number of letters replaces it when the image supports
         * that spelling almost as well. Words the lists hold, numbers and punctuation stay as read.
         * This runs with any {@link Corrections}; {@link Corrections#POLISH} adds its own Polish and
         * English lists to these.
         *
         * @param v the list
         * @return this builder
         * @throws OcrException when {@code v} is null
         */
        public Builder wordList(ModelSource v) {
            if (v == null) {
                throw new OcrException("word list is null");
            }
            wordLists.add(v);
            return this;
        }

        /**
         * Adds a word list from a file, see {@link #wordList(ModelSource)}.
         *
         * @param v the file, read when the engine is created
         * @return this builder
         * @throws OcrException when {@code v} is null
         */
        public Builder wordList(Path v) {
            if (v == null) {
                throw new OcrException("word list is null");
            }
            return wordList(ModelSource.of(v));
        }

        /**
         * Leaves out a line of at most 10 characters whose mean character probability is below
         * 0.8. Such lines are mostly stamps, bleed-through, ornaments and stray marks read as
         * text. Off by default, because it also drops real short text the recognizer is unsure
         * of, such as an {@code X} in a check box or a misread page number.
         *
         * @param v true to leave such lines out
         * @return this builder
         */
        public Builder dropFragments(boolean v) { dropFragments = v; return this; }

        /**
         * Sets {@link OcrConfig#pageOrientation()}.
         *
         * @param v false to read every page as it is given
         * @return this builder
         */
        public Builder pageOrientation(boolean v) { pageOrientation = v; return this; }

        /**
         * Replaces every word list added so far; an empty list turns the search off.
         *
         * @param v the lists
         * @return this builder
         * @throws OcrException when the list or one of its entries is null
         */
        public Builder wordLists(List<ModelSource> v) {
            if (v == null) {
                throw new OcrException("word lists are null");
            }
            List<ModelSource> copy = new ArrayList<>(v.size());
            for (ModelSource source : v) {
                if (source == null) {
                    throw new OcrException("word list is null");
                }
                copy.add(source);
            }
            wordLists.clear();
            wordLists.addAll(copy);
            return this;
        }

        /**
         * Checks the settings and makes the configuration. The models are not opened here.
         *
         * @return the configuration
         * @throws OcrException when a setting is null, not finite or out of its range
         */
        public OcrConfig build() {
            if (threads < 1) {
                throw new OcrException("threads must be >= 1");
            }
            if (corrections == null) {
                throw new OcrException("corrections is null");
            }
            if (layout == null) {
                throw new OcrException("layout is null");
            }
            if (alphabet == null) {
                throw new OcrException("alphabet is null");
            }
            if (!Float.isFinite(mean)) {
                throw new OcrException("mean must be finite");
            }
            if (!Float.isFinite(std) || std <= 0f) {
                throw new OcrException("std must be positive");
            }
            if (detLimitSideLen < 1) {
                throw new OcrException("detLimitSideLen must be >= 1");
            }
            if (detLimitType == null) {
                throw new OcrException("detLimitType is null");
            }
            if (!Float.isFinite(detThresh) || !Float.isFinite(boxThresh) || !Float.isFinite(unclipRatio)) {
                throw new OcrException("detection thresholds must be finite");
            }
            if (!Float.isFinite(clsThresh) || !Float.isFinite(recThresh)) {
                throw new OcrException("score thresholds must be finite");
            }
            if (recHeight < 1 || recMaxWidth < 1) {
                throw new OcrException("recognition size must be >= 1");
            }
            if (maxCandidates < 1) {
                throw new OcrException("maxCandidates must be >= 1");
            }
            // 3 * height * width is the REC float tensor. Reject a product that cannot be allocated.
            if (recMaxWidth > (Integer.MAX_VALUE / 3) / recHeight) {
                throw new OcrException("recognition tensor is too large");
            }
            return new OcrConfig(this);
        }
    }
}
