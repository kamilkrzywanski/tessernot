package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.post.Ctc;

import java.util.ArrayList;
import java.util.List;

/**
 * The boxes of the words of a line, cut out of the line's box where the recognizer read them.
 * <p>
 * A read gives, for every character, the share of the crop's width where it starts and ends
 * ({@link Ctc.Result}). The crop was cut along the quad of the box and may have been turned by
 * quarter turns before it was read, so the left edge of the crop is one of the four edges of the
 * quad. A word is the run of characters between two spaces, and its quad the strip of the box
 * between its first character's start and its last character's end, with the corners named as
 * the text sees them.
 */
final class Words {
    private Words() {
    }

    /**
     * @param result the read; no words when it has no positions
     * @param quad the box of the line on the page: top left, top right, bottom right, bottom left
     * @param turns quarter turns counter-clockwise the crop was given before the read
     */
    static List<TextWord> of(Ctc.Result result, float[] quad, int turns) {
        float[] from = result.from();
        float[] to = result.to();
        String text = result.text();
        if (from == null || to == null || from.length != text.length()) {
            return List.of();
        }
        // The corners of the box as the crop that was read has them: a quarter turn
        // counter-clockwise makes the old top right corner the new top left.
        float[] corners = new float[8];
        for (int i = 0; i < 4; i++) {
            int source = (i + turns) & 3;
            corners[i * 2] = quad[source * 2];
            corners[i * 2 + 1] = quad[source * 2 + 1];
        }
        List<TextWord> words = new ArrayList<>();
        float previousEnd = 0f;
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == ' ') {
                i++;
                continue;
            }
            int j = i;
            while (j < text.length() && text.charAt(j) != ' ') {
                j++;
            }
            // Words follow one another: one does not start before the last has ended.
            float start = Math.max(previousEnd, Math.min(1f, from[i]));
            float end = Math.max(start, Math.min(1f, to[j - 1]));
            previousEnd = end;
            words.add(new TextWord(text.substring(i, j), strip(corners, start, end)));
            i = j;
        }
        return words;
    }

    /** The part of a quad between two shares of its width: top left, top right, bottom right, bottom left. */
    private static float[] strip(float[] q, float start, float end) {
        return new float[] {
                q[0] + (q[2] - q[0]) * start, q[1] + (q[3] - q[1]) * start,
                q[0] + (q[2] - q[0]) * end, q[1] + (q[3] - q[1]) * end,
                q[6] + (q[4] - q[6]) * end, q[7] + (q[5] - q[7]) * end,
                q[6] + (q[4] - q[6]) * start, q[7] + (q[5] - q[7]) * start};
    }
}
