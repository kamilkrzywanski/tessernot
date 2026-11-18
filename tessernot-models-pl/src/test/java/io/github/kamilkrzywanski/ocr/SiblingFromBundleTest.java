package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.post.Lexicon;
import io.github.kamilkrzywanski.ocr.internal.post.PostalCities;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** In the engine's package: {@code sibling} is what the engine asks for the word lists next to the dict. */
class SiblingFromBundleTest {
    @Test
    void wordListsMissingNextToTheDictComeFromThisBundle() throws IOException {
        Path dir = Files.createTempDirectory("ocr-dict-only");
        Path dict = dir.resolve(ModelFiles.DICT);
        Path polish = dir.resolve(Lexicon.POLISH_FILE);
        try {
            Files.writeString(dict, "a\n");
            ModelSource source = ModelSource.of(dict);
            for (String name : new String[]{Lexicon.POLISH_FILE, Lexicon.ENGLISH_FILE, PostalCities.FILE}) {
                ModelSource sibling = source.sibling(name);
                assertNotNull(sibling, name);
                assertTrue(sibling.isBundled(), name);
                assertEquals(name, sibling.name());
                assertTrue(sibling.read().length > 0, name);
            }
            assertNull(source.sibling("absent.txt.gz"));

            // A file on disk still wins over the bundle.
            Files.write(polish, new byte[]{1});
            ModelSource onDisk = source.sibling(Lexicon.POLISH_FILE);
            assertNotNull(onDisk);
            assertFalse(onDisk.isBundled());
            assertEquals(polish, onDisk.path());
        } finally {
            Files.deleteIfExists(polish);
            Files.deleteIfExists(dict);
            Files.deleteIfExists(dir);
        }
    }
}
