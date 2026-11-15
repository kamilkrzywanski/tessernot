import io.github.kamilkrzywanski.ocr.*;
import io.github.kamilkrzywanski.ocr.internal.eval.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.stream.*;

/**
 * Probe &lt;root&gt; &lt;out.tsv&gt; [--only name,name] [--limit N] [--dump dir] &lt;variant&gt;...
 * <p>
 * Reads every page of {@code dev} and {@code test}, and of no other set, once for each variant:
 * the page changed in a way that should not matter, or as a camera would change it. Writes the
 * edits against the page's reference, one row per page and one column per variant, to the TSV
 * as it goes, and the total at the end. Run it through {@code bench/tools/probe.sh}.
 * <p>
 * Variants (N is a number):
 * <ul>
 * <li>{@code none}: the page as given
 * <li>{@code rot90}, {@code rot180}, {@code rot270}: turned clockwise
 * <li>{@code skewN}: askew by N degrees, on a white canvas ({@code skew-3} turns the other way)
 * <li>{@code half}: at half the resolution (block mean); {@code halfup}: that, enlarged back
 * <li>{@code jpegN}: saved as a JPEG of quality N percent
 * <li>{@code persxN}: photographed from the side in true perspective, the right edge N percent
 *     shorter: the lines run to one point and the page is narrower towards that edge
 * <li>{@code keyxN}: the right edge N percent shorter without the narrowing; {@code keyyN}: the
 *     top edge N percent shorter, lines level and margins leaning in
 * <li>{@code blurN}: out of focus, a Gaussian of N tenths of a pixel
 * <li>{@code shadeN}: lit unevenly, N percent of the light at the bottom right, with the shadow
 *     of an edge across the lower third
 * </ul>
 * {@code --dump dir} also saves each changed picture there as a JPEG.
 */
public class Probe {
    public static void main(String[] a) throws Exception {
        Path root = Path.of(a[0]);
        Path out = Path.of(a[1]);
        OcrConfig.Builder b = OcrConfig.defaults(root.resolve("models")).toBuilder();
        List<String> variants = new ArrayList<>();
        Set<String> only = null;
        int limit = Integer.MAX_VALUE;
        Path dump = null;
        for (int i = 2; i < a.length; i++) {
            switch (a[i]) {
                case "--dump" -> dump = Path.of(a[++i]);
                case "--limit" -> limit = Integer.parseInt(a[++i]);
                case "--only" -> only = new HashSet<>(Arrays.asList(a[++i].split(",")));
                default -> variants.add(a[i]);
            }
        }
        long[] total = new long[variants.size()];
        long chars = 0;
        StringBuilder log = new StringBuilder("set\tname\tchars\t" + String.join("\t", variants) + "\n");
        try (OcrEngine engine = OcrEngine.create(b.build())) {
            for (String set : new String[] {"dev", "test"}) {
                List<Path> refs;
                try (Stream<Path> s = Files.list(root.resolve("bench/ref/" + set))) {
                    refs = s.filter(p -> p.toString().endsWith(".txt")).sorted().collect(Collectors.toList());
                }
                int done = 0;
                for (Path ref : refs) {
                    String name = ref.getFileName().toString().replaceAll("\\.txt$", "");
                    if (only != null && !only.contains(name)) continue;
                    if (done++ >= limit) break;
                    Path file = null;
                    for (String ext : new String[] {"png", "jpg", "jpeg", "tif", "tiff"}) {
                        Path p = root.resolve("bench/data/" + name + "." + ext);
                        if (Files.isRegularFile(p)) { file = p; break; }
                    }
                    BufferedImage im = OcrImages.read(file);
                    String reference = Metrics.normalize(Files.readString(ref));
                    StringBuilder row = new StringBuilder(set + "\t" + name + "\t" + reference.length());
                    for (int v = 0; v < variants.size(); v++) {
                        BufferedImage shown = variant(im, variants.get(v));
                        if (dump != null) {
                            ImageIO.write(rgb(shown), "jpg", dump.resolve(name + "." + variants.get(v) + ".jpg").toFile());
                        }
                        OcrResult r = engine.recognize(shown);
                        if (r.rotation() != 0) System.out.println("  TURNED " + name + " " + variants.get(v) + " rotation=" + r.rotation());
                        int e = Metrics.levenshtein(Metrics.normalize(r.text()), reference);
                        total[v] += e;
                        row.append('\t').append(e);
                    }
                    chars += reference.length();
                    log.append(row).append('\n');
                    System.out.println(row);
                    Files.writeString(out, log.toString());
                }
            }
        }
        StringBuilder t = new StringBuilder("all\tTOTAL\t" + chars);
        for (long e : total) t.append('\t').append(e);
        log.append(t).append('\n');
        System.out.println(t);
        Files.writeString(out, log.toString());
    }

