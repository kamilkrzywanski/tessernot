package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.post.Charset;
import io.github.kamilkrzywanski.ocr.internal.post.Ctc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WordsTest {
    /** A box 100 wide and 20 high at (10, 50). */
    private static final float[] BOX = {10, 50, 110, 50, 110, 70, 10, 70};

    @Test
    void theDecoderSaysWhereEachCharacterStands() {
        Charset charset = Charset.of("a", "b", " ");
        // Eight timesteps: a a - b - space b b. Blank is label 0.
        int[] labels = {1, 1, 0, 2, 0, 3, 2, 2};
        float[] probs = new float[8 * 4];
        for (int t = 0; t < 8; t++) {
            probs[t * 4 + labels[t]] = 1f;
        }
        Ctc.Result result = Ctc.greedy(probs, 8, 4, charset);
        assertEquals("ab b", result.text());
        // From the timestep before its first to the one after its last, in eighths.
        assertArrayEquals(new float[] {0f, 2 / 8f, 4 / 8f, 5 / 8f}, result.from());
        assertArrayEquals(new float[] {2 / 8f, 4 / 8f, 6 / 8f, 1f}, result.to());
        // The crop filled half of the recognizer's input: the same places as shares of the crop.
        Ctc.Result scaled = result.scaled(2f);
        assertArrayEquals(new float[] {0f, 0.5f, 1f, 1f}, scaled.from());
        assertEquals("ab b", scaled.text());
        assertNull(new Ctc.Result("x", 1f).from());
        assertNull(new Ctc.Result("x", 1f).scaled(2f).from());
    }

    @Test
    void aWordIsTheStripOfTheBoxWhereItWasRead() {
        Ctc.Result result = new Ctc.Result("ab cd", 1f,
                new float[] {0.1f, 0.2f, 0.35f, 0.5f, 0.7f}, new float[] {0.2f, 0.3f, 0.5f, 0.7f, 0.9f});
        List<TextWord> words = Words.of(result, BOX, 0);
        assertEquals(2, words.size());
        assertEquals("ab", words.get(0).text());
        // 0.1 to 0.3 of 100 px: x from 20 to 40; the corners run top left, top right, bottom right, bottom left.
        assertArrayEquals(new float[] {20, 50, 40, 50, 40, 70, 20, 70}, words.get(0).quad(), 0.001f);
        assertEquals("cd", words.get(1).text());
        assertArrayEquals(new float[] {60, 50, 100, 50, 100, 70, 60, 70}, words.get(1).quad(), 0.001f);
    }

    @Test
    void aLineReadTurnedKeepsItsCornersInTheOrderOfTheText() {
        Ctc.Result result = new Ctc.Result("ab", 1f, new float[] {0f, 0.25f}, new float[] {0.25f, 0.5f});
        // Upside down: the text starts at the right end of the box and its top is the lower edge.
        assertArrayEquals(new float[] {110, 70, 60, 70, 60, 50, 110, 50}, Words.of(result, BOX, 2).get(0).quad(), 0.001f);
        // A box 20 wide and 100 high whose text runs down the page (a quarter turn counter-clockwise reads it).
        float[] standing = {10, 50, 30, 50, 30, 150, 10, 150};
        assertArrayEquals(new float[] {30, 50, 30, 100, 10, 100, 10, 50}, Words.of(result, standing, 1).get(0).quad(), 0.001f);
        // The same box with text that runs up the page.
        assertArrayEquals(new float[] {10, 150, 10, 100, 30, 100, 30, 150}, Words.of(result, standing, 3).get(0).quad(), 0.001f);
    }

    @Test
    void wordsFollowOneAnotherAndNeedPositions() {
        // The second word's first character is placed before the first word's end.
        Ctc.Result overlapping = new Ctc.Result("a b", 1f, new float[] {0f, 0.3f, 0.2f}, new float[] {0.4f, 0.4f, 0.6f});
        List<TextWord> words = Words.of(overlapping, BOX, 0);
        assertEquals(50f, words.get(1).quad()[0], 0.001f);
        assertEquals(List.of(), Words.of(new Ctc.Result("a b", 1f), BOX, 0));
        assertEquals(List.of(), Words.of(new Ctc.Result("   ", 1f, new float[3], new float[3]), BOX, 0));
        assertThrows(OcrException.class, () -> new TextWord("a", new float[6]));
        float[] quad = {1, 2, 3, 4, 5, 6, 7, 8};
        TextWord word = new TextWord(null, quad);
        quad[0] = 99;
        word.quad()[1] = 99;
        assertEquals("", word.text());
        assertArrayEquals(new float[] {1, 2, 3, 4, 5, 6, 7, 8}, word.quad());
    }
}
