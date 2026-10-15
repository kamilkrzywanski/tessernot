package io.github.kamilkrzywanski.ocr.models;

import io.github.kamilkrzywanski.ocr.ModelFiles;
import io.github.kamilkrzywanski.ocr.OcrConfig;
import io.github.kamilkrzywanski.ocr.OcrEngine;
import io.github.kamilkrzywanski.ocr.OcrResult;
import io.github.kamilkrzywanski.ocr.internal.eval.Samples;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DefaultModelsTest {
    @Test
    void bundleOpensOnlyTheDefaultModels() throws Exception {
        DefaultModels bundle = new DefaultModels();
        for (String name : new String[]{ModelFiles.DET, ModelFiles.REC, ModelFiles.CLS, ModelFiles.DICT}) {
            try (InputStream in = bundle.open(name)) {
                assertNotNull(in, name);
                assertTrue(in.read() >= 0, name);
            }
        }
        assertNull(bundle.open("lexicon-pl.txt.gz"));
        assertNull(bundle.open("../models/" + ModelFiles.DET));
        assertNull(bundle.open("DefaultModels.class"));
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void bundledModelsReadLikeTheDirectory() {
        assumeTrue(Files.exists(Path.of("models", ModelFiles.REC)), "models/ is missing");
        BufferedImage invoice = Samples.invoice();
        OcrResult bundled;
        try (OcrEngine engine = OcrEngine.load()) {
            bundled = engine.recognize(invoice);
        }
        OcrResult directory;
        try (OcrEngine engine = OcrEngine.create(OcrConfig.defaults(Path.of("models")))) {
            directory = engine.recognize(invoice);
        }
        assertTrue(bundled.text().contains("FAKTURA"), bundled.text());
        assertEquals(directory.text(), bundled.text());
        assertEquals(directory.lines().size(), bundled.lines().size());
    }
}
