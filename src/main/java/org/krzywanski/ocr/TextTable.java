package org.krzywanski.ocr;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/** Cells of one ruled grid, row by row, left to right. An empty cell is an empty string. */
public record TextTable(List<List<String>> cells, Rectangle bounds) {
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

    public int rows() {
        return cells.size();
    }

    public int columns() {
        return cells.get(0).size();
    }

    public String cell(int row, int column) {
        return cells.get(row).get(column);
    }
}
