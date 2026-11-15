import io.github.kamilkrzywanski.ocr.TextLine;
import io.github.kamilkrzywanski.ocr.internal.post.Fragments;
import io.github.kamilkrzywanski.ocr.internal.post.ReadingOrder;
import java.nio.file.*;
import java.util.*;
/** RoOrder <in.tsv> <out.tsv>: the lines in reading order, without the fragments the engine drops. */
public class RoOrder {
    public static void main(String[] args) throws Exception {
        List<TextLine> lines = new ArrayList<>();
        List<String> raw = Files.readAllLines(Path.of(args[0]));
        Map<TextLine, String> src = new IdentityHashMap<>();
        Path scoreFile = Path.of(args[0].replaceAll("\\.tsv$", ".score"));
        List<String> scores = Files.exists(scoreFile) ? Files.readAllLines(scoreFile) : List.of();
        for (String row : raw) {
            String[] p = row.split("\t", 9);
            float[] q = new float[8];
            for (int i = 0; i < 8; i++) q[i] = Float.parseFloat(p[i]);
            float score = lines.size() < scores.size() ? Float.parseFloat(scores.get(lines.size())) : 1f;
            TextLine t = new TextLine(p.length > 8 ? p[8] : "", score, 1f, q, null);
            lines.add(t); src.put(t, row);
        }
        StringBuilder out = new StringBuilder();
        // As the engine does: the order is found with the fragments, then they are left out.
        for (TextLine t : ReadingOrder.flatten(ReadingOrder.group(lines))) {
            if (!Fragments.is(t)) out.append(src.get(t)).append('\n');
        }
        Files.writeString(Path.of(args[1]), out.toString());
    }
}
