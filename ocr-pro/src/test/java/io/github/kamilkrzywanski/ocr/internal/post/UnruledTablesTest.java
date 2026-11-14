package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;
import io.github.kamilkrzywanski.ocr.TextTable;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnruledTablesTest {
    private static TextLine line(String text, float x, float y, float w) {
        return new TextLine(text, 1f, 1f, new float[] {x, y, x + w, y, x + w, y + 20, x, y + 20}, null);
    }

    /** Rows 30 px apart; each row is its boxes left to right. */
    private static List<List<TextLine>> rows(Object[]... rows) {
        List<List<TextLine>> out = new ArrayList<>();
        for (int r = 0; r < rows.length; r++) {
            List<TextLine> row = new ArrayList<>();
            for (int i = 0; i < rows[r].length; i += 3) {
                row.add(line((String) rows[r][i], ((Number) rows[r][i + 1]).floatValue(), r * 30,
                        ((Number) rows[r][i + 2]).floatValue()));
            }
            out.add(row);
        }
        return out;
    }

    @Test
    void rowsSetInThreeColumnsAreATable() {
        // Names from one left edge, amounts to one right edge, a unit between them.
        List<List<TextLine>> page = rows(
                new Object[] {"Nazwa", 0, 60, "j.m.", 300, 50, "Cena", 460, 40},
                new Object[] {"Chleb razowy", 0, 150, "szt.", 300, 50, "4,20", 460, 40},
                new Object[] {"Mleko", 0, 70, "l", 300, 15, "13,99", 448, 52},
                new Object[] {"Ser", 0, 40, "kg", 300, 30, "31,00", 448, 52});
        List<TextTable> tables = UnruledTables.find(page, List.of());
        assertEquals(1, tables.size());
        TextTable table = tables.get(0);
        assertFalse(table.ruled());
        assertEquals(4, table.rows());
        assertEquals(3, table.columns());
        assertEquals(List.of("Chleb razowy", "szt.", "4,20"), table.cells().get(1));
        assertEquals(new Rectangle(0, 0, 500, 110), table.bounds());
    }

    @Test
    void anEmptyCellStaysEmptyAndTwoBoxesOfACellAreJoined() {
        List<List<TextLine>> page = rows(
                new Object[] {"Chleb", 0, 70, "szt.", 300, 50, "4,20", 460, 40},
                new Object[] {"Mleko", 0, 30, "3,2 %", 40, 50, "13,99", 448, 52},
                new Object[] {"Ser", 0, 40, "kg", 300, 30, "31,00", 448, 52},
                new Object[] {"Masło", 0, 60, "szt.", 300, 50, "7,49", 460, 40});
        TextTable table = UnruledTables.find(page, List.of()).get(0);
        assertEquals(List.of("Mleko 3,2 %", "", "13,99"), table.cells().get(1));
    }

    @Test
    void largePrintCutIntoWordsIsNotATable() {
        // Three rows of three words: the gaps line up, the words begin and end anywhere.
        List<List<TextLine>> page = rows(
                new Object[] {"BEYOND", 60, 130, "THIS", 210, 90, "POINT", 330, 110},
                new Object[] {"YOU ARE", 20, 160, "ON A", 215, 80, "PREMISES", 325, 170},
                new Object[] {"TAKE", 90, 90, "NOTICE", 205, 110, "THAT", 345, 80});
        assertEquals(List.of(), UnruledTables.find(page, List.of()));
    }

    @Test
    void twoColumnsAndTwoRowsAreNotEnough() {
        List<List<TextLine>> pairs = rows(
                new Object[] {"NIP", 0, 40, "1234567890", 200, 120},
                new Object[] {"Data", 0, 50, "2024-01-02", 200, 120},
                new Object[] {"Numer", 0, 60, "FV/1/2024", 200, 110},
                new Object[] {"Miejsce", 0, 70, "Warszawa", 200, 100});
        assertEquals(List.of(), UnruledTables.find(pairs, List.of()));
        List<List<TextLine>> short2 = rows(
                new Object[] {"Chleb", 0, 70, "szt.", 300, 50, "4,20", 460, 40},
                new Object[] {"Mleko", 0, 70, "l", 300, 15, "13,99", 448, 52});
        assertEquals(List.of(), UnruledTables.find(short2, List.of()));
    }

    @Test
    void aLineAcrossTheColumnsEndsTheTable() {
        List<List<TextLine>> page = rows(
                new Object[] {"Chleb", 0, 70, "szt.", 300, 50, "4,20", 460, 40},
                new Object[] {"Mleko", 0, 70, "l", 300, 15, "13,99", 448, 52},
                new Object[] {"Ser", 0, 40, "kg", 300, 30, "31,00", 448, 52},
                new Object[] {"Razem do zapłaty za wszystkie towary", 0, 420, "49,19", 448, 52},
                new Object[] {"Dziękujemy", 0, 120, "i zapraszamy", 150, 140});
        List<TextTable> tables = UnruledTables.find(page, List.of());
        assertEquals(1, tables.size());
        assertEquals(3, tables.get(0).rows());
        assertEquals("Ser", tables.get(0).cell(2, 0));
    }

    @Test
    void rowsFarApartAndRowsOfARuledTableAreLeftOut() {
        List<List<TextLine>> page = rows(
                new Object[] {"Chleb", 0, 70, "szt.", 300, 50, "4,20", 460, 40},
                new Object[] {"Mleko", 0, 70, "l", 300, 15, "13,99", 448, 52},
                new Object[] {"Ser", 0, 40, "kg", 300, 30, "31,00", 448, 52});
        TextTable ruled = new TextTable(List.of(List.of("a", "b"), List.of("c", "d")), new Rectangle(-5, -5, 600, 200));
        assertTrue(ruled.ruled());
        assertEquals(List.of(), UnruledTables.find(page, List.of(ruled)));
        // The same three rows with the third two lines lower.
        List<TextLine> low = new ArrayList<>();
        for (TextLine line : page.get(2)) {
            float[] quad = line.quad();
            for (int i = 1; i < 8; i += 2) {
                quad[i] += 60;
            }
            low.add(new TextLine(line.text(), 1f, 1f, quad, null));
        }
        page.set(2, low);
        assertEquals(List.of(), UnruledTables.find(page, List.of()));
    }
}
