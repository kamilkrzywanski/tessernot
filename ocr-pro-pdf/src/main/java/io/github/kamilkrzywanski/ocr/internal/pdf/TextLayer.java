package io.github.kamilkrzywanski.ocr.internal.pdf;

import io.github.kamilkrzywanski.ocr.OcrResult;
import io.github.kamilkrzywanski.ocr.TextLine;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The text of a page written under its picture, so a viewer can search, select and copy it.
 * Every line is one string in text rendering mode 3 (neither filled nor stroked), stretched over
 * the box of the line: along its lower edge, as high as the box. A skewed line keeps its slant,
 * and a box that stands on its short side holds its text from the bottom up.
 * <p>
 * The text needs a font, and no standard PDF font has Polish letters. The layer brings its own:
 * Type 3 fonts whose glyphs draw nothing. A Type 3 font is a dictionary of drawing procedures, so
 * there is no font file to ship or embed. Each font has 223 codes (33 to 255, and 32 for the
 * space), given to the characters of the document in the order they turn up, and a
 * {@code ToUnicode} map that tells a reader which character a code is. A document with more
 * characters gets more fonts. The widths are those of Helvetica for the letter, or for its base
 * letter when Helvetica lacks it, so a word inside a line lies about where it is printed; only
 * the ends of a line are exact.
 * <p>
 * The page content that is already there is wrapped in {@code q ... Q}, so the layer starts from
 * the default coordinates whatever the page did to them.
 */
public final class TextLayer {
    /** Share of the line box below the baseline, and of the font box: room for descenders. */
    private static final float DESCENT = 0.25f;
    /** A box this many times as high as it is wide holds a line that runs upward, as {@code OcrConfig.rotateVertical} takes it. */
    private static final float UPRIGHT = 1.5f;
    private static final int FIRST_CODE = 33;
    private static final int LAST_CODE = 255;
    private static final int SPACE = 32;
    /** Helvetica's width for {@code n}: what a letter gets when nothing better is known. */
    private static final int DEFAULT_WIDTH = 556;
    private static final int WIDE_WIDTH = 1000;

    private final PDDocument document;
    private final List<Font> fonts = new ArrayList<>();
    /** Code point to its font and code, as {@code font * 256 + code}. */
    private final Map<Integer, Integer> slots = new HashMap<>();
    private final PDType1Font helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private boolean finished;

    /** One invisible font: a dictionary that {@link #finish()} fills. */
    private static final class Font {
        final COSDictionary dictionary = new COSDictionary();
        final COSName name;
        /** Code point of each code from {@link #FIRST_CODE}. */
        final List<Integer> codePoints = new ArrayList<>();

        Font(int index) {
            this.name = COSName.getPDFName("OcrProText" + index);
        }
    }

    public TextLayer(PDDocument document) {
        this.document = document;
    }

    /**
     * Adds the lines of {@code result} to {@code page}. {@code dpi} is the resolution the page was
     * rendered at for recognition: a line's pixel is {@code 72 / dpi} points of the page, counted
     * from the top left corner of the crop box as the page is shown, that is after its rotation.
     */
    public void add(PDPage page, OcrResult result, float dpi) throws IOException {
        if (finished) {
            throw new IllegalStateException("text layer is finished");
        }
        StringBuilder content = new StringBuilder("Q\nBT\n3 Tr\n");
        Placement placement = new Placement(page, dpi);
        List<Font> used = new ArrayList<>();
        boolean any = false;
        for (TextLine line : result.lines()) {
            any |= show(content, placement, line, used);
        }
        if (!any) {
            return;
        }
        content.append("ET\n");
        PDResources resources = page.getResources();
        if (resources == null) {
            resources = new PDResources();
            page.setResources(resources);
        }
        COSDictionary resourceFonts = resources.getCOSObject().getCOSDictionary(COSName.FONT);
        if (resourceFonts == null) {
            resourceFonts = new COSDictionary();
            resources.getCOSObject().setItem(COSName.FONT, resourceFonts);
        }
        for (Font font : used) {
            resourceFonts.setItem(font.name, font.dictionary);
        }
        wrap(page, content.toString());
    }

