package org.krzywanski.ocr.post;

import org.krzywanski.ocr.OcrException;
import org.krzywanski.ocr.TextLine;
import org.krzywanski.ocr.TextTable;
import org.krzywanski.ocr.image.Bgr;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Ruled tables on a printed page.
 * A table is a hairline grid of at least two rows and two columns. The same text boxes stay
 * in reading order; this only places them in cells. A box cut by an interior rule is read
 * again, once per piece, when {@code reader} is not null. Prices with no rule are not a table.
 */
public final class Tables {
    /** Paper is brighter than its rules by about this much. Letter stems fail the thickness cut. */
    private static final int PAPER_GAP = 90;
    /** A stroke thicker than this is text or the scan edge, not a rule. */
    private static final int MAX_THICKNESS = 10;
    /** White holes of this many pixels still belong to the same stroke. */
    private static final int RUN_GAP = 4;
    /** Collinear pieces farther apart than this are two rules. */
    private static final int MERGE_GAP = 18;
    /**
     * One pixel more, and only when both pieces are at least {@link #LONG_PIECE} long.
     * Eighteen white rows is a letter on the line between Liczba and j.m. The same
     * extra pixel on a short piece joins digit stems and splits the VAT amounts.
     */
    private static final int LETTER_GAP = 19;
    /** Shorter than this, a piece is a stem, not a broken rule. */
    private static final int LONG_PIECE = 48;
    /** Hairlines whose centers are this close are one rule. */
    private static final int ALIGN = 4;
    /** A line must reach the crossing within this slack. */
    private static final int CROSS_SLACK = 6;
    /** Ignore specks shorter than this before they are joined. */
    private static final int RAW_MIN = 12;
    /** A joined rule shorter than this cannot separate two cells. */
    private static final int MIN_RULE = 30;
    /** A rule this close to spanning the page is the scan border. */
    private static final double PAGE_SPAN = 0.97;
    /** An interior line has to cover this much of the outer grid. */
    private static final double SPAN = 0.55;
    /** A rule splits a box only when it sticks out of that box. */
    private static final int STICK = 3;
    /**
     * Both sides of a cut must be at least this wide. Unclip lets a box overlap the next
     * rule by a few pixels, and that sliver is not a cell. The unit Szt. overlaps the
     * j.m. rule by 23 pixels, and that sliver reads as a digit; the row number beside
     * a name is 36 pixels and still has to be cut.
     */
    private static final int MIN_PIECE = 28;

    private Tables() {
    }

