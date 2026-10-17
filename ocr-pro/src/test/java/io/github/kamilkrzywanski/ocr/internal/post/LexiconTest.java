package io.github.kamilkrzywanski.ocr.internal.post;

import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.ocr.OcrException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexiconTest {
    private final Lexicon lexicon = Lexicon.of(
            new String[] {"żniwna", "świat", "spółka"},
            new String[] {"state", "poland", "hello"});

    @Test
    void bothListsAndTheReceiptStemAreWordsAndPrefixes() {
        assertTrue(lexicon.word("żniwna"));
        assertTrue(lexicon.word("poland"));
        assertTrue(lexicon.word("polędw"));
        assertFalse(lexicon.word("zniwna"));
        assertFalse(lexicon.word("Poland"));
        assertTrue(lexicon.prefix("żni"));
        assertTrue(lexicon.prefix("pol"));
        assertTrue(lexicon.prefix("polę"));
        assertFalse(lexicon.prefix("zni"));
    }

    @Test
    void wordListFindsLongerWordsForAPrefix() {
        WordList list = WordList.of("przewód", "przewodniczący", "ser", "serce");
        assertTrue(list.extendsPrefix("przewod"));
        assertTrue(list.extendsPrefix("ser"));
        assertFalse(list.extendsPrefix("serce"));
        assertFalse(list.extendsPrefix("sera"));
        assertFalse(list.extendsPrefix("zzz"));
        assertFalse(list.extendsPrefix(""));
    }

    @Test
    void wordListDoesNotMatchAPrefix() {
        WordList list = WordList.of("ser", "serce", "ą");
        assertTrue(list.contains("ser"));
        assertTrue(list.contains("serce"));
        assertTrue(list.contains("ą"));
        assertFalse(list.contains("se"));
        assertFalse(list.contains("serc"));
        assertFalse(list.contains(""));
    }

    @Test
    void optionalLoadsGzipListsBesideTheModels() throws Exception {
        Path dir = Files.createTempDirectory("lexicon");
        writeGz(dir.resolve(Lexicon.POLISH_FILE), "magazyn\n");
        assertNull(Lexicon.optional(dir));
        writeGz(dir.resolve(Lexicon.ENGLISH_FILE), "poland\n");
        Lexicon loaded = Lexicon.optional(dir);
        assertTrue(loaded.word("magazyn"));
        assertTrue(loaded.word("poland"));
        assertNull(Lexicon.optional(null));
        Files.writeString(dir.resolve(Lexicon.POLISH_FILE), "not gzip");
        assertThrows(OcrException.class, () -> Lexicon.optional(dir));
    }

    private static void writeGz(Path path, String text) throws Exception {
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
