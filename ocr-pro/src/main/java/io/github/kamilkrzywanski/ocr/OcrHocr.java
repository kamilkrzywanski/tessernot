package io.github.kamilkrzywanski.ocr;

import java.util.List;
import java.util.Locale;

/**
 * hOCR output: the page as XHTML with a box on every line and word, the format that hOCR tools,
 * search indexers and PDF builders read. The CLI writes this same text with {@code --hocr}.
 * <p>
 * A page is a {@code div} of class {@code ocr_page} whose {@code title} gives
 * {@code bbox 0 0 width height} and the page number; a line is a {@code span} of class
 * {@code ocr_line}, in reading order; a word is a {@code span} of class {@code ocrx_word} inside
 * its line. Every {@code bbox} is the upright rectangle around the quad, in pixels of the picture
 * as it was given. A line that does not run level carries {@code textangle}, its angle in degrees
 * counter-clockwise. A line whose word boxes are not known holds its text directly. Tables and
 * scores are not written: hOCR's word confidence is per word, and the engine has one score per
 * line.
 */
public final class OcrHocr {
    private OcrHocr() {
    }

    /**
     * One page as a complete hOCR document.
     *
     * @param result the page; its {@link OcrResult#width()} and {@link OcrResult#height()} give the page box
     * @return the XHTML text, ending with a newline
     * @throws OcrException when {@code result} is null
     */
    public static String page(OcrResult result) {
        if (result == null) {
            throw new OcrException("result is null");
        }
        return document(List.of(result));
    }

    /**
     * Pages in the given order, one {@code ocr_page} each, as one hOCR document.
     *
     * @param pages the pages of the document
     * @return the XHTML text, ending with a newline
     * @throws OcrException when {@code pages} or one of its entries is null
     */
    public static String document(List<OcrResult> pages) {
        if (pages == null) {
            throw new OcrException("pages is null");
        }
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\"")
                .append(" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n")
                .append("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n")
                .append(" <head>\n")
                .append("  <title></title>\n")
                .append("  <meta http-equiv=\"Content-Type\" content=\"text/html;charset=utf-8\"/>\n")
                .append("  <meta name=\"ocr-system\" content=\"ocr-pro\"/>\n")
                .append("  <meta name=\"ocr-capabilities\" content=\"ocr_page ocr_line ocrx_word\"/>\n")
                .append(" </head>\n")
                .append(" <body>\n");
        for (int p = 0; p < pages.size(); p++) {
            OcrResult page = pages.get(p);
            if (page == null) {
                throw new OcrException("page is null");
            }
            int number = p + 1;
            out.append("  <div class=\"ocr_page\" id=\"page_").append(number).append("\" title=\"bbox 0 0 ")
                    .append(page.width()).append(' ').append(page.height()).append("; ppageno ").append(p)
                    .append("\">\n");
            List<TextLine> lines = page.lines();
            for (int l = 0; l < lines.size(); l++) {
                TextLine line = lines.get(l);
                float[] quad = line.quad();
                out.append("   <span class=\"ocr_line\" id=\"line_").append(number).append('_').append(l + 1)
                        .append("\" title=\"");
                bbox(out, quad);
                // Counter-clockwise on the page: y grows downward in the picture.
                double angle = -Math.toDegrees(Math.atan2(quad[3] - quad[1], quad[2] - quad[0]));
                if (Math.abs(angle) >= 0.5) {
                    out.append("; textangle ").append(String.format(Locale.ROOT, "%.1f", angle));
                }
                out.append("\">");
                List<TextWord> words = line.words();
                if (words.isEmpty()) {
                    text(out, line.text());
                }
                for (int w = 0; w < words.size(); w++) {
                    if (w > 0) {
                        out.append(' ');
                    }
                    out.append("<span class=\"ocrx_word\" id=\"word_").append(number).append('_').append(l + 1)
                            .append('_').append(w + 1).append("\" title=\"");
                    bbox(out, words.get(w).quad());
                    out.append("\">");
                    text(out, words.get(w).text());
                    out.append("</span>");
                }
                out.append("</span>\n");
            }
            out.append("  </div>\n");
        }
        out.append(" </body>\n</html>\n");
        return out.toString();
    }

    /** {@code bbox x0 y0 x1 y1}: the upright rectangle around a quad, whole pixels, never negative. */
    private static void bbox(StringBuilder out, float[] quad) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float x = quad[i * 2];
            float y = quad[i * 2 + 1];
            if (Float.isFinite(x)) {
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
            }
            if (Float.isFinite(y)) {
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        if (minX > maxX || minY > maxY) {
            out.append("bbox 0 0 0 0");
            return;
        }
        out.append("bbox ").append(Math.max(0, (int) Math.floor(minX))).append(' ')
                .append(Math.max(0, (int) Math.floor(minY))).append(' ')
                .append(Math.max(0, (int) Math.ceil(maxX))).append(' ')
                .append(Math.max(0, (int) Math.ceil(maxY)));
    }

    /** Text for XML: the five markup characters escaped, and characters XML 1.0 does not allow left out. */
    private static void text(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> {
                    boolean allowed = c == '\t' || c == '\n' || c == '\r' || c >= 0x20 && c <= 0xd7ff
                            || c >= 0xe000 && c <= 0xfffd || c >= 0x10000 && c <= 0x10ffff;
                    if (allowed) {
                        out.appendCodePoint(c);
                    }
                }
            }
        }
    }
}
