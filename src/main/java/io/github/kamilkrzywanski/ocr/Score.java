package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.eval.Metrics;
import io.github.kamilkrzywanski.ocr.internal.image.Images;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Held-out benchmark. Every {@code <name>.txt} in the reference directory is compared with the
 * page text of {@code <name>.<image>} read by the engine, or with {@code <name>.txt} from a
 * directory of ready outputs (another engine). Both sides go through {@link Metrics#normalize}.
 * CER is order-sensitive; the word score is not, so a different column order costs CER only.
 */
final class Score {
    private static final String[] IMAGES = {".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp", ".gif"};

    record Row(String name, int chars, int edits, int matched, int hypWords, int refWords, double millis) {
    }

    private Score() {
    }

    /** {@code engine} is null when {@code hypDir} holds the outputs. {@code outDir} may be null. */
    static List<Row> run(Path refDir, Path imageDir, Path hypDir, Path outDir, OcrEngine engine, PrintStream out) {
        List<String> names = references(refDir);
        if (names.isEmpty()) {
            throw new OcrException("no .txt references in " + refDir);
        }
        List<Path> images = new ArrayList<>();
        if (hypDir == null) {
            for (String name : names) {
                images.add(image(imageDir, name));
            }
            // The first page pays for JIT and arena growth. It is read once before timing.
            engine.recognize(Images.read(images.get(0)));
        }
        List<Row> rows = new ArrayList<>();
        out.println("name\tchars\tCER\twordF1\tms");
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String reference = Metrics.normalize(read(refDir.resolve(name + ".txt")));
            String text;
            double millis;
            if (hypDir == null) {
                OcrResult result = engine.recognize(Images.read(images.get(i)));
                text = result.text();
                millis = result.totalMillis();
            } else {
                Path hyp = hypDir.resolve(name + ".txt");
                text = Files.isRegularFile(hyp) ? read(hyp) : "";
                millis = Double.NaN;
            }
            if (outDir != null) {
                write(outDir.resolve(name + ".txt"), text);
            }
            String hypothesis = Metrics.normalize(text);
            int[] words = Metrics.wordMatches(hypothesis, reference);
            Row row = new Row(name, reference.length(), Metrics.levenshtein(hypothesis, reference),
                    words[0], words[1], words[2], millis);
            rows.add(row);
            out.printf(Locale.ROOT, "%s\t%d\t%.4f\t%.4f\t%s%n", name, row.chars,
                    row.chars == 0 ? 0.0 : row.edits / (double) row.chars,
                    f1(row.matched, row.hypWords, row.refWords), ms(row.millis));
        }
        summary(rows, out);
        return rows;
    }

    /** Totals per name prefix ({@code pl-}, {@code en-}) and for all rows. CER is summed edits over summed characters. */
    static void summary(List<Row> rows, PrintStream out) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            int dash = row.name.indexOf('-');
            String group = dash > 0 ? row.name.substring(0, dash) : row.name;
            groups.computeIfAbsent(group, k -> new ArrayList<>()).add(row);
        }
        out.println();
        out.println("group\tdocs\tchars\tCER\twordF1\tmedian ms");
        if (groups.size() > 1) {
            groups.forEach((group, list) -> total(group, list, out));
        }
        total("all", rows, out);
    }

    private static void total(String label, List<Row> rows, PrintStream out) {
        long chars = 0;
        long edits = 0;
        long matched = 0;
        long hypWords = 0;
        long refWords = 0;
        List<Double> times = new ArrayList<>();
        for (Row row : rows) {
            chars += row.chars;
            edits += row.edits;
            matched += row.matched;
            hypWords += row.hypWords;
            refWords += row.refWords;
            if (!Double.isNaN(row.millis)) {
                times.add(row.millis);
            }
        }
        times.sort(null);
        double median = times.isEmpty() ? Double.NaN : times.size() % 2 == 1
                ? times.get(times.size() / 2)
                : (times.get(times.size() / 2 - 1) + times.get(times.size() / 2)) / 2;
        out.printf(Locale.ROOT, "%s\t%d\t%d\t%.4f\t%.4f\t%s%n", label, rows.size(), chars,
                chars == 0 ? 0.0 : edits / (double) chars, f1(matched, hypWords, refWords), ms(median));
    }

    static double f1(long matched, long hypWords, long refWords) {
        if (matched == 0) {
            return hypWords == 0 && refWords == 0 ? 1.0 : 0.0;
        }
        double precision = matched / (double) hypWords;
        double recall = matched / (double) refWords;
        return 2 * precision * recall / (precision + recall);
    }

    private static String ms(double millis) {
        return Double.isNaN(millis) ? "-" : String.format(Locale.ROOT, "%.0f", millis);
    }

    private static List<String> references(Path refDir) {
        if (refDir == null || !Files.isDirectory(refDir)) {
            throw new OcrException("not a directory: " + refDir);
        }
        try (Stream<Path> files = Files.list(refDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".txt"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new OcrException("cannot list " + refDir + ": " + e.getMessage());
        }
    }

    private static Path image(Path imageDir, String name) {
        for (String ext : IMAGES) {
            Path path = imageDir.resolve(name + ext);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new OcrException("no image for " + name + " in " + imageDir + " (run bench/fetch.sh)");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OcrException("cannot read " + path + ": " + e.getMessage());
        }
    }

    private static void write(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text.endsWith("\n") ? text : text + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OcrException("cannot write " + path + ": " + e.getMessage());
        }
    }
}
