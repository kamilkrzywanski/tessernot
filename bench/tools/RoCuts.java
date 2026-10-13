import io.github.kamilkrzywanski.ocr.TextLine;
import io.github.kamilkrzywanski.ocr.internal.post.ReadingOrder;
import java.nio.file.*;
import java.util.*;
/** RoCuts <tsv-dir>: lines ReadingOrder.cuts would split, per page. */
public class RoCuts {
    public static void main(String[] args) throws Exception {
        try (var s = Files.list(Path.of(args[0]))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".tsv")).sorted().toList()) {
                List<TextLine> lines = new ArrayList<>();
                for (String row : Files.readAllLines(f)) {
                    String[] p = row.split("\t", 9);
                    float[] q = new float[8];
                    for (int i = 0; i < 8; i++) q[i] = Float.parseFloat(p[i]);
                    lines.add(new TextLine(p.length > 8 ? p[8] : "", 1f, 1f, q, null));
                }
                Map<TextLine, Float> cuts = ReadingOrder.cuts(lines);
                for (var e : cuts.entrySet()) {
                    String t = e.getKey().text();
                    int at = Math.round(e.getValue() * t.length());
                    System.out.printf(Locale.ROOT, "%s  %.2f  %s | %s%n", f.getFileName(), e.getValue(), t.substring(0, at), t.substring(at));
                }
            }
        }
    }
}
