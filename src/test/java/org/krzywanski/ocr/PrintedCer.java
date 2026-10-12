package org.krzywanski.ocr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Character edits of a printed transcription against detected lines.
 * Each reference line is matched to its own hypothesis line. Extra lines,
 * such as a signature or a stamp, are left unmatched and do not add edits.
 * A reference with no line left pays its own length.
 */
final class PrintedCer {
    private static final int INF = 1_000_000;

    private PrintedCer() {
    }

    static Result score(List<String> hypothesis, List<String> reference) {
        List<String> lines = new ArrayList<>();
        if (hypothesis != null) {
            for (String line : hypothesis) {
                if (line != null && !line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        List<String> refs = reference == null ? List.of() : reference;
        int n = refs.size();
        int m = lines.size();
        int cols = m + n;
        int[][] cost = new int[Math.max(n, 1)][Math.max(cols, 1)];
        if (n == 0) {
            return new Result(0, 0, List.of());
        }
        for (int i = 0; i < n; i++) {
            String ref = refs.get(i);
            for (int j = 0; j < m; j++) {
                cost[i][j] = Metrics.levenshtein(lines.get(j), ref);
            }
            for (int j = 0; j < n; j++) {
                cost[i][m + j] = j == i ? ref.length() : INF;
            }
        }
        int[] column = assign(cost);
        int edits = 0;
        int chars = 0;
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String ref = refs.get(i);
            chars += ref.length();
            int chosen = column[i];
            int distance = cost[i][chosen];
            edits += distance;
            String hyp = chosen < m ? lines.get(chosen) : "";
            pairs.add(new Pair(ref, hyp, distance));
        }
        return new Result(edits, chars, pairs);
    }

    /** Column chosen for each row. {@code cost} has at least as many columns as rows. */
    static int[] assign(int[][] cost) {
        int n = cost.length;
        int m = cost[0].length;
        int[] u = new int[n + 1];
        int[] v = new int[m + 1];
        int[] p = new int[m + 1];
        int[] way = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            int[] minv = new int[m + 1];
            boolean[] used = new boolean[m + 1];
            Arrays.fill(minv, INF);
            do {
                used[j0] = true;
                int i0 = p[j0];
                int delta = INF;
                int j1 = 0;
                for (int j = 1; j <= m; j++) {
                    if (used[j]) {
                        continue;
                    }
                    int cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if (minv[j] < delta) {
                        delta = minv[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] column = new int[n];
        Arrays.fill(column, -1);
        for (int j = 1; j <= m; j++) {
            if (p[j] != 0) {
                column[p[j] - 1] = j - 1;
            }
        }
        return column;
    }

    record Pair(String reference, String hypothesis, int edits) {
    }

    record Result(int edits, int chars, List<Pair> pairs) {
        String detail(String name) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("quality %s edits %d / %d%n", name, edits, chars));
            for (Pair pair : pairs) {
                if (pair.edits == 0) {
                    continue;
                }
                sb.append("  ").append(pair.edits).append(" [").append(pair.reference).append("] ~ [")
                        .append(pair.hypothesis).append("]\n");
            }
            return sb.toString();
        }
    }
}
