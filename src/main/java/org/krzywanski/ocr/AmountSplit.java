package org.krzywanski.ocr;

/**
 * One box sometimes holds a money amount and the VAT rate from the next cell.
 * {@code 8.13 23} is that pair on the invoice. The box is split only when a
 * vertical rule sits between them and each side reads back as the same token.
 */
final class AmountSplit {
    private AmountSplit() {
    }

    /** The two tokens, or null when the line is not an amount plus a short rate. */
    static String[] parts(String text) {
        if (text == null) {
            return null;
        }
        int space = text.indexOf(' ');
        if (space <= 0 || space != text.lastIndexOf(' ')) {
            return null;
        }
        String left = text.substring(0, space);
        String right = text.substring(space + 1);
        if (!money(left) || !rate(right)) {
            return null;
        }
        return new String[] {left, right};
    }

    private static boolean money(String token) {
        int sep = -1;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '.' || c == ',') {
                if (sep >= 0) {
                    return false;
                }
                sep = i;
                continue;
            }
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return sep >= 1 && sep <= 6 && token.length() - sep == 3;
    }

    private static boolean rate(String token) {
        if (token.length() < 1 || token.length() > 2) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
