package io.github.kamilkrzywanski.ocr;

/**
 * A line that is only a dot, one letter and a dot is read again from a box
 * extended to the left and downward. The invoice header {@code j.m.} is boxed
 * as 42 by 30 and the first read is {@code .m.}. Sixteen pixels of the hook
 * and sixteen of the descender make the same model say {@code j.m.}.
 * A leading digit, which is what the cell rule becomes, is not kept.
 * Extending the top of that box reads {@code 1}, so the top stays put.
 */
final class DotKeep {
    static final float LEFT = 16f;
    static final float DOWN = 16f;

    private DotKeep() {
    }

    static boolean candidate(String text) {
        return text != null
                && text.length() == 3
                && text.charAt(0) == '.'
                && text.charAt(2) == '.'
                && Character.isLetter(text.charAt(1));
    }

    /**
     * @return the second line when it is {@code first} with one letter written
     *         in front of the opening dot. Otherwise null.
     */
    static String keep(String first, String second) {
        if (!candidate(first) || second == null || second.length() != first.length() + 1) {
            return null;
        }
        if (!second.endsWith(first) || !Character.isLetter(second.charAt(0))) {
            return null;
        }
        return second;
    }

    /** Axis-aligned quad grown left and down. The top and the right edge stay. */
    static float[] grow(float[] quad) {
        float minX = quad[0];
        float maxX = quad[0];
        float minY = quad[1];
        float maxY = quad[1];
        for (int i = 1; i < 4; i++) {
            minX = Math.min(minX, quad[i * 2]);
            maxX = Math.max(maxX, quad[i * 2]);
            minY = Math.min(minY, quad[i * 2 + 1]);
            maxY = Math.max(maxY, quad[i * 2 + 1]);
        }
        minX -= LEFT;
        maxY += DOWN;
        return new float[] {minX, minY, maxX, minY, maxX, maxY, minX, maxY};
    }
}
