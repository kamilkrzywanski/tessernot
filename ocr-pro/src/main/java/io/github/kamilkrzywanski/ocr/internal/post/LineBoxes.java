package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.TextLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Extents and medians of lines and of rows of lines. */
final class LineBoxes {
    private LineBoxes() {
    }

    static List<TextLine> flatten(List<List<TextLine>> rows) {
        List<TextLine> out = new ArrayList<>();
        for (List<TextLine> row : rows) {
            out.addAll(row);
        }
        return out;
    }

    static float medianWidth(List<TextLine> lines) {
        float[] widths = new float[lines.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = lines.get(i).right() - lines.get(i).left();
        }
        return median(widths);
    }

    static float medianHeight(List<TextLine> lines) {
        float[] heights = new float[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            heights[i] = lines.get(i).bottom() - lines.get(i).top();
        }
        return median(heights);
    }

    static float medianCount(List<List<TextLine>> rows) {
        float[] counts = new float[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            counts[i] = rows.get(i).size();
        }
        return median(counts);
    }

    static float median(float[] values) {
        Arrays.sort(values);
        int mid = values.length / 2;
        if ((values.length & 1) == 1) {
            return values[mid];
        }
        return (values[mid - 1] + values[mid]) * 0.5f;
    }

    static float rowTop(List<TextLine> row) {
        float y = row.get(0).top();
        for (int i = 1; i < row.size(); i++) {
            y = Math.min(y, row.get(i).top());
        }
        return y;
    }

    static float rowBottom(List<TextLine> row) {
        float y = row.get(0).bottom();
        for (int i = 1; i < row.size(); i++) {
            y = Math.max(y, row.get(i).bottom());
        }
        return y;
    }

    static float rowLeft(List<TextLine> row) {
        float x = row.get(0).left();
        for (int i = 1; i < row.size(); i++) {
            x = Math.min(x, row.get(i).left());
        }
        return x;
    }

    static float minLeft(List<TextLine> lines) {
        float x = lines.get(0).left();
        for (int i = 1; i < lines.size(); i++) {
            x = Math.min(x, lines.get(i).left());
        }
        return x;
    }

    static float maxRight(List<TextLine> lines) {
        float x = lines.get(0).right();
        for (int i = 1; i < lines.size(); i++) {
            x = Math.max(x, lines.get(i).right());
        }
        return x;
    }
}
