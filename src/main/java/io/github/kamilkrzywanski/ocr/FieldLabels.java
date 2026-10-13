package io.github.kamilkrzywanski.ocr;

/**
 * A short invoice label that is one edit from exactly one known label is replaced by it.
 * A line that already is a label, that contains a digit, or that is equally close to two
 * labels stays as read. The list is the usual Polish VAT field names, not a character map.
 */
final class FieldLabels {
    private static final String[] PHRASES = {
            "Miejsce wystawienia",
            "Data wystawienia",
            "Data sprzedaży",
            "Data dostawy",
            "Sprzedawca",
            "Nabywca",
            "Słownie",
            "Razem do zapłaty",
            "Zapłacono przelewem",
            "Forma płatności",
            "Sposób zapłaty",
            "Termin płatności",
            "Wystawił",
            "Odebrał"
    };

    private FieldLabels() {
    }

    static String apply(String line) {
        return apply(line, PHRASES);
    }

    static String apply(String line, String[] phrases) {
        if (line == null || line.length() < 4 || line.length() > 48 || hasDigit(line)) {
            return line;
        }
        boolean colon = line.charAt(line.length() - 1) == ':';
        String body = colon ? stripTrailing(line.substring(0, line.length() - 1)) : line;
        if (body.length() < 4) {
            return line;
        }
        String hit = null;
        for (String phrase : phrases) {
            int distance = distanceAtMostOne(body, phrase);
            if (distance == 0) {
                return line;
            }
            if (distance == 1) {
                if (hit != null) {
                    return line;
                }
                hit = phrase;
            }
        }
        if (hit == null) {
            return line;
        }
        return colon ? hit + ":" : hit;
    }

    private static boolean hasDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    /** 0, 1, or 2 when the distance is greater than 1. */
    private static int distanceAtMostOne(String left, String right) {
        int n = left.length();
        int m = right.length();
        if (Math.abs(n - m) > 1) {
            return 2;
        }
        int[] previous = new int[m + 1];
        int[] current = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            current[0] = i;
            int rowMin = current[0];
            char a = left.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = a == right.charAt(j - 1) ? 0 : 1;
                int insert = current[j - 1] + 1;
                int delete = previous[j] + 1;
                int replace = previous[j - 1] + cost;
                int best = insert < delete ? insert : delete;
                current[j] = best < replace ? best : replace;
                if (current[j] < rowMin) {
                    rowMin = current[j];
                }
            }
            if (rowMin > 1) {
                return 2;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[m] > 1 ? 2 : previous[m];
    }
}
