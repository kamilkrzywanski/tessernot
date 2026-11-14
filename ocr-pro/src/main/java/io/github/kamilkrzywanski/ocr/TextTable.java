package io.github.kamilkrzywanski.ocr;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Cells of one ruled grid, row by row, left to right. An empty cell is an empty string.
 * A table does not change: the cells are unmodifiable and {@link #bounds()} hands out a copy.
 *
 * @param cells the text of every cell, one list per row, each row with the same number of cells
 * @param bounds the pixel rectangle of the grid on the page
 */
public record TextTable(List<List<String>> cells, Rectangle bounds) {
    /**
     * A null cell becomes empty and a null rectangle an empty one; the rows and the rectangle are copied.
     *
     * @throws OcrException when there are fewer than two rows or two columns, or the rows differ in length
     */
    public TextTable {
        if (cells == null || cells.isEmpty() || cells.get(0) == null || cells.get(0).size() < 2) {
            throw new OcrException("table needs at least two columns and two rows");
        }
        int columns = cells.get(0).size();
        if (cells.size() < 2) {
            throw new OcrException("table needs at least two columns and two rows");
        }
        List<List<String>> copy = new ArrayList<>(cells.size());
        for (List<String> row : cells) {
            if (row == null || row.size() != columns) {
                throw new OcrException("table row has " + (row == null ? 0 : row.size()) + " cells, not " + columns);
            }
            List<String> texts = new ArrayList<>(columns);
            for (String cell : row) {
                texts.add(cell == null ? "" : cell);
            }
            copy.add(List.copyOf(texts));
        }
        cells = List.copyOf(copy);
        bounds = bounds == null ? new Rectangle() : new Rectangle(bounds);
    }

    /** {@return a copy of the pixel rectangle of the grid} */
    @Override
    public Rectangle bounds() {
        return new Rectangle(bounds);
    }

    /** {@return the number of rows, at least two} */
    public int rows() {
        return cells.size();
    }

    /** {@return the number of columns, at least two} */
    public int columns() {
        return cells.get(0).size();
    }

    /**
     * One cell.
     *
     * @param row the row, from 0
     * @param column the column, from 0
     * @return the text of the cell, empty when the cell is
     */
    public String cell(int row, int column) {
        return cells.get(row).get(column);
    }
}
