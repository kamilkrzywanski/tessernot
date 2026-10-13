package org.krzywanski.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AmountSplitTest {
    @Test
    void splitsOnlyAnAmountAndAShortRate() {
        assertArrayEquals(new String[] {"8.13", "23"}, AmountSplit.parts("8.13 23"));
        assertArrayEquals(new String[] {"76,19", "5"}, AmountSplit.parts("76,19 5"));
        assertNull(AmountSplit.parts("90,00"));
        assertNull(AmountSplit.parts("8.13 23%"));
        assertNull(AmountSplit.parts("1 x4,99 4,99C"));
        assertNull(AmountSplit.parts("8.13  23"));
        assertNull(AmountSplit.parts("8.134 23"));
        assertNull(AmountSplit.parts("8.13 100"));
        assertNull(AmountSplit.parts(null));
    }
}
