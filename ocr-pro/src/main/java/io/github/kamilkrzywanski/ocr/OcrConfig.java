package io.github.kamilkrzywanski.ocr;

import java.nio.file.Path;

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
    private final String detLimitType;
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
    private final boolean opDebug;
    private final Corrections corrections;
    private final Layout layout;
    private final Alphabet alphabet;

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
        this.opDebug = b.opDebug;
        this.corrections = b.corrections;
        this.layout = b.layout;
        this.alphabet = b.alphabet;
    }

    public ModelSource detModel() { return detModel; }
    public ModelSource recModel() { return recModel; }
    public ModelSource clsModel() { return clsModel; }
    public ModelSource dict() { return dict; }
    public boolean useVector() { return useVector; }
    public int threads() { return threads; }
    public int detLimitSideLen() { return detLimitSideLen; }
    public String detLimitType() { return detLimitType; }
    public float detThresh() { return detThresh; }
    public float boxThresh() { return boxThresh; }
    public float unclipRatio() { return unclipRatio; }
    public boolean useDilation() { return useDilation; }
    public int maxCandidates() { return maxCandidates; }
    public float clsThresh() { return clsThresh; }
    public boolean useCls() { return useCls; }
    public boolean useDet() { return useDet; }
    public boolean useRec() { return useRec; }
    public int recHeight() { return recHeight; }
    public int recMinWidth() { return recMinWidth; }
    public int recMaxWidth() { return recMaxWidth; }
    public float recThresh() { return recThresh; }
    public boolean useSpaceChar() { return useSpaceChar; }
    public boolean rotateVertical() { return rotateVertical; }
    public boolean deskew() { return deskew; }
    public boolean adaptiveThreshold() { return adaptiveThreshold; }
    public boolean quantize() { return quantize; }
    public float mean() { return mean; }
    public float std() { return std; }
    public boolean opDebug() { return opDebug; }
    public Corrections corrections() { return corrections; }
    public Layout layout() { return layout; }
    public Alphabet alphabet() { return alphabet; }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * PP-OCRv5 detection and angle classifier, plus PP-OCRv6 small recognition, from
     * {@code ocr-pro-models} on the class path. With {@code ocr-pro-models-pl} there too,
     * {@link Corrections#POLISH} also finds its word lists. Settings as in {@link #defaults(Path)}.
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

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private ModelSource detModel;
        private ModelSource recModel;
        private ModelSource clsModel;
        private ModelSource dict;
        private boolean useVector = true;
        private int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        private int detLimitSideLen = 2048;
        private String detLimitType = "max";
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
        private boolean opDebug = false;
        private Corrections corrections = Corrections.NONE;
        private Layout layout = Layout.AUTO;
        private Alphabet alphabet = Alphabet.ALL;

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
            this.opDebug = c.opDebug;
            this.corrections = c.corrections;
            this.layout = c.layout;
            this.alphabet = c.alphabet;
        }

        public Builder detModel(Path v) { detModel = v == null ? null : ModelSource.of(v); return this; }
        public Builder recModel(Path v) { recModel = v == null ? null : ModelSource.of(v); return this; }
        public Builder clsModel(Path v) { clsModel = v == null ? null : ModelSource.of(v); return this; }
        public Builder dict(Path v) { dict = v == null ? null : ModelSource.of(v); return this; }
        public Builder detModel(ModelSource v) { detModel = v; return this; }
        public Builder recModel(ModelSource v) { recModel = v; return this; }
        public Builder clsModel(ModelSource v) { clsModel = v; return this; }
        public Builder dict(ModelSource v) { dict = v; return this; }
        public Builder useVector(boolean v) { useVector = v; return this; }
        public Builder threads(int v) { threads = v; return this; }
        public Builder detLimitSideLen(int v) { detLimitSideLen = v; return this; }
        public Builder detLimitType(String v) { detLimitType = v; return this; }
        public Builder detThresh(float v) { detThresh = v; return this; }
        public Builder boxThresh(float v) { boxThresh = v; return this; }
        public Builder unclipRatio(float v) { unclipRatio = v; return this; }
        public Builder useDilation(boolean v) { useDilation = v; return this; }
        public Builder maxCandidates(int v) { maxCandidates = v; return this; }
        public Builder clsThresh(float v) { clsThresh = v; return this; }
        public Builder useCls(boolean v) { useCls = v; return this; }
        public Builder useDet(boolean v) { useDet = v; return this; }
        public Builder useRec(boolean v) { useRec = v; return this; }
        public Builder recHeight(int v) { recHeight = v; return this; }
        public Builder recMinWidth(int v) { recMinWidth = v; return this; }
        public Builder recMaxWidth(int v) { recMaxWidth = v; return this; }
        public Builder recThresh(float v) { recThresh = v; return this; }
        public Builder useSpaceChar(boolean v) { useSpaceChar = v; return this; }
        public Builder rotateVertical(boolean v) { rotateVertical = v; return this; }
        public Builder deskew(boolean v) { deskew = v; return this; }
        public Builder adaptiveThreshold(boolean v) { adaptiveThreshold = v; return this; }
        public Builder quantize(boolean v) { quantize = v; return this; }
        public Builder mean(float v) { mean = v; return this; }
        public Builder std(float v) { std = v; return this; }
        public Builder opDebug(boolean v) { opDebug = v; return this; }
        public Builder corrections(Corrections v) { corrections = v; return this; }
        public Builder layout(Layout v) { layout = v; return this; }
        public Builder alphabet(Alphabet v) { alphabet = v; return this; }

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
            if (!"max".equals(detLimitType) && !"min".equals(detLimitType)) {
                throw new OcrException("detLimitType must be max or min");
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
