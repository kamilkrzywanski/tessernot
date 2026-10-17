package io.github.kamilkrzywanski.ocr.internal.post;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CtcWordsTest {
    private final Charset charset = Charset.of("s", "i", "e", "ę", "-", " ");
    private final Lexicon lexicon = Lexicon.of(new String[] {"się", "sieć"}, new String[] {"see"});

    /** Rows of blank, s, i, e, ę, hyphen, space. */
    private static float[] steps(float[]... rows) {
        float[] out = new float[rows.length * 7];
        for (int t = 0; t < rows.length; t++) {
            System.arraycopy(rows[t], 0, out, t * 7, 7);
        }
        return out;
    }

    @Test
    void aListedSpellingTheRecognizerAlsoSawReplacesTheGreedyWord() {
        float[] probs = steps(
                new float[] {0, 1, 0, 0, 0, 0, 0},
                new float[] {0, 0, 1, 0, 0, 0, 0},
                new float[] {0, 0, 0, 0.7f, 0.3f, 0, 0},
                new float[] {1, 0, 0, 0, 0, 0, 0});
        assertEquals("się", Ctc.greedy(probs, 4, 7, charset, false, lexicon).text());
        assertEquals("sie", Ctc.greedy(probs, 4, 7, charset, false, null).text());
    }

    @Test
    void oneUnseenMarkIsAllowedOnALineWithMarksButNotAnotherLetter() {
        float[] probs = steps(
                new float[] {0, 1, 0, 0, 0, 0, 0},
                new float[] {0, 0, 1, 0, 0, 0, 0},
                new float[] {0, 0, 0, 0.99999f, 0.00001f, 0, 0},
                new float[] {1, 0, 0, 0, 0, 0, 0});
        assertEquals("sie", Ctc.greedy(probs, 4, 7, charset, false, lexicon).text());
        float[] marked = steps(
                new float[] {0, 0, 0, 0, 1, 0, 0},
                new float[] {0, 0, 0, 0, 0, 0, 1},
                new float[] {0, 1, 0, 0, 0, 0, 0},
                new float[] {0, 0, 1, 0, 0, 0, 0},
                new float[] {0, 0, 0, 0.99999f, 0.00001f, 0, 0},
                new float[] {1, 0, 0, 0, 0, 0, 0});
        // ę elsewhere on the line shows that this print has marks.
        assertEquals("ę się", Ctc.greedy(marked, 6, 7, charset, false, lexicon).text());
        Lexicon other = Lexicon.of(new String[] {"sis"}, new String[] {"see"});
        assertEquals("sie", Ctc.greedy(probs, 4, 7, charset, false, other).text());
    }

    @Test
    void aListedPrefixBeforeAHyphenIsKnown() {
        float[] probs = steps(
                new float[] {0, 1, 0, 0, 0, 0, 0},
                new float[] {0, 0, 1, 0, 0, 0, 0},
                new float[] {0, 0, 0, 0.9f, 0.1f, 0, 0},
                new float[] {0, 0, 0, 0, 0, 1, 0});
        assertEquals("sie-", Ctc.greedy(probs, 4, 7, charset, false, lexicon).text());
    }

    @Test
    void logProbSumsEveryAlignment() {
        float[] probs = steps(
                new float[] {0.5f, 0.5f, 0, 0, 0, 0, 0},
                new float[] {0.5f, 0.5f, 0, 0, 0, 0, 0});
        // "s" over two steps: s-s, s-blank, blank-s.
        assertEquals(Math.log(0.75), CtcWords.logProb(probs, 7, new int[] {1}, 0, 1), 1e-9);
        assertEquals(Math.log(0.25), CtcWords.logProb(probs, 7, new int[0], 0, 1), 1e-9);
    }

    @Test
    void aUserListMayBeUnsortedMixedCaseAndGzipped() throws IOException {
        byte[] plain = "\uFEFFStraße\nÄrger\n\nzwei Wörter\r\nabend\nÄRGER\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(packed)) {
            gzip.write(plain);
        }
        for (byte[] data : List.of(plain, packed.toByteArray())) {
            Vocabulary german = Vocabulary.read(data, "de");
            assertTrue(german.word("straße"));
            assertTrue(german.word("ärger"));
            assertTrue(german.word("abend"));
            assertTrue(german.prefix("är"));
            assertFalse(german.word("zwei"));
            assertFalse(german.word("Ärger"));
        }
    }

    @Test
    void aSortedListIsUsedAsItIsAndAnUnsortedOneIsSorted() {
        Vocabulary sorted = Vocabulary.read("abend\närger\nstraße\n".getBytes(StandardCharsets.UTF_8), "sorted");
        assertTrue(sorted.word("ärger"));
        assertTrue(sorted.word("straße"));
        Vocabulary unsorted = Vocabulary.read("straße\nabend\närger".getBytes(StandardCharsets.UTF_8), "unsorted");
        assertTrue(unsorted.word("abend"));
        assertTrue(unsorted.word("ärger"));
        assertTrue(unsorted.word("straße"));
        Vocabulary decomposed = Vocabulary.read("a\u0308rger\n".getBytes(StandardCharsets.UTF_8), "nfd");
        assertTrue(decomposed.word("ärger"));
    }

    @Test
    void listsAreJoined() {
        Vocabulary joined = Vocabulary.of(List.of(
                Vocabulary.read("dom".getBytes(StandardCharsets.UTF_8), "a"),
                Vocabulary.read("haus".getBytes(StandardCharsets.UTF_8), "b")));
        assertTrue(joined.word("dom"));
        assertTrue(joined.word("haus"));
        assertTrue(joined.prefix("ha"));
        assertFalse(joined.word("ha"));
        assertNull(Vocabulary.of(List.of()));
    }
}
