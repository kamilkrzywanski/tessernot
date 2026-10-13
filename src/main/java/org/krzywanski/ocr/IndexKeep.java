package org.krzywanski.ocr;

/**
 * The row-index cut is confirmed when the shorter read is the same letters.
 * The cut may drop {@code ł} or {@code ó}; the first read already has them,
 * so the caller keeps that text without the leading number.
 */
final class IndexKeep {
    private IndexKeep() {
    }

    static boolean agrees(String stripped, String second) {
        if (stripped == null || second == null || stripped.isEmpty() || stripped.length() != second.length()) {
            return false;
        }
        for (int i = 0; i < stripped.length(); i++) {
            char full = stripped.charAt(i);
            char cut = second.charAt(i);
            if (full == cut || dropped(full, cut)) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean dropped(char full, char cut) {
        return (full == '\u0142' && cut == 'l')
                || (full == '\u0141' && cut == 'L')
                || (full == '\u00f3' && cut == 'o')
                || (full == '\u00d3' && cut == 'O');
    }
}
