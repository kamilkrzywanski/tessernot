package org.krzywanski.ocr.post;

import org.junit.jupiter.api.Test;
import org.krzywanski.ocr.TextLine;
import org.krzywanski.ocr.TextTable;
import org.krzywanski.ocr.image.Bgr;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TablesTest {
    @Test
    void ruledGridPlacesTextInCells() {
        Bgr page = page(220, 140);
        v(page, 10, 10, 110);
        v(page, 80, 10, 110);
        v(page, 160, 10, 110);
        h(page, 10, 10, 160);
        h(page, 60, 10, 160);
        h(page, 110, 10, 160);
        List<TextTable> tables = Tables.find(page, List.of(
                line("A", 20, 20, 30, 20),
                line("B", 100, 25, 30, 16),
                line("C", 20, 75, 24, 16),
                line("OUT", 20, 120, 30, 12)), crop -> {
            throw new AssertionError("unread text");
        });
        assertEquals(1, tables.size());
        TextTable table = tables.get(0);
        assertEquals(2, table.rows());
        assertEquals(2, table.columns());
        assertEquals("A", table.cell(0, 0));
        assertEquals("B", table.cell(0, 1));
        assertEquals("C", table.cell(1, 0));
        assertEquals("", table.cell(1, 1));
        assertTrue(table.bounds().contains(40, 30), table.bounds().toString());
    }

    @Test
    void twoWordsInOneCellStayInOrder() {
        Bgr page = page(220, 140);
        grid(page);
        TextTable table = Tables.find(page, List.of(
                line("B", 40, 24, 16, 14),
                line("A", 18, 22, 16, 14),
                line("Z", 100, 75, 20, 14)), null).get(0);
        assertEquals("A B", table.cell(0, 0));
    }

    @Test
    void ruleThroughABoxReadsEachPiece() {
        Bgr page = page(220, 140);
        v(page, 10, 10, 110);
        v(page, 70, 10, 110);
        v(page, 180, 10, 110);
        h(page, 10, 10, 180);
        h(page, 55, 10, 180);
        h(page, 100, 10, 180);
        AtomicInteger reads = new AtomicInteger();
        TextTable table = Tables.find(page, List.of(
                line("BOTH", 20, 20, 140, 24),
                line("Z", 20, 70, 20, 16)), crop -> {
            reads.incrementAndGet();
            return "w" + crop.width();
        }).get(0);
        assertTrue(reads.get() >= 2, "reads " + reads.get());
        assertEquals("w" + table.cell(0, 0).substring(1), table.cell(0, 0));
        String left = table.cell(0, 0);
        String right = table.cell(0, 1);
        assertTrue(left.startsWith("w") && right.startsWith("w"), left + " | " + right);
        assertTrue(!left.equals(right), left + " | " + right);
        assertTrue(!left.contains("BOTH") && !right.contains("BOTH"), left + " | " + right);
        assertEquals("Z", table.cell(1, 0));
    }

    @Test
    void ruleThatMissesTheBoxDoesNotSplitIt() {
        Bgr page = page(220, 220);
        v(page, 10, 10, 200);
        v(page, 40, 10, 120);
        v(page, 80, 10, 200);
        v(page, 160, 10, 200);
        h(page, 10, 10, 160);
        h(page, 100, 10, 160);
        h(page, 200, 10, 160);
        AtomicInteger reads = new AtomicInteger();
        TextTable table = Tables.find(page, List.of(
                line("Z", 15, 40, 16, 14),
                line("NAME", 20, 150, 50, 20)), crop -> {
            reads.incrementAndGet();
            return "PIECE" + crop.width();
        }).get(0);
        assertEquals(3, table.columns(), table.cells().toString());
        assertEquals(0, reads.get());
        assertEquals("NAME", table.cell(1, 1));
        assertEquals("", table.cell(1, 0));
    }

    @Test
    void unclipMarginDoesNotSplitTheBox() {
        Bgr page = page(220, 140);
        v(page, 10, 10, 110);
        v(page, 70, 10, 110);
        v(page, 180, 10, 110);
        h(page, 10, 10, 180);
        h(page, 55, 10, 180);
        h(page, 100, 10, 180);
        AtomicInteger reads = new AtomicInteger();
        TextTable table = Tables.find(page, List.of(
                line("BOTH", 62, 20, 100, 24),
                line("Z", 20, 70, 20, 16)), crop -> {
            reads.incrementAndGet();
            return "PIECE";
        }).get(0);
        assertEquals(0, reads.get());
        assertEquals("", table.cell(0, 0));
        assertEquals("BOTH", table.cell(0, 1));
        assertEquals("Z", table.cell(1, 0));
    }

    @Test
    void spanningBoxStaysWholeWithoutAReader() {
        Bgr page = page(220, 140);
        v(page, 10, 10, 110);
        v(page, 70, 10, 110);
        v(page, 180, 10, 110);
        h(page, 10, 10, 180);
        h(page, 55, 10, 180);
        h(page, 100, 10, 180);
        TextTable table = Tables.find(page, List.of(
                line("BOTH", 20, 20, 140, 24),
                line("Z", 20, 70, 20, 16)), null).get(0);
        assertEquals("", table.cell(0, 0));
        assertEquals("BOTH", table.cell(0, 1));
        assertEquals("Z", table.cell(1, 0));
    }

    @Test
    void brokenHairlineIsStillOneRule() {
        Bgr page = page(220, 140);
        v(page, 10, 10, 110);
        v(page, 100, 10, 110);
        v(page, 190, 10, 110);
        h(page, 10, 10, 190);
        h(page, 110, 10, 190);
        stroke(page, 10, 60, 92, 61);
        stroke(page, 101, 60, 190, 61);
        TextTable table = Tables.find(page, List.of(
                line("A", 20, 25, 20, 14),
                line("B", 120, 25, 20, 14),
                line("C", 20, 80, 20, 14)), null).get(0);
        assertEquals(2, table.rows());
        assertEquals(2, table.columns());
        assertEquals("B", table.cell(0, 1));
    }

    @Test
    void thickStrokeIsNotAColumn() {
        Bgr page = page(280, 140);
        grid(page);
        stroke(page, 200, 10, 224, 110);
        TextTable table = Tables.find(page, List.of(
                line("A", 20, 25, 20, 14),
                line("B", 100, 25, 20, 14)), null).get(0);
        assertEquals(2, table.columns(), table.cells().toString());
    }

    @Test
    void pageBorderDoesNotJoinTwoGrids() {
        Bgr page = page(400, 130);
        stroke(page, 0, 2, 399, 3);
        block(page, 20);
        block(page, 220);
        List<TextTable> tables = Tables.find(page, List.of(
                line("A", 30, 30, 16, 12),
                line("A2", 30, 65, 16, 12),
                line("B", 230, 30, 16, 12),
                line("B2", 230, 65, 16, 12)), null);
        assertEquals(2, tables.size(), tables.toString());
        assertEquals("A", tables.get(0).cell(0, 0));
        assertEquals("B", tables.get(1).cell(0, 0));
    }

    @Test
    void oneRectangleAndAPriceRailAreNotTables() {
        Bgr framed = page(160, 120);
        h(framed, 10, 10, 100);
        h(framed, 80, 10, 100);
        v(framed, 10, 10, 80);
        v(framed, 100, 10, 80);
        assertTrue(Tables.find(framed, List.of(line("Sign", 30, 40, 40, 16)), null).isEmpty());
        Bgr plain = page(300, 80);
        assertTrue(Tables.find(plain, List.of(
                line("Chleb", 10, 10, 40, 12),
                line("1.99", 200, 10, 30, 12),
                line("Ser", 10, 40, 30, 12),
                line("3.29", 210, 40, 30, 12)), null).isEmpty());
    }

    @Test
    void blankAndNullInputs() {
        assertTrue(Tables.find(page(40, 40), List.of(), null).isEmpty());
        assertThrows(org.krzywanski.ocr.OcrException.class, () -> Tables.find(null, List.of(), null));
        assertThrows(org.krzywanski.ocr.OcrException.class, () -> Tables.find(page(40, 40), null, null));
    }

    private static void grid(Bgr page) {
        v(page, 10, 10, 110);
        v(page, 80, 10, 110);
        v(page, 160, 10, 110);
        h(page, 10, 10, 160);
        h(page, 60, 10, 160);
        h(page, 110, 10, 160);
    }

    private static void block(Bgr page, int x) {
        v(page, x, 2, 100);
        v(page, x + 40, 2, 100);
        v(page, x + 80, 2, 100);
        h(page, 20, x, x + 80);
        h(page, 50, x, x + 80);
        h(page, 90, x, x + 80);
    }

    private static void h(Bgr page, int y, int x0, int x1) {
        stroke(page, x0, y, x1, y + 1);
    }

    private static void v(Bgr page, int x, int y0, int y1) {
        stroke(page, x, y0, x + 1, y1);
    }

    private static void stroke(Bgr page, int x0, int y0, int x1, int y1) {
        byte[] data = page.data();
        int width = page.width();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int pixel = (y * width + x) * 3;
                Arrays.fill(data, pixel, pixel + 3, (byte) 0);
            }
        }
    }

    private static Bgr page(int width, int height) {
        byte[] data = new byte[width * height * 3];
        Arrays.fill(data, (byte) 255);
        return new Bgr(data, width, height);
    }

    private static TextLine line(String text, int x, int y, int w, int h) {
        return new TextLine(text, 1f, 1f,
                new float[]{x, y, x + w, y, x + w, y + h, x, y + h},
                new Rectangle(x, y, w, h));
    }
}
