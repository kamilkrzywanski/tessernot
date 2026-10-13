package org.krzywanski.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeRuleTest {
    @Test
    void dropsOneFenceOnEachSideAndNothingElse() {
        assertTrue(EdgeRule.candidate("[VAT (%)|"));
        assertTrue(EdgeRule.candidate("VAT (%)|"));
        assertFalse(EdgeRule.candidate("VAT (%)"));
        assertFalse(EdgeRule.candidate("|"));
        assertEquals("VAT (%)", EdgeRule.keep("[VAT (%)|", "VAT (%)"));
        assertEquals("VAT (%)", EdgeRule.keep("|VAT (%)|", "VAT (%)"));
        assertEquals("VAT (%)", EdgeRule.keep("VAT (%)|", "VAT (%)"));
        assertNull(EdgeRule.keep("[VAT (%)|", "[VAT (%)|"));
        assertNull(EdgeRule.keep("[VAT (%)|", "VAT"));
        assertNull(EdgeRule.keep("[VAT (%)|", "VAT (%)|"));
        assertNull(EdgeRule.keep("90,00", "90,00"));
    }
}
