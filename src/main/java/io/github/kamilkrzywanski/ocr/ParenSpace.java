package io.github.kamilkrzywanski.ocr;

/**
 * Drops one space between an opening parenthesis and a single letter that the
 * parenthesis closes on. {@code Wystawił( a):} and {@code Odebrał(a):} are the
 * same print on the invoice; only the first read keeps the space. A wider gap,
 * such as {@code ( a )}, stays.
 */
final class ParenSpace {
    private ParenSpace() {
    }

    static String apply(String line) {
        if (line == null || line.length() < 4 || line.indexOf('(') < 0) {
            return line;
        }
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '('
                    && i + 3 < line.length()
                    && line.charAt(i + 1) == ' '
                    && Character.isLetter(line.charAt(i + 2))
                    && line.charAt(i + 3) == ')') {
                out.append('(').append(line.charAt(i + 2)).append(')');
                i += 3;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
