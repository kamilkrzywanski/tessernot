package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotKeepTest {
    @Test
    void keepsOneLeadingLetterAndRejectsADigitOrADifferentWord() {
        assertTrue(DotKeep.candidate(".m."));
        assertFalse(DotKeep.candidate("j.m."));
        assertFalse(DotKeep.candidate(".m"));
        assertFalse(DotKeep.candidate(""));
        assertEquals("j.m.", DotKeep.keep(".m.", "j.m."));
        assertEquals("i.m.", DotKeep.keep(".m.", "i.m."));
        assertNull(DotKeep.keep(".m.", "1.m."));
        assertNull(DotKeep.keep(".m.", ".m."));
        assertNull(DotKeep.keep(".m.", "jm."));
        assertNull(DotKeep.keep(".m.", "j.m"));
        assertNull(DotKeep.keep("a.m.", "ja.m."));
        float[] grown = DotKeep.grow(new float[] {10, 20, 40, 20, 40, 50, 10, 50});
        assertEquals(10 - DotKeep.LEFT, grown[0]);
        assertEquals(20f, grown[1]);
        assertEquals(40f, grown[2]);
        assertEquals(50 + DotKeep.DOWN, grown[5]);
    }
}
