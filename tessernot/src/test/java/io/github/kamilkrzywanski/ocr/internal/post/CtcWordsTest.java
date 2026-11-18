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
    void anUnseenMarkCostsTheSameOnALetterOfTwoTimesteps() {
        // ę on the line, then s, i and an e that takes two timesteps with no trace of the mark.
        float[] wide = steps(
                new float[] {0, 0, 0, 0, 1, 0, 0},
                new float[] {0, 0, 0, 0, 0, 0, 1},
                new float[] {0, 1, 0, 0, 0, 0, 0},
                new float[] {0, 0, 1, 0, 0, 0, 0},
                new float[] {0, 0, 0, 0.99999f, 0.00001f, 0, 0},
                new float[] {0, 0, 0, 0.99999f, 0.00001f, 0, 0},
                new float[] {1, 0, 0, 0, 0, 0, 0});
        // 3 nats, inside the margin of 4. Paid on both timesteps it would be 6.
        assertEquals("ę się", Ctc.greedy(wide, 7, 7, charset, false, lexicon).text());
    }

    @Test
    void twoUnseenMarksInOneWordAreTooMany() {
        Lexicon twice = Lexicon.of(new String[] {"sęę"}, new String[0]);
        float[] probs = steps(
                new float[] {0, 0, 0, 0, 1, 0, 0},
                new float[] {0, 0, 0, 0, 0, 0, 1},
                new float[] {0, 1, 0, 0, 0, 0, 0},
                new float[] {0, 0, 0, 0.99999f, 0.00001f, 0, 0},
                new float[] {1, 0, 0, 0, 0, 0, 0},
                new float[] {0, 0, 0, 0.99999f, 0.00001f, 0, 0});
        assertEquals("ę see", Ctc.greedy(probs, 6, 7, charset, false, twice).text());
        // One of the two marks seen at one in three: 3 nats and a little, and the word is taken.
        float[] half = probs.clone();
        half[3 * 7 + 3] = 0.7f;
        half[3 * 7 + 4] = 0.3f;
        assertEquals("ę sęę", Ctc.greedy(half, 6, 7, charset, false, twice).text());
    }

    @Test
    void aMarkOnTheFirstTimestepOfALetterCarriesOverTheSecond() {
        // The e takes two timesteps; the mark shows on the first only.
        float[] probs = steps(
                new float[] {0, 0, 0, 0, 1, 0, 0},
                new float[] {0, 0, 0, 0, 0, 0, 1},
                new float[] {0, 1, 0, 0, 0, 0, 0},
                new float[] {0, 0, 1, 0, 0, 0, 0},
                new float[] {0, 0, 0, 0.9f, 0.1f, 0, 0},
                new float[] {0, 0, 0, 0.99999f, 0.00001f, 0, 0},
                new float[] {1, 0, 0, 0, 0, 0, 0});
        assertEquals("ę się", Ctc.greedy(probs, 7, 7, charset, false, lexicon).text());
        // Without a mark anywhere on the line nothing is borrowed from the plain letter.
        float[] bare = java.util.Arrays.copyOfRange(probs, 2 * 7, probs.length);
        assertEquals("sie", Ctc.greedy(bare, 5, 7, charset, false, lexicon).text());
    }

    @Test
    void lowercaseIsLetterByLetter() {
        assertEquals("οσοι", CtcWords.lower("ΟΣΟΙ"));
        assertEquals("οδοσ", CtcWords.lower("ΟΔΟΣ"));
        assertEquals("istanbul", CtcWords.lower("İSTANBUL"));
        assertEquals("straße", CtcWords.lower("Straße"));
        String already = "łódź";
        assertTrue(already == CtcWords.lower(already));
    }

    /** Rows over a charset of four letters: blank and the four. */
    private static float[] rows(float[]... rows) {
        float[] out = new float[rows.length * 5];
        for (int t = 0; t < rows.length; t++) {
            System.arraycopy(rows[t], 0, out, t * 5, 5);
        }
        return out;
    }

    @Test
    void aCapitalSigmaInsideAWordKeepsThePrefixListed() {
        Charset greek = Charset.of("Ο", "Σ", "Ι", "Θ");
        // Ο, Σ, then Θ over Ο, then Ι: the listed ΟΣΟΙ is found through the prefix ΟΣ.
        float[] probs = rows(
                new float[] {0, 1, 0, 0, 0},
                new float[] {0, 0, 1, 0, 0},
                new float[] {0, 0.3f, 0, 0, 0.7f},
                new float[] {0, 0, 0, 1, 0});
        assertEquals("ΟΣΘΙ", Ctc.greedy(probs, 4, 5, greek, false, null).text());
        assertEquals("ΟΣΟΙ", Ctc.greedy(probs, 4, 5, greek, false, WordList.of("όσοι", "οσοι")).text());
    }

    @Test
    void aCapitalSigmaAtTheEndMatchesTheFinalSigmaOfTheList() {
        Charset greek = Charset.of("Ο", "Σ", "Δ", "Θ");
        Vocabulary list = Vocabulary.read("οδος\n".getBytes(StandardCharsets.UTF_8), "el");
        float[] read = rows(
                new float[] {0, 1, 0, 0, 0},
                new float[] {0, 0, 0, 0.3f, 0.7f},
                new float[] {0, 1, 0, 0, 0},
                new float[] {0, 0, 1, 0, 0});
        assertEquals("ΟΔΟΣ", Ctc.greedy(read, 4, 5, greek, false, list).text());
        // A list in capitals is lowercased as a whole word, with ς at the end.
        Vocabulary capitals = Vocabulary.read("ΟΔΟΣ\n".getBytes(StandardCharsets.UTF_8), "EL");
        assertTrue(capitals.word("οδος"));
        assertEquals("ΟΔΟΣ", Ctc.greedy(read, 4, 5, greek, false, capitals).text());
    }

    @Test
    void aCapitalIMayBeTheDotlessOne() {
        Charset turkish = Charset.of("K", "I", "Z", "S");
        float[] probs = rows(
                new float[] {0, 1, 0, 0, 0},
                new float[] {0, 0, 1, 0, 0},
                new float[] {0, 0, 0, 0.3f, 0.7f});
        assertEquals("KIS", Ctc.greedy(probs, 3, 5, turkish, false, null).text());
        assertEquals("KIZ", Ctc.greedy(probs, 3, 5, turkish, false, WordList.of("kız")).text());
        // The dotted reading still counts: an English list has no ı.
        assertEquals("KIZ", Ctc.greedy(probs, 3, 5, turkish, false, WordList.of("kiz")).text());
        // A listed word is left alone in either reading.
        float[] sure = rows(
                new float[] {0, 1, 0, 0, 0},
                new float[] {0, 0, 1, 0, 0},
                new float[] {0, 0, 0, 0, 1});
        assertEquals("KIS", Ctc.greedy(sure, 3, 5, turkish, false, WordList.of("kıs", "kız")).text());
    }

    @Test
    void aDottedCapitalIInAListIsAPlainI() {
        Vocabulary list = Vocabulary.read("İSTANBUL\nIŞIK\n".getBytes(StandardCharsets.UTF_8), "tr");
        assertTrue(list.word("istanbul"));
        assertTrue(list.prefix("ist"));
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
