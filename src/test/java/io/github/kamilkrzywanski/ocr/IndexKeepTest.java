package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexKeepTest {
    @Test
    void agreesWhenTheCutOnlyDropsAStrokeOrAnAcute() {
        assertTrue(IndexKeep.agrees("Ziemia Kłodzka", "Ziemia Kłodzka"));
        assertTrue(IndexKeep.agrees("Ziemia Kłodzka", "Ziemia Klodzka"));
        assertTrue(IndexKeep.agrees("Spółka", "Spolka"));
        assertFalse(IndexKeep.agrees("Ziemia Kłodzka", "Ziemia Kłodzk"));
        assertFalse(IndexKeep.agrees("Ziemia Kłodzka", "Ziemia Klodzkx"));
        assertFalse(IndexKeep.agrees("1 Ziemia Kłodzka", "Ziemia Kłodzka"));
        assertFalse(IndexKeep.agrees("", ""));
        assertFalse(IndexKeep.agrees("x", "×"));
        assertFalse(IndexKeep.agrees("Polędw", "Poledw"));
        assertTrue(IndexKeep.agrees("usługi", "uslugi"));
    }
}
