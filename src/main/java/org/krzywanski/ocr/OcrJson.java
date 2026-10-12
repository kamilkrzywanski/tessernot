package org.krzywanski.ocr;

import java.awt.Rectangle;
import java.util.List;

/**
 * Compact JSON for one page or a whole document. The CLI writes this same text on stdout
 * when given {@code --json}.
 * <p>
 * A page object keeps the keys {@code text}, {@code lines}, {@code tables}, in that order.
 * A line keeps {@code text}, {@code score}, {@code detScore}, {@code quad}, {@code bounds}.
 * {@code quad} is eight numbers: top-left, top-right, bottom-right, bottom-left.
 * {@code bounds} keeps {@code x}, {@code y}, {@code width}, {@code height} as integers.
 * A table keeps {@code bounds}, then {@code cells} as a row-major array of strings.
 * An empty cell is {@code ""}. Tables do not change {@code text}.
 * A document is {@code \{"pages":[page, ...]\}} in page order, and each element uses the page object.
 * <p>
 * Every key is present. Scores and quad entries are JSON numbers, or {@code null} when the
 * float is not finite. A quotation mark and a backslash are escaped, and so are controls
 * U+0000 through U+001F: backspace, form feed, newline, carriage return, tab, and a
 * four-digit escape for every other control.
 * Other characters, including Polish letters, are written as themselves.
 * Detection and recognition times are not included. Each string ends with one newline.
 */
public final class OcrJson {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private OcrJson() {
    }

    /** One page object. */
    public static String page(OcrResult result) {
        if (result == null) {
            throw new OcrException("result is null");
        }
        StringBuilder out = new StringBuilder();
        appendPage(out, result);
        out.append('\n');
        return out.toString();
    }

    /** Pages in the given order. An empty list is {@code \{"pages":[]\}}. */
    public static String document(List<OcrResult> pages) {
        if (pages == null) {
            throw new OcrException("pages is null");
        }
        StringBuilder out = new StringBuilder();
        out.append("{\"pages\":[");
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            OcrResult page = pages.get(i);
            if (page == null) {
                throw new OcrException("page is null");
            }
            appendPage(out, page);
        }
        out.append("]}\n");
        return out.toString();
    }

    private static void appendPage(StringBuilder out, OcrResult result) {
        out.append("{\"text\":");
        string(out, result.text());
        out.append(",\"lines\":[");
        List<TextLine> lines = result.lines();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            appendLine(out, lines.get(i));
        }
        out.append("],\"tables\":[");
        List<TextTable> tables = result.tables();
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            appendTable(out, tables.get(i));
        }
        out.append("]}");
    }

    private static void appendLine(StringBuilder out, TextLine line) {
        out.append("{\"text\":");
        string(out, line.text());
        out.append(",\"score\":");
        number(out, line.score());
        out.append(",\"detScore\":");
        number(out, line.detScore());
        out.append(",\"quad\":[");
        float[] quad = line.quad();
        for (int i = 0; i < quad.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            number(out, quad[i]);
        }
        out.append("],\"bounds\":");
        bounds(out, line.bounds());
        out.append('}');
    }

    private static void appendTable(StringBuilder out, TextTable table) {
        out.append("{\"bounds\":");
        bounds(out, table.bounds());
        out.append(",\"cells\":[");
        List<List<String>> cells = table.cells();
        for (int row = 0; row < cells.size(); row++) {
            if (row > 0) {
                out.append(',');
            }
            out.append('[');
            List<String> texts = cells.get(row);
            for (int column = 0; column < texts.size(); column++) {
                if (column > 0) {
                    out.append(',');
                }
                string(out, texts.get(column));
            }
            out.append(']');
        }
        out.append("]}");
    }

    private static void bounds(StringBuilder out, Rectangle bounds) {
        out.append("{\"x\":").append(bounds.x);
        out.append(",\"y\":").append(bounds.y);
        out.append(",\"width\":").append(bounds.width);
        out.append(",\"height\":").append(bounds.height);
        out.append('}');
    }

    private static void number(StringBuilder out, float value) {
        if (!Float.isFinite(value)) {
            out.append("null");
            return;
        }
        out.append(Float.toString(value));
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00");
                        out.append(HEX[c >>> 4]);
                        out.append(HEX[c & 0xf]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
