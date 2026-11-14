import io.github.kamilkrzywanski.ocr.TextLine;
import io.github.kamilkrzywanski.ocr.internal.eval.Metrics;
import io.github.kamilkrzywanski.ocr.internal.post.ReadingOrder;

import java.nio.file.*;
import java.util.*;

/**
 * RoJitter <tsv-dir> <ref-dir>... : how much the reading order depends on where exactly the detector drew
 * each box. Every cached page is ordered again -Dtrials=40 times with its box edges moved at random, the way
 * two reads of one page differ: every edge by up to 0.04 of the box height along the line and 0.05 across
 * it, and one end in fifty by up to half a height. (Measured on a color and a gray read of the same page:
 * 90 % of the edges within 0.04, the far tail at 0.4 to 0.7.) The seed is the page name, so a run repeats.
 * Per page: edits as cached, mean and worst over the trials, and how many trials changed the order's score.
 * A page whose worst is far above its base hangs on a threshold. -Donly=part picks documents.
 */
public class RoJitter {
    public static void main(String[] args) throws Exception {
        Path tsv = Path.of(args[0]);
        int trials = Integer.getInteger("trials", 40);
        String only = System.getProperty("only");
        System.out.printf("%-42s %6s %6s %8s %6s %s%n", "page", "chars", "base", "mean", "worst", "changed/" + trials);
        for (int a = 1; a < args.length; a++) {
            Path refDir = Path.of(args[a]);
            List<Path> refs = new ArrayList<>();
            try (var s = Files.list(refDir)) { s.filter(p -> p.toString().endsWith(".txt")).sorted().forEach(refs::add); }
            long base = 0, worst = 0;
            double mean = 0;
            for (Path ref : refs) {
                String name = ref.getFileName().toString().replace(".txt", "");
                if (only != null && !name.contains(only)) continue;
                Path f = tsv.resolve(name + ".tsv");
                if (!Files.exists(f)) { System.out.println("missing " + name); continue; }
                List<float[]> quads = new ArrayList<>();
                List<String> texts = new ArrayList<>();
                for (String row : Files.readAllLines(f)) {
                    String[] p = row.split("\t", 9);
                    float[] q = new float[8];
                    for (int i = 0; i < 8; i++) q[i] = Float.parseFloat(p[i]);
                    quads.add(q);
                    texts.add(p.length > 8 ? p[8] : "");
                }
                String reference = Metrics.normalize(Files.readString(ref));
                int asCached = edits(quads, texts, reference, null);
                Random random = new Random(name.hashCode());
                long sum = 0;
                int max = asCached, changed = 0;
                for (int t = 0; t < trials; t++) {
                    int e = edits(quads, texts, reference, random);
                    sum += e;
                    max = Math.max(max, e);
                    if (e != asCached) changed++;
                }
                base += asCached; worst += max; mean += sum / (double) trials;
                System.out.printf(Locale.ROOT, "%-42s %6d %6d %8.1f %6d %d%n", name, reference.length(), asCached, sum / (double) trials, max, changed);
            }
            System.out.printf(Locale.ROOT, "== %s edits %d as cached, %.0f mean, %d worst%n", refDir.getFileName(), base, mean, worst);
        }
    }

    private static int edits(List<float[]> quads, List<String> texts, String reference, Random random) {
        List<TextLine> lines = new ArrayList<>(quads.size());
        for (int i = 0; i < quads.size(); i++) {
            float[] q = quads.get(i).clone();
            if (random != null) {
                float h = (float) Math.hypot(q[6] - q[0], q[7] - q[1]);
                float left = (random.nextFloat() * 2 - 1) * 0.04f * h;
                float right = (random.nextFloat() * 2 - 1) * 0.04f * h;
                if (random.nextFloat() < 0.02f) left += (random.nextFloat() * 2 - 1) * 0.5f * h;
                if (random.nextFloat() < 0.02f) right += (random.nextFloat() * 2 - 1) * 0.5f * h;
                float top = (random.nextFloat() * 2 - 1) * 0.05f * h;
                float bottom = (random.nextFloat() * 2 - 1) * 0.05f * h;
                q[0] += left; q[6] += left; q[2] += right; q[4] += right;
                q[1] += top; q[3] += top; q[5] += bottom; q[7] += bottom;
            }
            lines.add(new TextLine(texts.get(i), 1f, 1f, q, null));
        }
        return Metrics.levenshtein(Metrics.normalize(ReadingOrder.join(ReadingOrder.group(lines))), reference);
    }
}
