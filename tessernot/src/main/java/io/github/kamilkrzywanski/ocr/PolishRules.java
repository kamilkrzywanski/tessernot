package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.image.Bgr;
import io.github.kamilkrzywanski.ocr.internal.image.Images;
import io.github.kamilkrzywanski.ocr.internal.post.Ctc;
import io.github.kamilkrzywanski.ocr.internal.post.DbPost;
import io.github.kamilkrzywanski.ocr.internal.post.PostalCities;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The second reads of {@link Corrections#POLISH}: a line whose first read shows one of a few known
 * misses is read again from a changed crop, and the new text is kept only when it differs in the
 * one expected way. The rules and what each was measured on are in docs/corrections.md. The
 * engine reads the lines; this class decides what to read again, through {@link LineReader}.
 * <p>
 * The single-string rules it applies are in {@link IndexKeep}, {@link NotchedZ}, {@link MarginKeep},
 * {@link FieldLabels}, {@link ParenSpace}, {@link DotKeep}, {@link EdgeRule} and {@link AmountSplit};
 * the runner-ups of the decoder are in {@code RunnerUps}.
 */
final class PolishRules {
    /** What the rules need of the engine: the page, read with the sessions of one worker. */
    interface LineReader {
        /** The recognizer's reading of a crop. */
        Ctc.Result read(Bgr crop);

        /** The crop turned by the line classifier, or the crop itself when there is none. */
        Bgr upright(Bgr crop);

        /** The part of the page under a quad, or null when it is empty. */
        Bgr crop(float[] quad);
    }

    /** A Polish postal code and the city token after it. */
    private static final Pattern POSTAL_CITY = Pattern.compile("^(\\d{2}-\\d{3})\\s+(\\S.*)$");
    /**
     * Black-and-white cut for a dot-matrix city that is still full of digits.
     * On the measured invoice 195–240 read Łódź apart from the capital L.
     * 190 turns the last letter into ż, and 245 collapses the code.
     */
    private static final int POSTAL_INK = 220;

    private final float recThresh;
    private final boolean rotateVertical;
    /** The Polish and English lists are loaded: the word search repairs what the margin reads would. */
    private final boolean wordLists;
    /** Null when the list of postal codes is not there. */
    private final PostalCities postal;

    PolishRules(float recThresh, boolean rotateVertical, boolean wordLists, PostalCities postal) {
        this.recThresh = recThresh;
        this.rotateVertical = rotateVertical;
        this.wordLists = wordLists;
        this.postal = postal;
    }

    /**
     * The second reads of one line, in the order of docs/corrections.md. {@code crop} is the line
     * as the recognizer saw it and {@code result} what it read. Each rule reads a changed crop
     * again and keeps the new text only when it differs from the first in the one expected way.
     */
    Ctc.Result reread(Bgr crop, DbPost.Box box, Ctc.Result result, LineReader reader) {
        String text = result.text();
        float score = result.score();
        Bgr indexed = Images.cutLeftIndex(crop);
        if (indexed != crop) {
            // The row number is real ink in the same box as the name. A second read is kept
            // only when it is this text without that leading number, so a letter stem stays.
            // On the measured invoice the cut drops ł and ó. With the lists the decoder's
            // word search puts them back; without them a shorter read that only lost those
            // two marks still confirms the cut.
            String stripped = text.replaceFirst("^[0-9]+\\s+", "");
            if (!stripped.equals(text)) {
                Ctc.Result again = reader.read(indexed);
                if (!again.text().isEmpty() && again.score() >= recThresh) {
                    String second = again.text();
                    if (IndexKeep.agrees(stripped, second)) {
                        text = stripped;
                        score = again.score();
                    }
                }
            }
        }
        Matcher coded = POSTAL_CITY.matcher(text.trim());
        if (coded.matches() && hasDigit(coded.group(2))) {
            Ctc.Result again = reader.read(inkThreshold(crop, POSTAL_INK));
            if (!again.text().isEmpty() && again.score() >= recThresh) {
                String second = again.text().trim();
                Matcher city = POSTAL_CITY.matcher(second);
                if (city.matches() && city.group(1).equals(coded.group(1))
                        && !hasDigit(city.group(2)) && city.group(2).length() >= 3) {
                    text = second;
                    score = again.score();
                }
            }
        }
        // Żyt's caron is a light gap in the top bar and disappears at height 48.
        // A dot above that gap is kept only when the second read adds ż or ź on this Z and nothing else.
        // On the measured scans that restores Żyt. SPRZEDAZ, Ziemia and Zapłacono stay.
        if (text.indexOf('Z') >= 0) {
            Bgr painted = NotchedZ.paint(crop, result.text());
            if (painted != null) {
                Ctc.Result again = reader.read(painted);
                if (!again.text().isEmpty() && again.score() >= recThresh) {
                    String merged = NotchedZ.keep(text, again.text());
                    if (merged != null) {
                        text = merged;
                        score = again.score();
                    }
                }
            }
        }
        // One official spelling for this code replaces the city when the letters
        // already match and the list does not remove a diacritic. 90-031 is Łódź.
        // A code shared by several places is absent, so that line stays.
        if (postal != null) {
            text = postal.apply(text);
        }
        // Without the word lists, white above and below lets the ł stroke survive
        // the resize to 48, a 4px shift down opens the gap in "i magazyn", and
        // eight white rows under the line open the gap before Wspólnicy as well.
        // Each second string is kept only when the only changes are that stroke
        // or a space between letters. The read with more of those repairs wins,
        // so the ł in Spółka is not kept at the cost of the missing space.
        // A plain margin also turns x into × and drops ł in Kłodzka, so those
        // reads are discarded. The lists already repair these lines, and a postal
        // line belongs to the threshold read above. Table cells never get here.
        text = marginText(crop, reader, text);
        // A VAT field label one edit from exactly one known label takes that spelling.
        // Lines with a digit, and any label that is already exact, stay as read.
        text = FieldLabels.apply(text);
        text = ParenSpace.apply(text);
        // The j in j.m. hangs out of a short header box. A second read 16 px to the
        // left and below is kept only when the whole line was ".x." and the new
        // text adds one letter in front of that dot. A cell rule read as 1 stays out.
        Ctc.Result hooked = rereadDot(box, reader, text);
        if (hooked != null) {
            text = hooked.text();
            score = hooked.score();
        }
        // A cell rule at the side of the crop is read as [ or |. Whitening those
        // tall edge strokes and reading again is kept only when the text loses
        // exactly that one fence character on a side. [VAT (%)| becomes VAT (%).
        Ctc.Result ruled = rereadEdge(crop, reader, text);
        if (ruled != null) {
            text = ruled.text();
            score = ruled.score();
        }
        return new Ctc.Result(text, score);
    }

    /**
     * A box that reads as an amount and a VAT rate is cut on the vertical rule between
     * them when each side reads back as that same token. {@code 8.13 23} becomes two lines.
     * Anything else, including a half that does not match, stays one line.
     */
    List<TextLine> splitAmounts(List<TextLine> lines, LineReader reader) {
        List<TextLine> out = new ArrayList<>(lines.size() + 1);
        for (TextLine line : lines) {
            TextLine[] pair = splitAmount(line, reader);
            if (pair == null) {
                out.add(line);
            } else {
                out.add(pair[0]);
                out.add(pair[1]);
            }
        }
        return out;
    }

    /** Whether any line reads as an amount and a rate, so that a recognizer is worth borrowing. */
    static boolean anyAmount(List<TextLine> lines) {
        for (TextLine line : lines) {
            if (AmountSplit.parts(line.text()) != null) {
                return true;
            }
        }
        return false;
    }

    private TextLine[] splitAmount(TextLine line, LineReader reader) {
        String[] parts = AmountSplit.parts(line.text());
        if (parts == null || line.bounds() == null) {
            return null;
        }
        // The detector quad leans a few pixels, so the upright crop is the bounds.
        // That rectangle is the one whose rule separates 8.13 from 23.
        Rectangle bounds = line.bounds();
        float x0 = bounds.x;
        float y0 = bounds.y;
        float x1 = bounds.x + bounds.width - 1f;
        float y1 = bounds.y + bounds.height - 1f;
        if (x1 <= x0 || y1 <= y0) {
            return null;
        }
        Bgr crop = reader.crop(new float[] {x0, y0, x1, y0, x1, y1, x0, y1});
        if (crop == null) {
            return null;
        }
        int[] cut = ruleCut(crop);
        if (cut == null) {
            return null;
        }
        Bgr leftImage = sliceX(crop, 0, cut[0]);
        Bgr rightImage = sliceX(crop, cut[1], crop.width());
        if (leftImage == null || rightImage == null) {
            return null;
        }
        leftImage = Images.mainInkBand(leftImage);
        rightImage = Images.mainInkBand(rightImage);
        Ctc.Result left = reader.read(leftImage);
        Ctc.Result right = reader.read(rightImage);
        if (!parts[0].equals(left.text()) || !parts[1].equals(right.text())) {
            return null;
        }
        float span = x1 - x0;
        if (crop.width() < 1) {
            return null;
        }
        float cutLeft = x0 + cut[0] * span / crop.width();
        float cutRight = x0 + cut[1] * span / crop.width();
        if (cutLeft <= x0 + 2f || cutRight >= x1 - 2f || cutRight <= cutLeft) {
            return null;
        }
        return new TextLine[] {
                lineBox(parts[0], left.score(), line.detScore(), x0, y0, cutLeft, y1),
                lineBox(parts[1], right.score(), line.detScore(), cutRight, y0, x1, y1)
        };
    }

    /** Left edge of the rule and the first column after it, or null. */
    private static int[] ruleCut(Bgr crop) {
        int width = crop.width();
        int height = crop.height();
        if (width < 40 || height < 16) {
            return null;
        }
        int from = width / 5;
        int to = width - from;
        byte[] data = crop.data();
        int[] dark = new int[width];
        for (int y = 0; y < height; y++) {
            int row = y * width * 3;
            for (int x = from; x < to; x++) {
                if (luma(data, row + x * 3) < 160) {
                    dark[x]++;
                }
            }
        }
        int best = -1;
        int bestDark = 0;
        for (int x = from; x < to; x++) {
            if (dark[x] > bestDark) {
                bestDark = dark[x];
                best = x;
            }
        }
        if (best < 0 || bestDark * 2 < height) {
            return null;
        }
        int left = best;
        while (left > from && dark[left - 1] * 3 >= bestDark) {
            left--;
        }
        int right = best;
        while (right + 1 < to && dark[right + 1] * 3 >= bestDark) {
            right++;
        }
        if (right - left > 4 || left < 8 || width - right < 8) {
            return null;
        }
        return new int[] {left, right + 1};
    }

    private static Bgr sliceX(Bgr src, int x0, int x1) {
        int width = x1 - x0;
        int height = src.height();
        if (width < 2 || height < 2) {
            return null;
        }
        byte[] out = new byte[width * height * 3];
        byte[] in = src.data();
        for (int y = 0; y < height; y++) {
            System.arraycopy(in, (y * src.width() + x0) * 3, out, y * width * 3, width * 3);
        }
        return new Bgr(out, width, height);
    }

    private static TextLine lineBox(String text, float score, float detScore,
                                    float x0, float y0, float x1, float y1) {
        float[] quad = {x0, y0, x1, y0, x1, y1, x0, y1};
        int x = (int) Math.floor(Math.min(x0, x1));
        int y = (int) Math.floor(Math.min(y0, y1));
        int w = Math.max(1, (int) Math.ceil(Math.max(x0, x1)) - x);
        int h = Math.max(1, (int) Math.ceil(Math.max(y0, y1)) - y);
        return new TextLine(text, score, detScore, quad, new Rectangle(x, y, w, h));
    }

    private Ctc.Result rereadEdge(Bgr crop, LineReader reader, String text) {
        if (!EdgeRule.candidate(text)) {
            return null;
        }
        Bgr blank = blankEdgeRules(crop);
        if (blank == null) {
            return null;
        }
        blank = reader.upright(blank);
        blank = Images.mainInkBand(blank);
        Ctc.Result result = reader.read(blank);
        if (result.text().isEmpty() || result.score() < recThresh) {
            return null;
        }
        String second = ParenSpace.apply(FieldLabels.apply(result.text()));
        if (EdgeRule.keep(text, second) == null) {
            return null;
        }
        return new Ctc.Result(second, result.score());
    }

    /** Tall dark columns in the outer 16 px become white. Null when none qualify. */
    private static Bgr blankEdgeRules(Bgr src) {
        int width = src.width();
        int height = src.height();
        if (width < 48 || height < 16) {
            return null;
        }
        int edge = Math.min(16, width / 5);
        byte[] in = src.data();
        int[] dark = new int[width];
        for (int y = 0; y < height; y++) {
            int row = y * width * 3;
            for (int x = 0; x < edge; x++) {
                if (luma(in, row + x * 3) < 160) {
                    dark[x]++;
                }
            }
            for (int x = width - edge; x < width; x++) {
                if (luma(in, row + x * 3) < 160) {
                    dark[x]++;
                }
            }
        }
        boolean[] cut = new boolean[width];
        boolean any = false;
        for (int x = 0; x < edge; x++) {
            if (dark[x] * 4 > height) {
                cut[x] = true;
                any = true;
            }
        }
        for (int x = width - edge; x < width; x++) {
            if (dark[x] * 4 > height) {
                cut[x] = true;
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        byte[] out = in.clone();
        for (int x = 0; x < width; x++) {
            if (!cut[x]) {
                continue;
            }
            for (int y = 0; y < height; y++) {
                int offset = (y * width + x) * 3;
                out[offset] = (byte) 255;
                out[offset + 1] = (byte) 255;
                out[offset + 2] = (byte) 255;
            }
        }
        return new Bgr(out, width, height);
    }

    private static int luma(byte[] data, int offset) {
        return ((data[offset + 2] & 255) * 54 + (data[offset + 1] & 255) * 183 + (data[offset] & 255) * 19) >> 8;
    }

    private Ctc.Result rereadDot(DbPost.Box box, LineReader reader, String text) {
        if (!DotKeep.candidate(text) || !OcrEngine.axisAligned(box.quad())) {
            return null;
        }
        Bgr again = reader.crop(DotKeep.grow(box.quad()));
        if (again == null) {
            return null;
        }
        if (rotateVertical && again.height() >= again.width() * 1.5f) {
            again = Images.rot90ccw(again);
        }
        again = reader.upright(again);
        again = Images.mainInkBand(again);
        Ctc.Result result = reader.read(again);
        if (result.text().isEmpty() || result.score() < recThresh) {
            return null;
        }
        String second = ParenSpace.apply(FieldLabels.apply(result.text()));
        if (DotKeep.keep(text, second) == null) {
            return null;
        }
        return new Ctc.Result(second, result.score());
    }

    private String marginText(Bgr crop, LineReader reader, String text) {
        if (wordLists || !MarginKeep.candidate(text) || POSTAL_CITY.matcher(text.trim()).matches()) {
            return text;
        }
        String best = text;
        int bestRepairs = 0;
        Bgr[] alts = {
                padVertical(crop, 4, 4),
                shiftDown(crop, 4),
                padVertical(crop, 0, 8)
        };
        for (Bgr alt : alts) {
            String again = readMargin(alt, reader);
            int repairs = MarginKeep.repairs(text, again);
            if (repairs > bestRepairs) {
                best = again;
                bestRepairs = repairs;
            }
        }
        return best;
    }

    private String readMargin(Bgr image, LineReader reader) {
        Ctc.Result again = reader.read(image);
        if (again.text().isEmpty() || again.score() < recThresh) {
            return "";
        }
        return again.text();
    }

    /** White rows above and below. The ink itself is not scaled yet. */
    private static Bgr padVertical(Bgr src, int top, int bottom) {
        int width = src.width();
        int height = src.height();
        byte[] out = new byte[width * (height + top + bottom) * 3];
        Arrays.fill(out, (byte) 255);
        System.arraycopy(src.data(), 0, out, top * width * 3, width * height * 3);
        return new Bgr(out, width, height + top + bottom);
    }

    /** Move the ink down by {@code dy} rows. The bottom rows fall outside the crop. */
    private static Bgr shiftDown(Bgr src, int dy) {
        int width = src.width();
        int height = src.height();
        if (dy <= 0 || dy >= height) {
            return src;
        }
        byte[] out = new byte[width * height * 3];
        Arrays.fill(out, (byte) 255);
        System.arraycopy(src.data(), 0, out, dy * width * 3, (height - dy) * width * 3);
        return new Bgr(out, width, height);
    }

    private static boolean hasDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** One global cut. Gray ink becomes black and the paper becomes white. */
    private static Bgr inkThreshold(Bgr src, int threshold) {
        byte[] in = src.data();
        byte[] out = new byte[in.length];
        int pixels = src.width() * src.height();
        for (int i = 0; i < pixels; i++) {
            int o = i * 3;
            int luma = ((in[o + 2] & 255) * 54 + (in[o + 1] & 255) * 183 + (in[o] & 255) * 19) >> 8;
            byte v = (byte) (luma < threshold ? 0 : 255);
            out[o] = v;
            out[o + 1] = v;
            out[o + 2] = v;
        }
        return new Bgr(out, src.width(), src.height());
    }
}