    public static List<TextTable> find(Bgr page, List<TextLine> lines, Function<Bgr, String> reader) {
        if (page == null) {
            throw new OcrException("image is null");
        }
        if (lines == null) {
            throw new OcrException("lines are null");
        }
        if (lines.isEmpty() || page.width() < 2 || page.height() < 2) {
            return List.of();
        }
        int thresh = threshold(page);
        List<Rule> horizontal = hairlines(page, thresh, false);
        List<Rule> vertical = hairlines(page, thresh, true);
        List<Grid> grids = grids(horizontal, vertical);
        if (grids.isEmpty()) {
            return List.of();
        }
        List<TextLine> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparingInt((TextLine line) -> line.bounds().y)
                .thenComparingInt(line -> line.bounds().x));
        List<TextTable> tables = new ArrayList<>();
        for (Grid grid : grids) {
            TextTable table = fill(page, grid, ordered, reader);
            if (table != null) {
                tables.add(table);
            }
        }
        tables.sort(Comparator.comparingInt((TextTable table) -> table.bounds().y)
                .thenComparingInt(table -> table.bounds().x));
        return List.copyOf(tables);
    }

    private static int threshold(Bgr page) {
        int[] hist = new int[256];
        int samples = 0;
        byte[] src = page.data();
        int pixels = page.width() * page.height();
        for (int i = 0; i < pixels; i += 8) {
            hist[luma(src, i)]++;
            samples++;
        }
        int seen = 0;
        int median = 255;
        int half = Math.max(1, samples / 2);
        for (int value = 0; value < hist.length; value++) {
            seen += hist[value];
            if (seen >= half) {
                median = value;
                break;
            }
        }
        return Math.max(40, Math.min(190, median - PAPER_GAP));
    }

    private static List<Rule> hairlines(Bgr page, int thresh, boolean vertical) {
        List<Rule> stacked = stack(runs(page, thresh, vertical));
        List<Rule> joined = new ArrayList<>();
        for (Rule rule : stacked) {
            if (!rule.dead && rule.thickness() <= MAX_THICKNESS) {
                joined.add(rule);
            }
        }
        List<Rule> lines = collinear(joined);
        int limit = (int) ((vertical ? page.height() : page.width()) * PAGE_SPAN);
        List<Rule> kept = new ArrayList<>();
        for (Rule rule : lines) {
            if (!rule.dead && rule.thickness() <= MAX_THICKNESS && rule.length() >= MIN_RULE && rule.length() < limit) {
                kept.add(rule);
            }
        }
        return kept;
    }

    private static List<Rule> runs(Bgr page, int thresh, boolean vertical) {
        byte[] src = page.data();
        int width = page.width();
        int height = page.height();
        int outer = vertical ? width : height;
        int inner = vertical ? height : width;
        List<Rule> out = new ArrayList<>();
        for (int across = 0; across < outer; across++) {
            int start = -1;
            int last = -1;
            for (int along = 0; along <= inner; along++) {
                boolean on = along < inner && dark(src, vertical ? along * width + across : across * width + along, thresh);
                if (on) {
                    if (start < 0) {
                        start = along;
                    }
                    last = along;
                } else if (start >= 0 && (along == inner || along - last > RUN_GAP)) {
                    if (last - start + 1 >= RAW_MIN) {
                        out.add(Rule.at(across, start, last));
                    }
                    start = -1;
                }
            }
        }
        return out;
    }

    /**
     * Adjacent rows of one stroke become one band. A band that grows too thick is text.
     * A band that has already ended cannot host a later run, so it leaves the open list.
     */
    private static List<Rule> stack(List<Rule> runs) {
        runs.sort(Comparator.comparingInt((Rule rule) -> rule.pos0).thenComparingInt(rule -> rule.from));
        List<Rule> bands = new ArrayList<>();
        List<Rule> open = new ArrayList<>();
        for (Rule run : runs) {
            int live = 0;
            Rule host = null;
            for (int i = 0; i < open.size(); i++) {
                Rule band = open.get(i);
                if (band.pos1 < run.pos0 - 1) {
                    continue;
                }
                open.set(live++, band);
                if (host == null && overlap(band, run) >= 0.6f * Math.min(band.length(), run.length())) {
                    host = band;
                }
            }
            if (live < open.size()) {
                open.subList(live, open.size()).clear();
            }
            if (host == null) {
                Rule created = run.copy();
                bands.add(created);
                open.add(created);
            } else {
                host.pos1 = Math.max(host.pos1, run.pos1);
                host.from = Math.min(host.from, run.from);
                host.to = Math.max(host.to, run.to);
                if (host.thickness() > MAX_THICKNESS) {
                    host.dead = true;
                }
            }
        }
        return bands;
    }

    private static List<Rule> collinear(List<Rule> rules) {
        rules.sort(Comparator.comparingInt((Rule rule) -> rule.from).thenComparingInt(rule -> rule.pos0));
        List<Rule> out = new ArrayList<>();
        for (Rule rule : rules) {
            Rule host = null;
            int center = rule.pos();
            for (Rule prev : out) {
                int gap = rule.length() >= LONG_PIECE && prev.length() >= LONG_PIECE ? LETTER_GAP : MERGE_GAP;
                if (Math.abs(prev.pos() - center) <= ALIGN
                        && rule.from <= prev.to + gap
                        && prev.from <= rule.to + gap) {
                    host = prev;
                    break;
                }
            }
            if (host == null) {
                out.add(rule.copy());
                continue;
            }
            host.from = Math.min(host.from, rule.from);
            host.to = Math.max(host.to, rule.to);
            host.pos0 = Math.min(host.pos0, rule.pos0);
            host.pos1 = Math.max(host.pos1, rule.pos1);
            if (host.thickness() > MAX_THICKNESS) {
                host.dead = true;
            }
        }
        return out;
    }

    private static List<Grid> grids(List<Rule> horizontal, List<Rule> vertical) {
        int hCount = horizontal.size();
        int count = hCount + vertical.size();
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) {
            parent[i] = i;
        }
        for (int h = 0; h < hCount; h++) {
            for (int v = 0; v < vertical.size(); v++) {
                if (crosses(horizontal.get(h), vertical.get(v))) {
                    union(parent, h, hCount + v);
                }
            }
        }
        List<List<Rule>> groupsH = new ArrayList<>();
        List<List<Rule>> groupsV = new ArrayList<>();
        int[] slot = new int[count];
        java.util.Arrays.fill(slot, -1);
        for (int i = 0; i < count; i++) {
            int root = find(parent, i);
            if (slot[root] < 0) {
                slot[root] = groupsH.size();
                groupsH.add(new ArrayList<>());
                groupsV.add(new ArrayList<>());
            }
            if (i < hCount) {
                groupsH.get(slot[root]).add(horizontal.get(i));
            } else {
                groupsV.get(slot[root]).add(vertical.get(i - hCount));
            }
        }
        List<Grid> grids = new ArrayList<>();
        for (int i = 0; i < groupsH.size(); i++) {
            Grid grid = grid(groupsH.get(i), groupsV.get(i));
            if (grid != null) {
                grids.add(grid);
            }
        }
        return grids;
    }

    private static Grid grid(List<Rule> horizontal, List<Rule> vertical) {
        List<Rule> hs = new ArrayList<>(horizontal);
        List<Rule> vs = new ArrayList<>(vertical);
        boolean changed = true;
        while (changed) {
            changed = false;
            List<Rule> nextH = new ArrayList<>();
            List<Rule> nextV = new ArrayList<>();
            for (Rule rule : hs) {
                if (crossings(rule, vs, true) >= 2) {
                    nextH.add(rule);
                } else {
                    changed = true;
                }
            }
            for (Rule rule : vs) {
                if (crossings(rule, hs, false) >= 2) {
                    nextV.add(rule);
                } else {
                    changed = true;
                }
            }
            hs = nextH;
            vs = nextV;
        }
        if (hs.size() < 3 || vs.size() < 3) {
            return null;
        }
        hs.sort(Comparator.comparingInt(Rule::pos));
        vs.sort(Comparator.comparingInt(Rule::pos));
        int top = hs.get(0).pos();
        int bottom = hs.get(hs.size() - 1).pos();
        int left = vs.get(0).pos();
        int right = vs.get(vs.size() - 1).pos();
        hs.removeIf(rule -> cover(rule, left, right) < SPAN);
        vs.removeIf(rule -> cover(rule, top, bottom) < SPAN);
        hs = dedup(hs);
        vs = dedup(vs);
        if (hs.size() < 3 || vs.size() < 3) {
            return null;
        }
        return new Grid(hs, vs);
    }

    private static List<Rule> dedup(List<Rule> rules) {
        List<Rule> out = new ArrayList<>();
        for (Rule rule : rules) {
            if (!out.isEmpty() && rule.pos() - out.get(out.size() - 1).pos() <= ALIGN) {
                if (rule.length() > out.get(out.size() - 1).length()) {
                    out.set(out.size() - 1, rule);
                }
            } else {
                out.add(rule);
            }
        }
        return out;
    }

    private static TextTable fill(Bgr page, Grid grid, List<TextLine> lines, Function<Bgr, String> reader) {
        int rows = grid.horizontal.size() - 1;
        int columns = grid.vertical.size() - 1;
        String[][] cells = new String[rows][columns];
        for (int row = 0; row < rows; row++) {
            Arrays.fill(cells[row], "");
        }
        for (TextLine line : lines) {
            Rectangle box = line.bounds();
            int x1 = box.x + box.width - 1;
            int y1 = box.y + box.height - 1;
            float cx = box.x + box.width / 2f;
            float cy = box.y + box.height / 2f;
            if (slot(grid.vertical, cx) < 0 || slot(grid.horizontal, cy) < 0) {
                continue;
            }
            List<Integer> xs = cuts(grid.vertical, box.x, x1, box.y, y1);
            List<Integer> ys = cuts(grid.horizontal, box.y, y1, box.x, x1);
            if (reader == null || xs.size() < 3 && ys.size() < 3) {
                String text = line.text();
                if (reader != null && ruleThroughGlyph(grid.vertical, box.x, x1, box.y, y1)) {
                    text = preferCase(text, readCell(page, grid, cx, cy, reader));
                }
                place(cells, grid, cx, cy, text);
                continue;
            }
            boolean any = false;
            for (int y = 0; y < ys.size() - 1; y++) {
                for (int x = 0; x < xs.size() - 1; x++) {
                    int left = xs.get(x) + (x > 0 ? 1 : 0);
                    int right = xs.get(x + 1) - (x + 1 < xs.size() - 1 ? 1 : 0);
                    int top = ys.get(y) + (y > 0 ? 1 : 0);
                    int bottom = ys.get(y + 1) - (y + 1 < ys.size() - 1 ? 1 : 0);
                    int pieceWidth = right - left + 1;
                    int pieceHeight = bottom - top + 1;
                    if (pieceWidth < 2 || pieceHeight < 2) {
                        continue;
                    }
                    if ((xs.size() >= 3 && pieceWidth < MIN_PIECE) || (ys.size() >= 3 && pieceHeight < MIN_PIECE)) {
                        continue;
                    }
                    Bgr crop = slice(page, left, top, right, bottom);
                    if (crop == null) {
                        continue;
                    }
                    String text = reader.apply(crop);
                    if (text == null || text.isEmpty()) {
                        continue;
                    }
                    any = true;
                    place(cells, grid, left + (right - left) / 2f, top + (bottom - top) / 2f, text);
                }
            }
            if (!any) {
                place(cells, grid, cx, cy, line.text());
            }
        }
        int filled = 0;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (!cells[row][column].isEmpty()) {
                    filled++;
                }
            }
        }
        // A grid with one line is not a table. Filling its empty cells would promote
        // letter strokes into a second table.
        if (filled >= 2 && reader != null) {
            fillEmpty(page, grid, cells, reader);
        }
        List<List<String>> copy = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            copy.add(new ArrayList<>(Arrays.asList(cells[row])));
        }
        if (filled < 2) {
            return null;
        }
        Rule left = grid.vertical.get(0);
        Rule right = grid.vertical.get(grid.vertical.size() - 1);
        Rule top = grid.horizontal.get(0);
        Rule bottom = grid.horizontal.get(grid.horizontal.size() - 1);
        Rectangle bounds = new Rectangle(
                left.pos(), top.pos(), Math.max(1, right.pos() - left.pos()), Math.max(1, bottom.pos() - top.pos()));
        return new TextTable(copy, bounds);
    }

    /**
     * A vertical rule this far inside the box, but not far enough to split it, runs through
     * the glyph. The detector box then reads Szt.; the cell interior, clear of that rule, reads szt.
     */
    private static final int RULE_THROUGH = 20;
    /** A missed glyph inside a ruled cell, clear of the rules. */
    private static final int CELL_INSET = 8;
    /** Fewer dark pixels than this is paper, not a character. */
    private static final int MIN_CELL_INK = 40;

    /**
     * An empty cell that still holds ink is read once. Only a short number or a short
     * word is kept, so a rule fragment cannot become a sentence. On the invoice this
     * is the 1 in Liczba and the Lp header, both missed by the detector.
     */
    private static void fillEmpty(Bgr page, Grid grid, String[][] cells, Function<Bgr, String> reader) {
        int rows = cells.length;
        int columns = cells[0].length;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (!cells[row][column].isEmpty()) {
                    continue;
                }
                int left = grid.vertical.get(column).pos() + CELL_INSET;
                int right = grid.vertical.get(column + 1).pos() - CELL_INSET;
                int top = grid.horizontal.get(row).pos() + CELL_INSET;
                int bottom = grid.horizontal.get(row + 1).pos() - CELL_INSET;
                if (right - left < 8 || bottom - top < 8) {
                    continue;
                }
                Bgr crop = slice(page, left, top, right, bottom);
                if (crop == null || !textInk(crop)) {
                    continue;
                }
                String text = reader.apply(crop);
                if (cellToken(text)) {
                    cells[row][column] = text.trim();
                }
            }
        }
    }

    private static boolean textInk(Bgr crop) {
        byte[] data = crop.data();
        int dark = 0;
        int pixels = crop.width() * crop.height();
        for (int i = 0; i < pixels; i++) {
            if (luma(data, i) < 160) {
                dark++;
            }
        }
        return dark >= MIN_CELL_INK && dark * 5 < pixels;
    }

    /**
     * The cell read replaces the line only when the letters match and the case does not.
     * Any other difference keeps the line, which already went through the dictionary.
     */
    static String preferCase(String line, String cell) {
        if (line == null || cell == null) {
            return line;
        }
        String trimmed = cell.trim();
        if (!trimmed.isEmpty() && !trimmed.equals(line) && trimmed.equalsIgnoreCase(line)) {
            return trimmed;
        }
        return line;
    }

    private static boolean ruleThroughGlyph(List<Rule> vertical, int start, int end, int along0, int along1) {
        for (Rule rule : vertical) {
            if (rule.to < along0 || rule.from > along1) {
                continue;
            }
            if (!(rule.from <= along0 - STICK || rule.to >= along1 + STICK)) {
                continue;
            }
            int pos = rule.pos();
            int left = pos - start;
            int right = end - pos;
            if (left >= RULE_THROUGH && left < MIN_PIECE && right >= MIN_PIECE) {
                return true;
            }
            if (right >= RULE_THROUGH && right < MIN_PIECE && left >= MIN_PIECE) {
                return true;
            }
        }
        return false;
    }

    private static String readCell(Bgr page, Grid grid, float x, float y, Function<Bgr, String> reader) {
        int column = slot(grid.vertical, x);
        int row = slot(grid.horizontal, y);
        if (row < 0 || column < 0) {
            return "";
        }
        int left = grid.vertical.get(column).pos() + CELL_INSET;
        int right = grid.vertical.get(column + 1).pos() - CELL_INSET;
        int top = grid.horizontal.get(row).pos() + CELL_INSET;
        int bottom = grid.horizontal.get(row + 1).pos() - CELL_INSET;
        Bgr crop = slice(page, left, top, right, bottom);
        if (crop == null) {
            return "";
        }
        String text = reader.apply(crop);
        return text == null ? "" : text;
    }

    /** A number, or a short word. A single odd mark, including a false digit, is not. */
    static boolean cellToken(String text) {
        if (text == null) {
            return false;
        }
        String token = text.trim();
        if (token.isEmpty() || token.length() > 8) {
            return false;
        }
        if (token.matches("[0-9]{1,6}([.,][0-9]{1,2})?")) {
            return true;
        }
        return token.matches("[A-Za-zĄąĆćĘęŁłŃńÓóŚśŹźŻż]{1,6}\\.?");
    }

    /**
     * Inclusive edges of the pieces. {@code start} and {@code end} are the box on the cut axis.
     * {@code along0} and {@code along1} are the box on the other axis, where a real rule sticks out.
     */
    private static List<Integer> cuts(List<Rule> rules, int start, int end, int along0, int along1) {
        List<Integer> edges = new ArrayList<>();
        edges.add(start);
        for (Rule rule : rules) {
            int pos = rule.pos();
            if (pos - start < MIN_PIECE || end - pos < MIN_PIECE) {
                continue;
            }
            boolean overlaps = rule.from <= along1 && rule.to >= along0;
            boolean sticks = rule.from <= along0 - STICK || rule.to >= along1 + STICK;
            if (overlaps && sticks) {
                edges.add(pos);
            }
        }
        if (edges.size() > 16) {
            return List.of(start, end);
        }
        edges.add(end);
        edges.sort(Integer::compareTo);
        return edges;
    }

    private static void place(String[][] cells, Grid grid, float x, float y, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int column = slot(grid.vertical, x);
        int row = slot(grid.horizontal, y);
        if (row < 0 || column < 0) {
            return;
        }
        if (cells[row][column].isEmpty()) {
            cells[row][column] = text;
        } else {
            cells[row][column] = cells[row][column] + " " + text;
        }
    }

    private static int slot(List<Rule> lines, float value) {
        for (int i = 0; i < lines.size() - 1; i++) {
            if (value >= lines.get(i).pos() && value < lines.get(i + 1).pos()) {
                return i;
            }
        }
        return -1;
    }

    private static Bgr slice(Bgr page, int x0, int y0, int x1, int y1) {
        x0 = Math.max(0, Math.min(page.width() - 1, x0));
        y0 = Math.max(0, Math.min(page.height() - 1, y0));
        x1 = Math.max(0, Math.min(page.width() - 1, x1));
        y1 = Math.max(0, Math.min(page.height() - 1, y1));
        int width = x1 - x0 + 1;
        int height = y1 - y0 + 1;
        if (width < 2 || height < 2) {
            return null;
        }
        byte[] out = new byte[width * height * 3];
        byte[] src = page.data();
        for (int y = 0; y < height; y++) {
            System.arraycopy(src, ((y0 + y) * page.width() + x0) * 3, out, y * width * 3, width * 3);
        }
        return new Bgr(out, width, height);
    }

    private static int crossings(Rule rule, List<Rule> others, boolean ruleIsHorizontal) {
        int count = 0;
        for (Rule other : others) {
            if (ruleIsHorizontal ? crosses(rule, other) : crosses(other, rule)) {
                count++;
            }
        }
        return count;
    }

    private static boolean crosses(Rule horizontal, Rule vertical) {
        int vx = vertical.pos();
        int hy = horizontal.pos();
        return vx >= horizontal.from - CROSS_SLACK && vx <= horizontal.to + CROSS_SLACK
                && hy >= vertical.from - CROSS_SLACK && hy <= vertical.to + CROSS_SLACK;
    }

    private static double cover(Rule rule, int from, int to) {
        int span = Math.abs(to - from);
        if (span == 0) {
            return 1;
        }
        int overlap = Math.min(rule.to, Math.max(from, to)) - Math.max(rule.from, Math.min(from, to));
        if (overlap < 0) {
            return 0;
        }
        return overlap / (double) span;
    }

    private static int overlap(Rule left, Rule right) {
        return Math.max(0, Math.min(left.to, right.to) - Math.max(left.from, right.from) + 1);
    }

    private static boolean dark(byte[] src, int index, int thresh) {
        return luma(src, index) < thresh;
    }

    private static int luma(byte[] src, int index) {
        int pixel = index * 3;
        return ((src[pixel] & 255) + (src[pixel + 1] & 255) + (src[pixel + 2] & 255)) / 3;
    }

    private static int find(int[] parent, int index) {
        int root = index;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[index] != root) {
            int next = parent[index];
            parent[index] = root;
            index = next;
        }
        return root;
    }

    private static void union(int[] parent, int left, int right) {
        int a = find(parent, left);
        int b = find(parent, right);
        if (a != b) {
            parent[b] = a;
        }
    }

    private static final class Grid {
        private final List<Rule> horizontal;
        private final List<Rule> vertical;

        private Grid(List<Rule> horizontal, List<Rule> vertical) {
            this.horizontal = horizontal;
            this.vertical = vertical;
        }
    }

    /** A straight stroke. {@code pos} is across the line and {@code from}/{@code to} run along it. */
    private static final class Rule {
        private int pos0;
        private int pos1;
        private int from;
        private int to;
        private boolean dead;

        private static Rule at(int across, int from, int to) {
            Rule rule = new Rule();
            rule.pos0 = across;
            rule.pos1 = across;
            rule.from = from;
            rule.to = to;
            return rule;
        }

        private Rule copy() {
            Rule rule = new Rule();
            rule.pos0 = pos0;
            rule.pos1 = pos1;
            rule.from = from;
            rule.to = to;
            rule.dead = dead;
            return rule;
        }

        private int pos() {
            return (pos0 + pos1) / 2;
        }

        private int thickness() {
            return pos1 - pos0 + 1;
        }

        private int length() {
            return to - from + 1;
        }
    }
}
