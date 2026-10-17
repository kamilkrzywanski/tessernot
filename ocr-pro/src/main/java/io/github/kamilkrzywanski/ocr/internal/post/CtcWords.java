package io.github.kamilkrzywanski.ocr.internal.post;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lexicon search on the CTC output. A greedy word that the vocabulary does not list is searched again on its
 * own timesteps, with every prefix kept inside the word lists. The best listed word replaces the
 * greedy one when its CTC probability is at most {@code margin} nats below the greedy word's on the
 * same timesteps. The recognizer has to see the listed spelling as well: a word the image does not
 * support stays as read, and no fixed table of letter swaps is involved.
 * <p>
 * The recognizer often misses a mark at its input height of 48 px, mostly on capitals: it may give
 * {@code ą} almost nothing where it reads {@code a}. So a letter with marks is never less probable
 * here than {@link #MARK} times its plain letter (see {@link Charset#plain()}). With the default
 * margin that pays for one unseen mark per word, in any language with a Latin-like script, and the
 * word still has to be listed. It only adds a mark: a mark the recognizer did read ({@code Vía}) is
 * not taken away or swapped this way. A line with no mark at all gets no unseen marks: a fiscal
 * printer or a telegram that cannot print them keeps {@code SPRZEDAZ}.
 * <p>
 * The search changes letters, not their number. A CTC path drops a letter almost for free by
 * reading its timesteps as blank, so a shorter listed word would win on probability too easily.
 * <p>
 * Letters glued to a digit ({@code 8vo}, {@code 10kg}) are a unit or a size, not a word, and stay.
 * A word followed by a hyphen may be the first half of a split word, so a listed prefix counts as
 * known there and may be the replacement.
 */
final class CtcWords {
    /** Timestep candidates below this probability are not searched. */
    private static final float FLOOR = 1e-4f;
    /** Letters tried per timestep, besides the blank and the last letter of the prefix. */
    private static final int TOP = 6;
    private static final int BEAM = 16;
    private static final int MIN_LETTERS = 2;
    private static final int MAX_LETTERS = 24;
    private static final double LOG_FLOOR = Math.log(1e-12);
    /** {@code -Docr.ctcwords.trace=true} prints every searched word, its best listed spelling and their gap in nats. */
    private static final boolean TRACE = Boolean.getBoolean("ocr.ctcwords.trace");
    /** e^-3: an unseen mark costs 3 nats against the margin. */
    static final float MARK = (float) Math.exp(-3.0);

    private CtcWords() {
    }

    /** One emitted label and the timesteps it spans in the greedy path. */
    private record Emission(int label, int first, int last) {
    }

    private record Beam(int[] labels, double blank, double label) {
        double total() {
            return logAdd(blank, label);
        }
    }

    /**
     * The text of {@code path} with unknown words replaced. {@code probs} is {@code [time, classes]}
     * after the softmax, {@code path} the label chosen at each timestep with 0 for blank.
     */
    static String decode(float[] probs, int time, int classes, Charset charset, int[] path,
                         Vocabulary words, double margin) {
        List<Emission> emissions = emissions(path, time);
        int[] plain = charset.plain();
        boolean marks = false;
        for (Emission e : emissions) {
            marks |= plain[e.label()] > 0;
        }
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < emissions.size()) {
            if (!letter(charset, emissions.get(i).label())) {
                text.append(charset.at(emissions.get(i).label()));
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < emissions.size() && letter(charset, emissions.get(j + 1).label())) {
                j++;
            }
            int[] labels = new int[j - i + 1];
            StringBuilder word = new StringBuilder();
            for (int k = i; k <= j; k++) {
                labels[k - i] = emissions.get(k).label();
                word.append(charset.at(labels[k - i]));
            }
            boolean hyphen = j + 1 < emissions.size() && "-".equals(charset.at(emissions.get(j + 1).label()));
            boolean glued = i > 0 && digit(charset, emissions.get(i - 1).label())
                    || j + 1 < emissions.size() && digit(charset, emissions.get(j + 1).label());
            int from = i == 0 ? 0 : emissions.get(i - 1).last() + 1;
            int to = j + 1 < emissions.size() ? emissions.get(j + 1).first() - 1 : time - 1;
            text.append(glued ? word
                    : repair(probs, classes, charset, labels, word.toString(), from, to, hyphen, marks, words, margin));
            i = j + 1;
        }
        return text.toString();
    }

    private static String repair(float[] probs, int classes, Charset charset, int[] labels, String word,
                                 int from, int to, boolean hyphen, boolean marks, Vocabulary words,
                                 double margin) {
        if (word.length() < MIN_LETTERS || word.length() > MAX_LETTERS) {
            return word;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        if (words.word(lower) || hyphen && words.prefix(lower)) {
            return word;
        }
        float[] seen = marked(probs, classes, from, to, charset.plain(), marks ? MARK : 0f);
        int steps = to - from;
        int[] best = search(probs, seen, classes, charset, from, to, labels.length, hyphen, words, charset.marked());
        if (best == null) {
            return word;
        }
        double greedy = logProb(seen, classes, labels, 0, steps);
        double listed = logProb(seen, classes, best, 0, steps);
        String fixed = text(charset, best);
        if (TRACE) {
            System.err.printf(Locale.ROOT, "CTCWORDS\t%s\t%s\t%.3f%n", word, fixed, listed - greedy);
        }
        return listed >= greedy - margin ? fixed : word;
    }

    /**
     * Timesteps {@code from..to} with every letter with marks raised to at least {@code mark} times
     * its plain letter. Rows are not renormalized: the result only compares spellings.
     */
    private static float[] marked(float[] probs, int classes, int from, int to, int[] plain, float mark) {
        int steps = Math.max(0, to - from + 1);
        float[] out = new float[steps * classes];
        System.arraycopy(probs, from * classes, out, 0, out.length);
        for (int t = 0; t < steps && mark > 0f; t++) {
            int base = t * classes;
            for (int c = 1; c < classes; c++) {
                if (plain[c] > 0) {
                    out[base + c] = Math.max(out[base + c], mark * probs[(from + t) * classes + plain[c]]);
                }
            }
        }
        return out;
    }

    /**
     * The best labeling of {@code letters} letters whose every prefix is listed and which is a listed word, or null.
     * Letters come from the recognizer's own top choices and those letters with marks; {@code seen} scores them.
     */
    private static int[] search(float[] probs, float[] seen, int classes, Charset charset, int from, int to,
                                int letters, boolean hyphen, Vocabulary words, int[][] marks) {
        List<Beam> beams = new ArrayList<>();
        beams.add(new Beam(new int[0], 0.0, Double.NEGATIVE_INFINITY));
        Map<String, Boolean> prefixes = new HashMap<>();
        for (int t = from; t <= to; t++) {
            int[] top = withMarks(top(probs, t * classes, classes, charset), marks);
            int base = (t - from) * classes;
            double blankP = log(seen[base]);
            Map<String, double[]> next = new HashMap<>();
            Map<String, int[]> keys = new HashMap<>();
            for (Beam beam : beams) {
                int[] labels = beam.labels();
                double total = beam.total();
                add(next, keys, labels, total + blankP, Double.NEGATIVE_INFINITY);
                int last = labels.length == 0 ? -1 : labels[labels.length - 1];
                if (last > 0) {
                    add(next, keys, labels, Double.NEGATIVE_INFINITY, beam.label() + log(seen[base + last]));
                }
                for (int c : top) {
                    double p = log(seen[base + c]);
                    if (c == last) {
                        // The same letter twice needs a blank between the two.
                        int[] longer = append(labels, c);
                        if (longer.length <= letters && listed(prefixes, charset, longer, words)) {
                            add(next, keys, longer, Double.NEGATIVE_INFINITY, beam.blank() + p);
                        }
                        continue;
                    }
                    int[] longer = append(labels, c);
                    if (longer.length <= letters && listed(prefixes, charset, longer, words)) {
                        add(next, keys, longer, Double.NEGATIVE_INFINITY, total + p);
                    }
                }
            }
            List<Beam> kept = new ArrayList<>(next.size());
            for (Map.Entry<String, double[]> e : next.entrySet()) {
                double[] v = e.getValue();
                kept.add(new Beam(keys.get(e.getKey()), v[0], v[1]));
            }
            kept.sort((a, b) -> Double.compare(b.total(), a.total()));
            beams = kept.size() > BEAM ? new ArrayList<>(kept.subList(0, BEAM)) : kept;
        }
        for (Beam beam : beams) {
            int[] labels = beam.labels();
            if (labels.length != letters) {
                continue;
            }
            String lower = text(charset, labels).toLowerCase(Locale.ROOT);
            if (words.word(lower) || hyphen && words.prefix(lower)) {
                return labels;
            }
        }
        return null;
    }

    private static boolean listed(Map<String, Boolean> cache, Charset charset, int[] labels, Vocabulary words) {
        String lower = text(charset, labels).toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(lower, words::prefix);
    }

    private static void add(Map<String, double[]> next, Map<String, int[]> keys, int[] labels,
                            double blank, double label) {
        String key = key(labels);
        double[] v = next.get(key);
        if (v == null) {
            next.put(key, new double[] {blank, label});
            keys.put(key, labels);
            return;
        }
        v[0] = logAdd(v[0], blank);
        v[1] = logAdd(v[1], label);
    }

    private static int[] withMarks(int[] top, int[][] marks) {
        int n = top.length;
        for (int c : top) {
            n += marks[c] == null ? 0 : marks[c].length;
        }
        if (n == top.length) {
            return top;
        }
        java.util.LinkedHashSet<Integer> all = new java.util.LinkedHashSet<>();
        for (int c : top) {
            all.add(c);
        }
        for (int c : top) {
            if (marks[c] != null) {
                for (int o : marks[c]) {
                    all.add(o);
                }
            }
        }
        int[] out = new int[all.size()];
        int i = 0;
        for (int c : all) {
            out[i++] = c;
        }
        return out;
    }

    /** Letter labels above {@link #FLOOR}, at most {@link #TOP}, most probable first. */
    private static int[] top(float[] probs, int base, int classes, Charset charset) {
        int[] best = new int[TOP];
        float[] value = new float[TOP];
        int n = 0;
        for (int c = 1; c < classes; c++) {
            float v = probs[base + c];
            if (v < FLOOR || n == TOP && v <= value[n - 1] || !letter(charset, c)) {
                continue;
            }
            int at = n < TOP ? n++ : TOP - 1;
            while (at > 0 && value[at - 1] < v) {
                best[at] = best[at - 1];
                value[at] = value[at - 1];
                at--;
            }
            best[at] = c;
            value[at] = v;
        }
        int[] out = new int[n];
        System.arraycopy(best, 0, out, 0, n);
        return out;
    }

    /** Log of the CTC probability of {@code labels} over timesteps {@code from..to}. */
    static double logProb(float[] probs, int classes, int[] labels, int from, int to) {
        int states = labels.length * 2 + 1;
        double[] alpha = new double[states];
        double[] next = new double[states];
        java.util.Arrays.fill(alpha, Double.NEGATIVE_INFINITY);
        if (to < from) {
            return labels.length == 0 ? 0.0 : Double.NEGATIVE_INFINITY;
        }
        int base = from * classes;
        alpha[0] = log(probs[base]);
        if (labels.length > 0) {
            alpha[1] = log(probs[base + labels[0]]);
        }
        for (int t = from + 1; t <= to; t++) {
            base = t * classes;
            for (int s = 0; s < states; s++) {
                double v = alpha[s];
                if (s > 0) {
                    v = logAdd(v, alpha[s - 1]);
                }
                int label = s % 2 == 0 ? 0 : labels[s / 2];
                if (s > 1 && label != 0 && label != labels[s / 2 - 1]) {
                    v = logAdd(v, alpha[s - 2]);
                }
                next[s] = v + log(probs[base + label]);
            }
            double[] swap = alpha;
            alpha = next;
            next = swap;
        }
        return labels.length == 0 ? alpha[0] : logAdd(alpha[states - 1], alpha[states - 2]);
    }

    private static List<Emission> emissions(int[] path, int time) {
        List<Emission> out = new ArrayList<>();
        int previous = 0;
        for (int t = 0; t < time; t++) {
            int label = path[t];
            if (label != 0 && label == previous) {
                Emission e = out.get(out.size() - 1);
                out.set(out.size() - 1, new Emission(label, e.first(), t));
            } else if (label != 0) {
                out.add(new Emission(label, t, t));
            }
            previous = label;
        }
        return out;
    }

    private static boolean letter(Charset charset, int label) {
        String s = charset.at(label);
        return s.length() == 1 && Character.isLetter(s.charAt(0));
    }

    private static boolean digit(Charset charset, int label) {
        String s = charset.at(label);
        return s.length() == 1 && Character.isDigit(s.charAt(0));
    }

    private static String text(Charset charset, int[] labels) {
        StringBuilder out = new StringBuilder(labels.length);
        for (int label : labels) {
            out.append(charset.at(label));
        }
        return out.toString();
    }

    private static int[] append(int[] labels, int label) {
        int[] out = java.util.Arrays.copyOf(labels, labels.length + 1);
        out[labels.length] = label;
        return out;
    }

    private static String key(int[] labels) {
        StringBuilder out = new StringBuilder(labels.length * 3);
        for (int label : labels) {
            out.append((char) label);
        }
        return out.toString();
    }

    private static double log(float p) {
        return p <= 0f ? LOG_FLOOR : Math.max(LOG_FLOOR, Math.log(p));
    }

    private static double logAdd(double a, double b) {
        if (a == Double.NEGATIVE_INFINITY) {
            return b;
        }
        if (b == Double.NEGATIVE_INFINITY) {
            return a;
        }
        double max = Math.max(a, b);
        return max + Math.log1p(Math.exp(-Math.abs(a - b)));
    }
}
