package io.github.kamilkrzywanski.ocr;

import java.nio.file.Path;

/**
 * Inference settings. Defaults follow RapidOCR mobile:
 * det limit 960, DB thresh 0.35 / box 0.5 / unclip 1.6, REC height 48, BGR normalize 0.5/0.5.
 */
public final class OcrConfig {
    public final Path detModel;
    public final Path recModel;
    public final Path clsModel;
    public final Path dict;
    public final boolean useVector;
    public final int threads;
    public final int detLimitSideLen;
    public final String detLimitType;
    public final float detThresh;
    public final float boxThresh;
    public final float unclipRatio;
    public final boolean useDilation;
    public final int maxCandidates;
    public final float clsThresh;
    public final boolean useCls;
    public final boolean useDet;
    public final boolean useRec;
    public final int recHeight;
    public final int recMinWidth;
    public final int recMaxWidth;
    public final float recThresh;
    public final boolean useSpaceChar;
    public final boolean rotateVertical;
    public final boolean deskew;
    public final boolean adaptiveThreshold;
    public final boolean quantize;
    public final float mean;
    public final float std;
    public final boolean opDebug;

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
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * PP-OCRv5 detection and angle classifier, plus PP-OCRv6 small recognition, from a directory.
     * An existing file is rejected. A missing directory is left for the loader.
     */
    public static OcrConfig latin(Path modelDirectory) {
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
        private Path detModel;
        private Path recModel;
        private Path clsModel;
        private Path dict;
        private boolean useVector = true;
        private int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        private int detLimitSideLen = 960;
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
        }

        public Builder detModel(Path v) { detModel = v; return this; }
        public Builder recModel(Path v) { recModel = v; return this; }
        public Builder clsModel(Path v) { clsModel = v; return this; }
        public Builder dict(Path v) { dict = v; return this; }
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

        public OcrConfig build() {
            if (threads < 1) {
                throw new OcrException("threads must be >= 1");
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
