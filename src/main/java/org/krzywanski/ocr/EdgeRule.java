package org.krzywanski.ocr;

/**
 * A cell rule inside the crop is read as {@code [} or {@code |}.
 * The invoice header comes back as {@code [VAT (%)|}. Whitening the tall strokes
 * in the outer columns and reading again leaves {@code VAT (%)}. The second
 * text is kept only when that is the whole difference: one fence character at
 * the start, one at the end, or both. Any other change stays on the first read.
 */
final class EdgeRule {
    private EdgeRule() {
    }

    static boolean candidate(String text) {
        if (text == null || text.length() < 2) {
            return false;
        }
        return fence(text.charAt(0)) || fence(text.charAt(text.length() - 1));
    }

    /**
     * @return the second line when it is {@code first} without one leading fence
     *         character and one trailing fence character. Otherwise null.
     */
    static String keep(String first, String second) {
        if (!candidate(first) || second == null || second.isEmpty() || first.equals(second)) {
            return null;
        }
        String stripped = strip(first);
        return stripped.equals(second) ? second : null;
    }

    static String strip(String text) {
        int start = 0;
        int end = text.length();
        if (start < end && fence(text.charAt(start))) {
            start++;
        }
        if (end > start && fence(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean fence(char c) {
        return c == '[' || c == ']' || c == '|';
    }
}
