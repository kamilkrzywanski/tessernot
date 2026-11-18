package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextLineTest {
    @Test
    void aLineDoesNotChangeThroughWhatItHandsOut() {
        float[] quad = {10, 20, 110, 22, 108, 52, 8, 50};
        Rectangle bounds = new Rectangle(8, 20, 102, 32);
        TextLine line = new TextLine("FAKTURA", 0.9f, 0.8f, quad, bounds);
        quad[0] = -1;
        bounds.x = -1;
        assertEquals(10f, line.quad()[0]);
        assertEquals(8, line.bounds().x);

        line.quad()[0] = 99;
        line.bounds().x = 99;
        assertArrayEquals(new float[]{10, 20, 110, 22, 108, 52, 8, 50}, line.quad(), 0f);
        assertEquals(new Rectangle(8, 20, 102, 32), line.bounds());
        assertNotSame(line.quad(), line.quad());
        assertNotSame(line.bounds(), line.bounds());
    }

    @Test
    void extentsAreTheSmallestAndLargestCorner() {
        TextLine line = new TextLine("x", 1f, 1f, new float[]{10, 20, 110, 22, 108, 52, 8, 50}, null);
        assertEquals(8f, line.left());
        assertEquals(110f, line.right());
        assertEquals(20f, line.top());
        assertEquals(52f, line.bottom());
        TextLine empty = new TextLine(null, 0f, 0f, null, null);
        assertEquals("", empty.text());
        assertEquals(0f, empty.right());
        assertEquals(new Rectangle(), empty.bounds());
        assertThrows(OcrException.class, () -> new TextLine("x", 1f, 1f, new float[6], null));
    }

    @Test
    void aTableHandsOutACopyOfItsBounds() {
        Rectangle given = new Rectangle(5, 6, 70, 80);
        TextTable table = new TextTable(java.util.List.of(java.util.List.of("a", "b"), java.util.List.of("c", "d")), given);
        given.x = 99;
        assertEquals(new Rectangle(5, 6, 70, 80), table.bounds());
        table.bounds().width = 1;
        assertEquals(new Rectangle(5, 6, 70, 80), table.bounds());
        assertNotSame(table.bounds(), table.bounds());
        assertEquals("d", table.cell(1, 1));
        assertEquals(2, table.rows());
        assertEquals(2, table.columns());
    }
}
