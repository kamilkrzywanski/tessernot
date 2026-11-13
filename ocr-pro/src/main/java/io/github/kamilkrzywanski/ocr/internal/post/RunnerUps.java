package io.github.kamilkrzywanski.ocr.internal.post;

import java.util.ArrayList;
import java.util.List;

/**
 * Runner-up classes that win over the greedy CTC choice: the rules of one correction profile, for
 * one charset. The decoder itself knows no language; it asks this table.
 * <p>
 * A rule names the class that won a run of timesteps, the class that came second, and the least
 * probability of that second class. It is decided once for the whole run of the winner and holds
 * when any timestep of the run meets it, so a letter that spans two timesteps is still one
 * letter. Deciding per timestep would read such a letter as two.
 */
public final class RunnerUps {
    private final int[] from;
    private final int[] to;
    private final float[] min;
    private final boolean[] afterDigit;
    /** The space class, or -1 when the profile has no rule for a space after a colon. */
    private final int space;
    private final float colonSpace;

    private RunnerUps(List<int[]> pairs, List<Float> bars, List<Boolean> digits, int space, float colonSpace) {
        this.from = new int[pairs.size()];
        this.to = new int[pairs.size()];
        this.min = new float[pairs.size()];
        this.afterDigit = new boolean[pairs.size()];
        for (int i = 0; i < from.length; i++) {
            from[i] = pairs.get(i)[0];
            to[i] = pairs.get(i)[1];
            min[i] = bars.get(i);
            afterDigit[i] = digits.get(i);
        }
        this.space = space;
        this.colonSpace = colonSpace;
    }

    /**
     * The rules of {@code Corrections.POLISH}, measured on the three printed scans. Each swap hits
     * only a real miss there, and a lower bar does not.
     * <ul>
     * <li>× on the 4,99 row is 0.36 for x.</li>
     * <li>l in zapłaty is 0.35 for ł. t in Wrocław, łącznie and Zapłacono is at least 0.16 for ł;
     * a bar of 0.10 for t adds nothing on these scans.</li>
     * <li>The period in 90.00 is 0.35 for a comma. The comma needs a digit before it: on the dev
     * set it turned an English sentence end into a comma.</li>
     * <li>ó in dziewięćdziesiąt is 0.17 for ć.</li>
     * <li>Capital S in Świat is 0.023 for Ś. The next capital S on these scans is 0.0023.</li>
     * <li>o in Spółka is 0.061 for ó. No other o on these scans reaches 0.04.</li>
     * <li>Both NIP lines on the scanned invoice drop the space after the colon. Blank wins there,
     * and the space is the runner-up at 0.35 or more. The same timestep on Nixon and on the
     * receipt stays under that.</li>
     * </ul>
     * A character the charset lacks leaves its rule out.
     */
    public static RunnerUps polish(Charset charset) {
        Builder rules = new Builder(charset);
        rules.add("×", "x", 0.35f, false);
        rules.add("l", "ł", 0.34f, false);
        rules.add("L", "Ł", 0.34f, false);
        rules.add("t", "ł", 0.15f, false);
        rules.add("T", "Ł", 0.15f, false);
        rules.add(".", ",", 0.34f, true);
        rules.add("ó", "ć", 0.15f, false);
        rules.add("S", "Ś", 0.015f, false);
        rules.add("o", "ó", 0.05f, false);
        return rules.build(charset.indexOf(" "), 0.35f);
    }

    /**
     * The class a run of {@code winner} reads as when {@code second} came second with
     * {@code probability} on one of its timesteps, or -1 when the winner stays.
     * {@code digitBefore} tells whether the text so far ends in a digit.
     */
    int swap(int winner, int second, float probability, boolean digitBefore) {
        for (int i = 0; i < from.length; i++) {
            if (from[i] == winner && to[i] == second && probability >= min[i] && (!afterDigit[i] || digitBefore)) {
                return to[i];
            }
        }
        return -1;
    }

    /**
     * The space class when a blank timestep right after a colon reads as a space: the space came
     * second with {@code probability} at or over the bar. Otherwise -1.
     */
    int spaceAfterColon(int second, float probability) {
        return space >= 0 && second == space && probability >= colonSpace ? space : -1;
    }

    private static final class Builder {
        private final Charset charset;
        private final List<int[]> pairs = new ArrayList<>();
        private final List<Float> bars = new ArrayList<>();
        private final List<Boolean> digits = new ArrayList<>();

        Builder(Charset charset) {
            this.charset = charset;
        }

        void add(String winner, String runnerUp, float min, boolean afterDigit) {
            int from = charset.indexOf(winner);
            int to = charset.indexOf(runnerUp);
            if (from < 0 || to < 0) {
                return;
            }
            pairs.add(new int[]{from, to});
            bars.add(min);
            digits.add(afterDigit);
        }

        RunnerUps build(int space, float colonSpace) {
            return new RunnerUps(pairs, bars, digits, space, colonSpace);
        }
    }
}
