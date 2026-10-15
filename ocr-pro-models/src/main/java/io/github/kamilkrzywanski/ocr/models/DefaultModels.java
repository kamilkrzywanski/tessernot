package io.github.kamilkrzywanski.ocr.models;

import io.github.kamilkrzywanski.ocr.ModelBundle;
import io.github.kamilkrzywanski.ocr.ModelFiles;

import java.io.InputStream;
import java.util.Set;

/**
 * The default models of {@link io.github.kamilkrzywanski.ocr.OcrConfig#defaults()}: PP-OCRv5
 * detection and angle classifier, PP-OCRv6 small recognition and its dict, unchanged copies of the
 * files {@link ModelFiles#ensure} downloads. Licenses and authors are in {@code META-INF/models/NOTICE}.
 */
public final class DefaultModels implements ModelBundle {
    private static final Set<String> FILES = Set.of(ModelFiles.DET, ModelFiles.REC, ModelFiles.CLS, ModelFiles.DICT);

    @Override
    public InputStream open(String name) {
        return FILES.contains(name) ? DefaultModels.class.getResourceAsStream(name) : null;
    }
}
