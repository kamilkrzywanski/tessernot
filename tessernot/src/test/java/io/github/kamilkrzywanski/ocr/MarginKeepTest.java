package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarginKeepTest {
    @Test
    void keepsAStrokeOrASpaceAndRejectsEveryOtherChange() {
        assertEquals("usługi", MarginKeep.keep("ustugi", "usługi"));
        assertEquals("Zapłacono", MarginKeep.keep("Zaptacono", "Zapłacono"));
        assertEquals("Słownie:", MarginKeep.keep("Stownie:", "Słownie:"));
        assertEquals("i Wspólnicy", MarginKeep.keep("iWspólnicy", "i Wspólnicy"));
        assertEquals(
                "Biuro i magazyn: ul. Warszawska 55, 05-820 Piastów",
                MarginKeep.keep(
                        "Biuro imagazyn: ul. Warszawska 55, 05-820 Piastów",
                        "Biuro i magazyn: ul. Warszawska 55, 05-820 Piastów"));
        assertNull(MarginKeep.keep("1 Ziemia Kłodzka", "1Ziemia Klodzka"));
        assertNull(MarginKeep.keep("1 x1,99", "1 ×1,99"));
        assertNull(MarginKeep.keep("dziewięćdziesiąt", "dziewięódziesiąt"));
        assertNull(MarginKeep.keep("Spolka", "Spółka"));
        assertNull(MarginKeep.keep("Kłodzka", "Klodzka"));
        assertNull(MarginKeep.keep("Ser ż.Swiat. 150g", "Ser ż.Swiat. \"150g"));
        assertNull(MarginKeep.keep("usługi", "usługi"));
        assertNull(MarginKeep.keep("abc", "a b c d"));
        assertFalse(MarginKeep.candidate("90,00"));
        assertFalse(MarginKeep.candidate(""));
        assertTrue(MarginKeep.candidate("ustugi"));
        assertTrue(MarginKeep.candidate("Biuro imagazyn: ul. Warszawska"));
        assertEquals(2, MarginKeep.repairs("iWspólnicy Spólka", "i Wspólnicy Spółka"));
        assertEquals(1, MarginKeep.repairs("iWspólnicy Spólka", "iWspólnicy Spółka"));
        assertEquals(1, MarginKeep.repairs("ustugi", "usługi"));
        assertEquals(0, MarginKeep.repairs("ustugi", "uslugi"));
        assertEquals(0, MarginKeep.repairs("Spolka", "Spółka"));
    }
}
