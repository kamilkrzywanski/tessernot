import io.github.kamilkrzywanski.ocr.TextLine;
import io.github.kamilkrzywanski.ocr.internal.eval.Metrics;
import io.github.kamilkrzywanski.ocr.internal.post.Fragments;
import io.github.kamilkrzywanski.ocr.internal.post.ReadingOrder;

import java.nio.file.*;
import java.util.*;

/** RoBench <tsv-dir> <ref-dir>... : CER of the reading order on cached lines, per document and pooled per ref dir.
 * -Drows=true reads row by row (Layout.ROWS); -Dkeep=true keeps the fragments; -Ddump=dir writes the texts;
 * -Donly=part picks documents. */
public class RoBench {
    public static void main(String[] args) throws Exception {
        Path tsv = Path.of(args[0]);
        boolean check = Boolean.getBoolean("check");
        String only = System.getProperty("only");
        for (int a = 1; a < args.length; a++) {
            Path refDir = Path.of(args[a]);
            List<Path> refs = new ArrayList<>();
            try (var s = Files.list(refDir)) { s.filter(p -> p.toString().endsWith(".txt")).sorted().forEach(refs::add); }
            long edits = 0, chars = 0;
            for (Path ref : refs) {
                String name = ref.getFileName().toString().replace(".txt", "");
                if (only != null && !name.contains(only)) continue;
                Path f = tsv.resolve(name + ".tsv");
                if (!Files.exists(f)) { System.out.println("missing " + name); continue; }
                List<TextLine> lines = new ArrayList<>();
                Path scoreFile = tsv.resolve(name + ".score");
                List<String> scores = Files.exists(scoreFile) ? Files.readAllLines(scoreFile) : List.of();
                int at = 0;
                for (String row : Files.readAllLines(f)) {
                    String[] p = row.split("\t", 9);
                    float[] q = new float[8];
                    for (int i = 0; i < 8; i++) q[i] = Float.parseFloat(p[i]);
                    float score = at < scores.size() ? Float.parseFloat(scores.get(at)) : 1f;
                    at++;
                    lines.add(new TextLine(p.length > 8 ? p[8] : "", score, 1f, q, null));
                }
                // As the engine does: the order is found with the fragments, then they are left out.
                List<List<TextLine>> rows = new ArrayList<>();
                for (List<TextLine> row : ReadingOrder.group(lines, !Boolean.getBoolean("rows"))) {
                    List<TextLine> kept = new ArrayList<>(row);
                    if (!Boolean.getBoolean("keep")) kept.removeIf(Fragments::is);
                    if (!kept.isEmpty()) rows.add(kept);
                }
                String text = ReadingOrder.join(rows);
                if (check) {
                    // The cached text is the engine's with its fragments kept, as the cache is.
                    String old = Files.readString(tsv.resolve(name + ".txt"));
                    String kept = ReadingOrder.join(ReadingOrder.group(lines, !Boolean.getBoolean("rows")));
                    if (!old.equals(kept)) System.out.println("DIFFERS from engine: " + name);
                }
                if (System.getProperty("dump") != null) Files.writeString(Path.of(System.getProperty("dump"), name + ".txt"), text);
                String r = Metrics.normalize(Files.readString(ref));
                String h = Metrics.normalize(text);
                int e = Metrics.levenshtein(h, r);
                edits += e; chars += r.length();
                System.out.printf(Locale.ROOT, "%-42s %6d %.3f%n", name, r.length(), e / (double) r.length());
            }
            System.out.printf(Locale.ROOT, "== %s pooled %.4f (%d chars)%n", refDir.getFileName(), edits / (double) chars, chars);
        }
    }
}
