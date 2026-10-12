package org.krzywanski.ocr;

/** Character error rate via Levenshtein distance. */
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
}
