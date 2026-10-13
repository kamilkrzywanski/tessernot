package io.github.kamilkrzywanski.ocr;

/**
 * A second read with a few pixels of white margin is kept only when it adds
 * {@code ł} on {@code t} or {@code l}, or inserts a space between two letters.
 * Anything else, including a lost diacritic, stays on the first read.
 */
final class MarginKeep {
    private MarginKeep() {
    }

    /** Lines that can gain ł or that glue a short word onto the next one. */
    static boolean candidate(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 't' || c == 'T' || c == 'l' || c == 'L') {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the second line when every difference is an allowed ł or a single
     *         space between letters, and there is at least one such difference.
     *         At most two inserted spaces. Otherwise null.
     */
    static String keep(String first, String second) {
        if (first == null || second == null || first.equals(second) || second.isEmpty()) {
            return null;
        }
        int i = 0;
        int j = 0;
        int inserted = 0;
        int upgrades = 0;
        while (i < first.length() && j < second.length()) {
            char a = first.charAt(i);
            char b = second.charAt(j);
            if (a == b) {
                i++;
                j++;
                continue;
            }
            if (b == ' ' && betweenLetters(second, j)) {
                j++;
                inserted++;
                if (inserted > 2) {
                    return null;
                }
                continue;
            }
            if (stroke(a, b)) {
                i++;
                j++;
                upgrades++;
                continue;
            }
            return null;
        }
        if (i != first.length() || j != second.length() || (inserted == 0 && upgrades == 0)) {
            return null;
        }
        return second;
    }

    private static boolean betweenLetters(String text, int space) {
        return space > 0 && space + 1 < text.length()
                && Character.isLetter(text.charAt(space - 1))
                && Character.isLetter(text.charAt(space + 1));
    }

    /** t or l gaining the Polish stroke. The other direction is not an upgrade. */
    /**
     * How many allowed repairs {@code second} makes on {@code first}.
     * A rejected second read scores zero. A tie stays with the earlier read.
     */
    static int repairs(String first, String second) {
        if (keep(first, second) == null) {
            return 0;
        }
        return (second.length() - first.length()) + strokes(second) - strokes(first);
    }

    private static int strokes(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0142' || c == '\u0141') {
                count++;
            }
        }
        return count;
    }

    private static boolean stroke(char from, char to) {
        return (from == 't' && to == '\u0142')
                || (from == 'T' && to == '\u0141')
                || (from == 'l' && to == '\u0142')
                || (from == 'L' && to == '\u0141');
    }
}
