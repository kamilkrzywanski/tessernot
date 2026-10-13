import io.github.kamilkrzywanski.ocr.TextLine;
import io.github.kamilkrzywanski.ocr.internal.post.ReadingOrder;
import java.nio.file.*;
import java.util.*;
/** RoOrder <in.tsv> <out.tsv>: the lines in reading order. */
public class RoOrder {
    public static void main(String[] args) throws Exception {
        List<TextLine> lines = new ArrayList<>();
        List<String> raw = Files.readAllLines(Path.of(args[0]));
        Map<TextLine, String> src = new IdentityHashMap<>();
        for (String row : raw) {
            String[] p = row.split("\t", 9);
            float[] q = new float[8];
            for (int i = 0; i < 8; i++) q[i] = Float.parseFloat(p[i]);
            TextLine t = new TextLine(p.length > 8 ? p[8] : "", 1f, 1f, q, null);
            lines.add(t); src.put(t, row);
        }
        StringBuilder out = new StringBuilder();
        for (TextLine t : ReadingOrder.flatten(ReadingOrder.group(lines))) out.append(src.get(t)).append('\n');
        Files.writeString(Path.of(args[1]), out.toString());
    }
}
