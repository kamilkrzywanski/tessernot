package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CutKeepTest {
    @Test
    void piecesThatReadBackTheLineReplaceIt() {
        assertFalse(OcrEngine.keepWhole("Suma brutto 123,45 zł", List.of("Suma brutto", "123,45 zł")));
        assertFalse(OcrEngine.keepWhole("left column text right column text", List.of("left column text", "right column text")));
    }

    @Test
    void aShortPieceAtARealGutterIsDroppedAndTheRestStays() {
        // The dash between the columns did not read; the two columns did. The merged line must not come back.
        assertFalse(OcrEngine.keepWhole("Warszawa - Kraków", Arrays.asList("Warszawa", null, "Kraków")));
        assertFalse(OcrEngine.keepWhole("Rozdział pierwszy . 12", Arrays.asList("Rozdział pierwszy", null)));
    }

    @Test
    void aCutThatLosesTheTextKeepsTheLine() {
        assertTrue(OcrEngine.keepWhole("Zamawiający: Urząd Miasta", Arrays.asList("Zamawiający:", null)));
        assertTrue(OcrEngine.keepWhole("abcdefghij", List.of("abc", "de")));
        assertTrue(OcrEngine.keepWhole("abcdefghij", Arrays.asList(null, null)));
    }

    @Test
    void whitespaceDoesNotCount() {
        assertFalse(OcrEngine.keepWhole("a b c d e f g h i j", List.of("abcde", "fghij")));
        assertFalse(OcrEngine.keepWhole("   ", Arrays.asList(null, null)));
    }
}
