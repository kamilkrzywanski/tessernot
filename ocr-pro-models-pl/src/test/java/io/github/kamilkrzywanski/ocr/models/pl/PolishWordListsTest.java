package io.github.kamilkrzywanski.ocr.models.pl;

import io.github.kamilkrzywanski.ocr.Corrections;
import io.github.kamilkrzywanski.ocr.ModelFiles;
import io.github.kamilkrzywanski.ocr.OcrConfig;
import io.github.kamilkrzywanski.ocr.OcrEngine;
import io.github.kamilkrzywanski.ocr.internal.eval.Samples;
import io.github.kamilkrzywanski.ocr.internal.post.Lexicon;
import io.github.kamilkrzywanski.ocr.internal.post.PostalCities;
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

class PolishWordListsTest {
    @Test
    void bundleOpensOnlyTheWordLists() throws Exception {
        PolishWordLists bundle = new PolishWordLists();
        for (String name : new String[]{Lexicon.POLISH_FILE, Lexicon.ENGLISH_FILE, PostalCities.FILE}) {
            try (InputStream in = bundle.open(name)) {
                assertNotNull(in, name);
                assertTrue(in.read() >= 0, name);
            }
        }
        assertNull(bundle.open(ModelFiles.DET));
        assertNull(bundle.open("../" + Lexicon.POLISH_FILE));
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void polishCorrectionsReadLikeTheDirectory() {
        assumeTrue(Files.exists(Path.of("models", Lexicon.POLISH_FILE)), "models/ is missing");
        BufferedImage invoice = Samples.invoice();
        String bundled;
        try (OcrEngine engine = OcrEngine.create(OcrConfig.defaults().toBuilder().corrections(Corrections.POLISH).build())) {
            bundled = engine.recognize(invoice).text();
        }
        String directory;
        try (OcrEngine engine = OcrEngine.create(
                OcrConfig.defaults(Path.of("models")).toBuilder().corrections(Corrections.POLISH).build())) {
            directory = engine.recognize(invoice).text();
        }
        assertEquals(directory, bundled);
    }
}
