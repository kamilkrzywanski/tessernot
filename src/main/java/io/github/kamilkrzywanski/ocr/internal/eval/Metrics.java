package io.github.kamilkrzywanski.ocr.internal.eval;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/** Character error rate via Levenshtein distance, and an order-free word match for the benchmark. */
public final class Metrics {
    private Metrics() {
    }

    public static int levenshtein(String a, String b) {
        if (a == null) {
            a = "";
        }
        if (b == null) {
            b = "";
        }
        int n = a.length();
        int m = b.length();
        if (n == 0) {
            return m;
        }
        if (m == 0) {
            return n;
        }
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[m];
    }

    /** Insertions + deletions + substitutions divided by the reference length. Empty reference yields 0 or 1. */
    public static double cer(String hypothesis, String reference) {
        if (reference == null || reference.isEmpty()) {
            return hypothesis == null || hypothesis.isEmpty() ? 0.0 : 1.0;
        }
        return levenshtein(hypothesis, reference) / (double) reference.length();
    }

    /**
     * Benchmark form of a page: NFC, typographic quotes and dashes folded to ASCII,
     * every run of whitespace (line breaks included) as one space, trimmed.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(nfc.length());
        boolean space = false;
        for (int i = 0; i < nfc.length(); i++) {
            char c = nfc.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = out.length() > 0;
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.append(switch (c) {
                case '\u201C', '\u201D', '\u201E', '\u201F', '\u00AB', '\u00BB', '\u2033' -> '"';
                case '\u2018', '\u2019', '\u201A', '\u201B', '\u2032', '`' -> '\'';
                case '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-';
                default -> c;
            });
        }
        return out.toString();
    }

    /**
     * Words of {@code reference} that also appear in {@code hypothesis}, counted as multisets
     * after {@link #normalize}. Reading order does not matter. Returns {matched, hypothesis words, reference words}.
     */
    public static int[] wordMatches(String hypothesis, String reference) {
        Map<String, Integer> pool = new HashMap<>();
        int hypWords = 0;
        for (String word : words(hypothesis)) {
            pool.merge(word, 1, Integer::sum);
            hypWords++;
        }
        int matched = 0;
        int refWords = 0;
        for (String word : words(reference)) {
            refWords++;
            Integer left = pool.get(word);
            if (left != null && left > 0) {
                pool.put(word, left - 1);
                matched++;
            }
        }
        return new int[]{matched, hypWords, refWords};
    }

    private static String[] words(String text) {
        String normalized = normalize(text);
        return normalized.isEmpty() ? new String[0] : normalized.split(" ");
    }
}