    /** Fills the font dictionaries. Call once, after the last page and before the document is saved. */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        finished = true;
        Map<Integer, COSStream> glyphs = new HashMap<>();
        for (Font font : fonts) {
            fill(font, glyphs);
        }
    }

    /** One line as a text matrix and its strings. False when the line has no text or no size. */
    private boolean show(StringBuilder content, Placement placement, TextLine line, List<Font> used) {
        String text = line.text();
        float[] quad = line.quad();
        float[] bottomLeft = placement.toPage(quad[6], quad[7]);
        float[] bottomRight = placement.toPage(quad[4], quad[5]);
        float[] topLeft = placement.toPage(quad[0], quad[1]);
        if (distance(bottomLeft, topLeft) >= UPRIGHT * distance(bottomLeft, bottomRight)) {
            // A box that stands: the engine reads it turned a quarter, from the bottom up.
            float[] topRight = placement.toPage(quad[2], quad[3]);
            topLeft = bottomLeft;
            bottomLeft = bottomRight;
            bottomRight = topRight;
        }
        float dx = bottomRight[0] - bottomLeft[0];
        float dy = bottomRight[1] - bottomLeft[1];
        float length = (float) Math.hypot(dx, dy);
        if (text.isBlank() || !(length > 0.01f)) {
            return false;
        }
        float ux = dx / length;
        float uy = dy / length;
        // Across the line, towards its top edge.
        float nx = -uy;
        float ny = ux;
        float upX = topLeft[0] - bottomLeft[0];
        float upY = topLeft[1] - bottomLeft[1];
        float height = nx * upX + ny * upY;
        if (height < 0f) {
            nx = -nx;
            ny = -ny;
            height = -height;
        }
        long units = 0;
        int[] codePoints = text.codePoints().filter(c -> !Character.isISOControl(c)).toArray();
        for (int codePoint : codePoints) {
            units += width(codePoint);
        }
        if (!(height > 0.01f) || units == 0) {
            return false;
        }
        // The font is set at size 1; the matrix carries the height and the stretch along the line.
        float stretch = length / (units / 1000f * height);
        float x = bottomLeft[0] + nx * height * DESCENT;
        float y = bottomLeft[1] + ny * height * DESCENT;
        number(content, ux * height * stretch);
        number(content, uy * height * stretch);
        number(content, nx * height);
        number(content, ny * height);
        number(content, x);
        number(content, y);
        content.append("Tm\n");
        Font current = null;
        boolean open = false;
        for (int codePoint : codePoints) {
            int slot = slot(codePoint);
            Font font = fonts.get(slot >> 8);
            if (font != current) {
                if (open) {
                    content.append("> Tj\n");
                }
                content.append('/').append(font.name.getName()).append(" 1 Tf\n<");
                open = true;
                current = font;
                if (!used.contains(font)) {
                    used.add(font);
                }
            }
            int code = slot & 255;
            content.append(Character.forDigit(code >> 4, 16)).append(Character.forDigit(code & 15, 16));
        }
        content.append("> Tj\n");
        return true;
    }

    private static float distance(float[] a, float[] b) {
        return (float) Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /** The font and code of a character, given out on first use. The space is code 32 of every font. */
    private int slot(int codePoint) {
        if (codePoint == ' ') {
            if (fonts.isEmpty()) {
                fonts.add(new Font(0));
            }
            return (fonts.size() - 1) * 256 + SPACE;
        }
        Integer known = slots.get(codePoint);
        if (known != null) {
            return known;
        }
        if (fonts.isEmpty() || fonts.get(fonts.size() - 1).codePoints.size() > LAST_CODE - FIRST_CODE) {
            fonts.add(new Font(fonts.size()));
        }
        Font font = fonts.get(fonts.size() - 1);
        int slot = (fonts.size() - 1) * 256 + FIRST_CODE + font.codePoints.size();
        font.codePoints.add(codePoint);
        slots.put(codePoint, slot);
        return slot;
    }

    /**
     * Width of a character in thousandths of the font size: Helvetica's, or that of the letter
     * without its marks, 1000 for an ideograph or a kana, and Helvetica's {@code n} otherwise.
     */
    int width(int codePoint) {
        String text = new String(Character.toChars(codePoint));
        Integer own = helveticaWidth(text);
        if (own != null) {
            return own;
        }
        String base = switch (codePoint) {
            case 'ł' -> "l";
            case 'Ł' -> "L";
            case 'đ' -> "d";
            case 'Đ' -> "D";
            case 'ħ' -> "h";
            case 'ı' -> "i";
            default -> Normalizer.normalize(text, Normalizer.Form.NFD);
        };
        Integer plain = helveticaWidth(new String(Character.toChars(base.codePointAt(0))));
        if (plain != null && base.codePointAt(0) != codePoint) {
            return plain;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return switch (script) {
            case HAN, HIRAGANA, KATAKANA, HANGUL -> WIDE_WIDTH;
            default -> DEFAULT_WIDTH;
        };
    }

    private Integer helveticaWidth(String character) {
        try {
            int width = Math.round(helvetica.getStringWidth(character));
            return width > 0 ? width : null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private void fill(Font font, Map<Integer, COSStream> glyphs) throws IOException {
        COSDictionary dictionary = font.dictionary;
        dictionary.setItem(COSName.TYPE, COSName.FONT);
        dictionary.setItem(COSName.SUBTYPE, COSName.TYPE3);
        dictionary.setItem(COSName.FONT_BBOX, numbers(0, -1000 * DESCENT, WIDE_WIDTH, 1000 * (1f - DESCENT)));
        dictionary.setItem(COSName.FONT_MATRIX, numbers(0.001f, 0, 0, 0.001f, 0, 0));
        dictionary.setItem(COSName.RESOURCES, new COSDictionary());
        int last = FIRST_CODE + font.codePoints.size() - 1;
        dictionary.setInt(COSName.FIRST_CHAR, SPACE);
        dictionary.setInt(COSName.LAST_CHAR, Math.max(SPACE, last));
        COSArray widths = new COSArray();
        COSArray differences = new COSArray();
        differences.add(COSInteger.get(SPACE));
        COSDictionary procedures = new COSDictionary();
        StringBuilder map = new StringBuilder();
        for (int code = SPACE; code <= Math.max(SPACE, last); code++) {
            int codePoint = code == SPACE ? ' ' : font.codePoints.get(code - FIRST_CODE);
            int width = width(codePoint);
            COSName glyph = COSName.getPDFName(String.format(Locale.ROOT, "g%02X", code));
            widths.add(COSInteger.get(width));
            differences.add(glyph);
            procedures.setItem(glyph, glyph(glyphs, width));
            map.append(String.format(Locale.ROOT, "<%02X> <", code));
            for (char unit : Character.toChars(codePoint)) {
                map.append(String.format(Locale.ROOT, "%04X", (int) unit));
            }
            map.append(">\n");
        }
        dictionary.setItem(COSName.WIDTHS, widths);
        COSDictionary encoding = new COSDictionary();
        encoding.setItem(COSName.TYPE, COSName.ENCODING);
        encoding.setItem(COSName.DIFFERENCES, differences);
        dictionary.setItem(COSName.ENCODING, encoding);
        dictionary.setItem(COSName.CHAR_PROCS, procedures);
        dictionary.setItem(COSName.TO_UNICODE, stream(toUnicode(map.toString())));
    }

    /** The procedure of an empty glyph: its width and nothing drawn. Glyphs of one width share it. */
    private COSStream glyph(Map<Integer, COSStream> glyphs, int width) throws IOException {
        COSStream known = glyphs.get(width);
        if (known == null) {
            known = stream(width + " 0 d0\n");
            glyphs.put(width, known);
        }
        return known;
    }

    private static String toUnicode(String pairs) {
        StringBuilder out = new StringBuilder();
        out.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
                .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
                .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
                .append("1 begincodespacerange\n<00> <FF>\nendcodespacerange\n");
        // A block holds at most 100 pairs.
        String[] lines = pairs.split("\n");
        for (int from = 0; from < lines.length; from += 100) {
            int to = Math.min(lines.length, from + 100);
            out.append(to - from).append(" beginbfchar\n");
            for (int i = from; i < to; i++) {
                out.append(lines[i]).append('\n');
            }
            out.append("endbfchar\n");
        }
        out.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        return out.toString();
    }

    /** The page content between {@code q} and {@code Q}, then {@code layer}, which starts with the {@code Q}. */
    private void wrap(PDPage page, String layer) throws IOException {
        COSDictionary dictionary = page.getCOSObject();
        COSBase stored = dictionary.getItem(COSName.CONTENTS);
        COSBase contents = dictionary.getDictionaryObject(COSName.CONTENTS);
        COSArray all = new COSArray();
        all.add(stream("q\n"));
        if (contents instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                all.add(array.get(i));
            }
        } else if (stored != null) {
            all.add(stored);
        }
        all.add(stream("\n" + layer));
        dictionary.setItem(COSName.CONTENTS, all);
    }

    private COSStream stream(String text) throws IOException {
        COSStream stream = document.getDocument().createCOSStream();
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
        }
        return stream;
    }

    private static COSArray numbers(float... values) {
        COSArray array = new COSArray();
        for (float value : values) {
            array.add(new COSFloat(value));
        }
        return array;
    }

    /** A number as PDF writes it: no exponent, at most four decimals. */
    private static void number(StringBuilder out, float value) {
        String text = String.format(Locale.ROOT, "%.4f", value);
        int end = text.length();
        while (text.charAt(end - 1) == '0') {
            end--;
        }
        if (text.charAt(end - 1) == '.') {
            end--;
        }
        text = text.substring(0, end);
        out.append(text.equals("-0") ? "0" : text).append(' ');
    }

    /** From a pixel of the rendered page to a point of the page as its content stream sees it. */
    private static final class Placement {
        private final float scale;
        private final float left;
        private final float bottom;
        private final float width;
        private final float height;
        private final int rotation;

        Placement(PDPage page, float dpi) {
            PDRectangle crop = page.getCropBox();
            this.scale = 72f / dpi;
            this.left = crop.getLowerLeftX();
            this.bottom = crop.getLowerLeftY();
            this.width = crop.getWidth();
            this.height = crop.getHeight();
            this.rotation = ((page.getRotation() % 360) + 360) % 360;
        }

        float[] toPage(float x, float y) {
            float px = x * scale;
            float py = y * scale;
            return switch (rotation) {
                case 90 -> new float[] {left + py, bottom + px};
                case 180 -> new float[] {left + width - px, bottom + py};
                case 270 -> new float[] {left + width - py, bottom + height - px};
                default -> new float[] {left + px, bottom + height - py};
            };
        }
    }
}