    static BufferedImage rgb(BufferedImage im) {
        BufferedImage o = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = o.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, o.getWidth(), o.getHeight());
        g.drawImage(im, 0, 0, null); g.dispose();
        return o;
    }

    static BufferedImage variant(BufferedImage im, String v) throws IOException {
        if (v.equals("none")) return im;
        if (v.startsWith("rot")) {
            int turns = Integer.parseInt(v.substring(3)) / 90;
            BufferedImage src = rgb(im);
            for (int t = 0; t < turns; t++) {
                int w = src.getWidth(), h = src.getHeight();
                BufferedImage o = new BufferedImage(h, w, BufferedImage.TYPE_3BYTE_BGR);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) o.setRGB(h - 1 - y, x, src.getRGB(x, y));
                src = o;
            }
            return src;
        }
        if (v.startsWith("skew")) {
            double rad = Math.toRadians(Double.parseDouble(v.substring(4)));
            int w = im.getWidth(), h = im.getHeight();
            int nw = (int) Math.ceil(Math.abs(w * Math.cos(rad)) + Math.abs(h * Math.sin(rad)));
            int nh = (int) Math.ceil(Math.abs(w * Math.sin(rad)) + Math.abs(h * Math.cos(rad)));
            BufferedImage o = new BufferedImage(nw, nh, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = o.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, nw, nh);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            AffineTransform t = new AffineTransform();
            t.translate(nw / 2.0, nh / 2.0); t.rotate(rad); t.translate(-w / 2.0, -h / 2.0);
            g.drawImage(im, t, null); g.dispose();
            return o;
        }
        if (v.equals("halfup")) {
            // Half the resolution, then back to the old size: what an enlarging step would see.
            BufferedImage small = variant(im, "half");
            BufferedImage o = new BufferedImage(small.getWidth() * 2, small.getHeight() * 2, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = o.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(small, 0, 0, o.getWidth(), o.getHeight(), null); g.dispose();
            return o;
        }
        if (v.equals("half")) {
            BufferedImage src = rgb(im);
            int w = src.getWidth() / 2, h = src.getHeight() / 2;
            BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int r = 0, gr = 0, bl = 0;
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                    int p = src.getRGB(x * 2 + dx, y * 2 + dy);
                    r += (p >> 16) & 255; gr += (p >> 8) & 255; bl += p & 255;
                }
                o.setRGB(x, y, 0xff000000 | ((r + 2) / 4) << 16 | ((gr + 2) / 4) << 8 | ((bl + 2) / 4));
            }
            return o;
        }
        if (v.startsWith("keyx") || v.startsWith("keyy")) {
            // A page photographed from below (keyy: its top edge shorter by N percent, lines stay
            // level, margins lean in) or from the side (keyx: its right edge shorter, lines fan out).
            double a = 1 - Double.parseDouble(v.substring(4)) / 100.0;
            boolean side = v.startsWith("keyx");
            BufferedImage src = rgb(im);
            int w = src.getWidth(), h = src.getHeight();
            int[] in = src.getRGB(0, 0, w, h, null, 0, w);
            int[] out = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                double sx, sy;
                if (side) {
                    double sc = 1 - (1 - a) * x / (double) w;
                    sx = x; sy = h / 2.0 + (y - h / 2.0) / sc;
                } else {
                    double sc = a + (1 - a) * y / (double) h;
                    sy = y; sx = w / 2.0 + (x - w / 2.0) / sc;
                }
                out[y * w + x] = sample(in, w, h, sx, sy);
            }
            BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            o.setRGB(0, 0, w, h, out, 0, w);
            return o;
        }
        if (v.startsWith("persx")) {
            // A page photographed from the side, in true perspective: its right edge is N percent
            // shorter, the lines run to one point, and the page is narrower towards that edge.
            double k = Double.parseDouble(v.substring(5)) / 100.0;
            BufferedImage src = rgb(im);
            int w = src.getWidth(), h = src.getHeight();
            double pp = k / ((1 - k) * w);
            int[] in = src.getRGB(0, 0, w, h, null, 0, w);
            int[] out = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                double den = 1 - pp * x;
                double sx = den > 0 ? x / den : -1;
                double sy = h / 2.0 + (y - h / 2.0) * (1 + pp * sx);
                out[y * w + x] = sample(in, w, h, sx, sy);
            }
            BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            o.setRGB(0, 0, w, h, out, 0, w);
            return o;
        }
        if (v.startsWith("blur")) {
            // Out of focus: a Gaussian of sigma N tenths of a pixel.
            double sigma = Double.parseDouble(v.substring(4)) / 10.0;
            int r = (int) Math.ceil(sigma * 3);
            float[] k = new float[2 * r + 1]; float sum = 0;
            for (int i = -r; i <= r; i++) { k[i + r] = (float) Math.exp(-i * i / (2 * sigma * sigma)); sum += k[i + r]; }
            for (int i = 0; i < k.length; i++) k[i] /= sum;
            BufferedImage src = rgb(im);
            BufferedImage mid = new ConvolveOp(new Kernel(k.length, 1, k), ConvolveOp.EDGE_NO_OP, null).filter(src, null);
            return new ConvolveOp(new Kernel(1, k.length, k), ConvolveOp.EDGE_NO_OP, null).filter(mid, null);
        }
        if (v.startsWith("shade")) {
            // Uneven light: full at the top left, N percent of it at the bottom right, and the
            // shadow of a hand's edge across the lower third, a quarter darker again.
            double low = Double.parseDouble(v.substring(5)) / 100.0;
            BufferedImage src = rgb(im);
            int w = src.getWidth(), h = src.getHeight();
            int[] px = src.getRGB(0, 0, w, h, null, 0, w);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                double t = (x / (double) w + y / (double) h) / 2;
                double f = 1 - (1 - low) * t;
                double edge = (y - 0.68 * h - 0.12 * x) / (0.01 * h);
                f *= 1 - 0.25 / (1 + Math.exp(-edge));
                int c = px[y * w + x];
                int rr = (int) (((c >> 16) & 255) * f), gg = (int) (((c >> 8) & 255) * f), bb = (int) ((c & 255) * f);
                px[y * w + x] = 0xff000000 | rr << 16 | gg << 8 | bb;
            }
            BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            o.setRGB(0, 0, w, h, px, 0, w);
            return o;
        }
        if (v.startsWith("jpeg")) {
            float q = Integer.parseInt(v.substring(4)) / 100f;
            ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); p.setCompressionQuality(q);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ImageOutputStream s = ImageIO.createImageOutputStream(bytes)) {
                w.setOutput(s); w.write(null, new IIOImage(rgb(im), null, null), p);
            }
            w.dispose();
            return ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        }
        throw new IllegalArgumentException(v);
    }

    /** Bilinear; white outside. */
    static int sample(int[] in, int w, int h, double sx, double sy) {
        if (sx < 0 || sy < 0 || sx > w - 1 || sy > h - 1) return 0xffffffff;
        int x0 = (int) sx, y0 = (int) sy, x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        double fx = sx - x0, fy = sy - y0;
        int out = 0xff000000;
        for (int sh = 0; sh <= 16; sh += 8) {
            double a = (in[y0 * w + x0] >> sh) & 255, b = (in[y0 * w + x1] >> sh) & 255, c = (in[y1 * w + x0] >> sh) & 255, d = (in[y1 * w + x1] >> sh) & 255;
            int val = (int) Math.round((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy);
            out |= val << sh;
        }
        return out;
    }
}
