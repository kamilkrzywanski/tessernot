package io.github.kamilkrzywanski.ocr;

import io.github.kamilkrzywanski.ocr.internal.eval.Samples;
import io.github.kamilkrzywanski.ocr.internal.eval.Metrics;
import io.github.kamilkrzywanski.ocr.internal.image.Images;
import io.github.kamilkrzywanski.ocr.internal.pdf.PdfPages;
import io.github.kamilkrzywanski.ocr.internal.runtime.Session;
import io.github.kamilkrzywanski.ocr.internal.runtime.Tensor;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Command line. OCR text goes to stdout. Timings go to stderr. */
public final class Cli {
    private static final Set<String> SWITCHES = Set.of("no-cls", "no-det", "raw", "json");
    private static final Set<String> FLAGS = Set.of(
            "model", "limit", "threads", "dpi", "no-cls", "no-det", "raw", "json", "corrections", "layout", "hyp", "out");

    private Cli() {
    }

    /** Whether a switch is on. {@code --no-cls} and {@code --no-cls=true} are on; {@code --no-cls=false} is off. */
    static boolean enabled(String[] args, String name) {
        return Parsed.of(args).has(name);
    }

    public static void main(String[] args) {
        int code = execute(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    public static int execute(String[] args) {
        try {
            return run(args);
        } catch (OcrException e) {
            System.err.println(e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }

    private static int run(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return 2;
        }
        if ("help".equals(args[0]) || "--help".equals(args[0])) {
            if (args.length > 1) {
                throw new OcrException("unexpected argument " + args[1]);
            }
            usage();
            return 0;
        }
        Parsed parsed = Parsed.of(args);
        String command = parsed.positionals.isEmpty() ? "" : parsed.positionals.get(0);
        int max = switch (command) {
            case "download" -> 2;
            case "convert" -> 3;
            case "selfcheck", "eval" -> 1;
            case "ocr", "bench", "pdf", "ops", "synth" -> 2;
            case "score" -> 3;
            default -> -1;
        };
        if (max > 0 && parsed.positionals.size() > max) {
            throw new OcrException("unexpected argument " + parsed.positionals.get(max));
        }
        return switch (command) {
            case "download" -> download(parsed);
            case "ocr" -> ocr(parsed);
            case "bench" -> bench(parsed);
            case "pdf" -> pdf(parsed);
            case "convert" -> convert(parsed);
            case "ops" -> ops(parsed);
            case "selfcheck" -> selfcheck(parsed);
            case "synth" -> synth(parsed);
            case "eval" -> eval(parsed);
            case "score" -> score(parsed);
            default -> {
                usage();
                yield 2;
            }
        };
    }

    private static int download(Parsed parsed) {
        only(parsed, "model");
        Path dir = parsed.positionals.size() > 1 ? Path.of(parsed.positionals.get(1)) : modelDir(parsed);
        ModelFiles.ensure(dir);
        System.out.println(dir.toAbsolutePath());
        return 0;
    }

    private static int ocr(Parsed parsed) throws Exception {
        only(parsed, "model", "limit", "threads", "no-cls", "no-det", "json", "corrections", "layout");
        if (parsed.positionals.size() < 2) {
            throw new OcrException("usage: ocr ocr <image> [--model dir] [--limit N] [--no-cls] [--no-det]"
                    + " [--threads N] [--corrections none|polish] [--layout auto|rows] [--json]");
        }
        corrections(parsed);
        layout(parsed);
        integerIfPresent(parsed, "limit");
        integerIfPresent(parsed, "threads");
        Path models = modelDir(parsed);
        requireModels(parsed, models);
        BufferedImage image = readImage(Path.of(parsed.positionals.get(1)));
        try (OcrEngine engine = engine(parsed, models)) {
            OcrResult result = engine.recognize(image);
            if (parsed.has("json")) {
                System.out.print(OcrJson.page(result));
                timing(result);
            } else {
                show(result);
            }
        }
        return 0;
    }

    private static int bench(Parsed parsed) throws Exception {
        only(parsed, "model", "limit", "threads", "no-cls", "no-det", "corrections", "layout");
        if (parsed.positionals.size() < 2) {
            throw new OcrException("usage: ocr bench <image> [--model dir] [--limit N] [--no-cls] [--no-det]"
                    + " [--threads N] [--corrections none|polish] [--layout auto|rows]");
        }
        corrections(parsed);
        layout(parsed);
        integerIfPresent(parsed, "limit");
        integerIfPresent(parsed, "threads");
        Path models = modelDir(parsed);
        requireModels(parsed, models);
        BufferedImage image = readImage(Path.of(parsed.positionals.get(1)));
        try (OcrEngine engine = engine(parsed, models)) {
            engine.recognize(image);
            double[] millis = new double[3];
            for (int i = 0; i < millis.length; i++) {
                millis[i] = engine.recognize(image).totalMillis();
            }
            Arrays.sort(millis);
            System.out.printf(Locale.ROOT, "min %.1f ms%nmedian %.1f ms%n", millis[0], millis[1]);
        }
        return 0;
    }

    private static int pdf(Parsed parsed) throws Exception {
        only(parsed, "dpi", "model", "limit", "threads", "no-cls", "no-det", "json", "corrections", "layout");
        if (parsed.positionals.size() < 2) {
            throw new OcrException("usage: ocr pdf <file> [--dpi N] [--model dir] [--limit N] [--no-cls] [--no-det]"
                    + " [--threads N] [--corrections none|polish] [--layout auto|rows] [--json]");
        }
        corrections(parsed);
        layout(parsed);
        integerIfPresent(parsed, "limit");
        integerIfPresent(parsed, "threads");
        float dpi = floatFlag(parsed, "dpi", 150f);
        if (!(dpi > 0f)) {
            throw new OcrException("--dpi must be positive");
        }
        Path models = modelDir(parsed);
        Path file = Path.of(parsed.positionals.get(1));
        PdfPages.requireUnlocked(file);
        try (OcrEngine engine = engine(parsed, models)) {
            if (parsed.has("json")) {
                List<OcrResult> pages = new ArrayList<>();
                engine.recognizePdf(file, dpi, (index, count, result) -> {
                    pages.add(result);
                    timing(result);
                });
                System.out.print(OcrJson.document(pages));
            } else {
                engine.recognizePdf(file, dpi, (index, count, result) -> {
                    if (count > 1) {
                        System.out.println("--- page " + (index + 1) + " ---");
                    }
                    show(result);
                });
            }
        }
        return 0;
    }

    private static int convert(Parsed parsed) {
        only(parsed);
        if (parsed.positionals.size() < 3) {
            throw new OcrException("usage: ocr convert <in.onnx> <out.jir>");
        }
        Path in = Path.of(parsed.positionals.get(1));
        Path out = Path.of(parsed.positionals.get(2));
        Session session = Session.load(in, new Session.Options(false, 1, null, false, false, true));
        session.saveJir(out);
        System.out.println(out.toAbsolutePath());
        return 0;
    }

    private static int ops(Parsed parsed) {
        only(parsed, "raw");
        if (parsed.positionals.size() < 2) {
            throw new OcrException("usage: ocr ops <model> [--raw]");
        }
        boolean raw = parsed.has("raw");
        Session session = Session.load(Path.of(parsed.positionals.get(1)),
                new Session.Options(false, 1, null, false, false, !raw));
        System.out.println("opset " + session.graph().opset);
        System.out.println("nodes " + session.graph().nodes.size());
        System.out.println("initializers " + session.graph().initializers.size());
        session.histogram().forEach((op, count) -> System.out.println(op + " " + count));
        return 0;
    }

    private static int selfcheck(Parsed parsed) {
        only(parsed);
        var graph = new io.github.kamilkrzywanski.ocr.internal.runtime.Graph();
        graph.inputs.add(new io.github.kamilkrzywanski.ocr.internal.runtime.Sig("x", Tensor.F32, new int[]{1, 1, 1, 1}));
        graph.outputs.add(new io.github.kamilkrzywanski.ocr.internal.runtime.Sig("y", Tensor.F32, new int[]{1, 1, 1, 1}));
        graph.initializers.put("w", Tensor.f32(new int[]{1, 1, 1, 1}, new float[]{2f}));
        graph.initializers.put("b", Tensor.f32(new int[]{1}, new float[]{3f}));
        graph.nodes.add(io.github.kamilkrzywanski.ocr.internal.runtime.Node.of(
                "Conv", "dot", new String[]{"x", "w", "b"}, new String[]{"y"}));
        Session session = Session.create(graph, new Session.Options(true, 1, null, false, false, true));
        float y = session.run(Map.of("x", Tensor.f32(new int[]{1, 1, 1, 1}, new float[]{4f}))).get("y").f[0];
        System.out.printf(Locale.ROOT, "vector=%s y=%.3f%n", session.vector(), y);
        if (Math.abs(y - 11f) > 1e-3f) {
            System.err.println("expected 11");
            return 1;
        }
        return 0;
    }

    private static int synth(Parsed parsed) {
        only(parsed);
        if (parsed.positionals.size() < 2) {
            throw new OcrException("usage: ocr synth <out.png>");
        }
        Samples.writePng(Path.of(parsed.positionals.get(1)));
        System.out.println(Path.of(parsed.positionals.get(1)).toAbsolutePath());
        return 0;
    }

    private static int eval(Parsed parsed) {
        only(parsed, "model", "limit", "threads", "no-cls", "no-det", "corrections", "layout");
        corrections(parsed);
        layout(parsed);
        try (OcrEngine engine = engine(parsed, modelDir(parsed))) {
            OcrResult result = engine.recognize(Samples.invoice());
            double cer = Metrics.cer(result.text(), Samples.invoiceText());
            System.out.printf(Locale.ROOT, "CER %.4f%n", cer);
            show(result);
            return cer > 0.35 ? 1 : 0;
        }
    }

    /**
     * Held-out benchmark: {@code score <ref-dir> <image-dir>} reads every image that has a reference,
     * {@code score <ref-dir> --hyp <dir>} scores ready outputs of another engine instead.
     */
    private static int score(Parsed parsed) {
        only(parsed, "model", "limit", "threads", "no-cls", "no-det", "corrections", "layout", "hyp", "out");
        String hyp = parsed.flag("hyp");
        int wanted = hyp == null ? 3 : 2;
        if (parsed.positionals.size() != wanted) {
            throw new OcrException("usage: ocr score <ref-dir> <image-dir> [--corrections none|polish] [--layout auto|rows] [--out dir]"
                    + " [--model dir] [--limit N] [--threads N]  |  ocr score <ref-dir> --hyp <dir>");
        }
        integerIfPresent(parsed, "limit");
        integerIfPresent(parsed, "threads");
        corrections(parsed);
        layout(parsed);
        Path refs = Path.of(parsed.positionals.get(1));
        Path out = parsed.flag("out") == null ? null : Path.of(parsed.flag("out"));
        if (hyp != null) {
            Score.run(refs, null, Path.of(hyp), out, null, System.out);
            return 0;
        }
        Path models = modelDir(parsed);
        requireModels(parsed, models);
        try (OcrEngine engine = engine(parsed, models)) {
            Score.run(refs, Path.of(parsed.positionals.get(2)), null, out, engine, System.out);
        }
        return 0;
    }

    private static Corrections corrections(Parsed parsed) {
        String value = parsed.flag("corrections");
        return value == null ? Corrections.NONE : Corrections.parse(value);
    }

    private static Layout layout(Parsed parsed) {
        String value = parsed.flag("layout");
        return value == null ? Layout.AUTO : Layout.parse(value);
    }

    private static OcrEngine engine(Parsed parsed, Path models) {
        OcrConfig.Builder builder = OcrConfig.defaults(models).toBuilder().corrections(corrections(parsed))
                .layout(layout(parsed));
        if (parsed.flag("limit") != null) {
            builder.detLimitSideLen(positiveInt(parsed, "limit"));
        }
        if (parsed.flag("threads") != null) {
            builder.threads(positiveInt(parsed, "threads"));
        }
        if (parsed.has("no-cls")) {
            builder.useCls(false);
        }
        if (parsed.has("no-det")) {
            builder.useDet(false);
        }
        return OcrEngine.create(builder.build());
    }

    private static void integerIfPresent(Parsed parsed, String name) {
        if (parsed.flag(name) != null) {
            positiveInt(parsed, name);
        }
    }

    /** Parsed before a file or a model is opened. Zero and negatives are rejected here. */
    private static int positiveInt(Parsed parsed, String name) {
        int value = integerFlag(parsed, name);
        if (value < 1) {
            throw new OcrException("--" + name + " must be an integer >= 1");
        }
        return value;
    }

    private static int integerFlag(Parsed parsed, String name) {
        try {
            return Integer.parseInt(parsed.flag(name));
        } catch (NumberFormatException e) {
            throw new OcrException("--" + name + " must be an integer");
        }
    }

    private static float floatFlag(Parsed parsed, String name, float fallback) {
        String value = parsed.flag(name);
        if (value == null) {
            return fallback;
        }
        try {
            float number = Float.parseFloat(value);
            if (!Float.isFinite(number)) {
                throw new NumberFormatException(value);
            }
            return number;
        } catch (NumberFormatException e) {
            throw new OcrException("--" + name + " must be a number");
        }
    }

    private static void known(String key) {
        if (!FLAGS.contains(key)) {
            throw new OcrException("unknown flag --" + key);
        }
    }

    private static String switchValue(String key, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return "true";
        }
        if ("false".equalsIgnoreCase(value)) {
            return "false";
        }
        throw new OcrException("--" + key + " must be true or false");
    }

    /** Rejects a flag this command never reads, before a file or a model is opened. */
    private static void only(Parsed parsed, String... allowed) {
        Set<String> ok = Set.of(allowed);
        String command = parsed.positionals.isEmpty() ? "" : parsed.positionals.get(0);
        for (String key : parsed.flags.keySet()) {
            if (!ok.contains(key)) {
                throw new OcrException("--" + key + " is not used by " + command);
            }
        }
    }

    private static void requireModels(Parsed parsed, Path models) {
        ModelFiles.requirePresent(models, !parsed.has("no-det"), true, !parsed.has("no-cls"));
    }

    private static Path modelDir(Parsed parsed) {
        String flag = parsed.flag("model");
        if (flag != null && flag.isBlank()) {
            throw new OcrException("--model needs a value");
        }
        Path dir = Path.of(flag == null ? "models" : flag);
        ModelFiles.requireDirectory(dir);
        return dir;
    }

    private static BufferedImage readImage(Path path) {
        return Images.read(path);
    }

    private static void show(OcrResult result) {
        System.out.println(result.text());
        List<TextTable> tables = result.tables();
        for (int i = 0; i < tables.size(); i++) {
            System.out.println();
            System.out.println("--- table " + (i + 1) + " ---");
            for (List<String> row : tables.get(i).cells()) {
                System.out.println(String.join("\t", row));
            }
        }
        timing(result);
    }

    private static void timing(OcrResult result) {
        System.err.printf(Locale.ROOT, "det %.1f ms  rec %.1f ms  total %.1f ms  lines %d%n",
                result.detectionMillis(), result.recognitionMillis(), result.totalMillis(), result.lines().size());
    }

    private static void usage() {
        System.err.println("""
                ocr-pro — pure Java PP-OCR

                ocr download [dir]
                ocr ocr <image> [--model dir] [--limit N] [--no-cls] [--no-det] [--threads N] [--json]
                ocr bench <image> [--model dir] [--limit N] [--no-cls] [--no-det] [--threads N]
                ocr pdf <file> [--dpi N] [--model dir] [--limit N] [--no-cls] [--no-det] [--threads N] [--json]
                ocr convert <in.onnx> <out.jir>
                ocr ops <model> [--raw]
                ocr selfcheck
                ocr synth <out.png>
                ocr eval [--model dir]
                ocr score <ref-dir> <image-dir> [--corrections none|polish] [--layout auto|rows] [--out dir]
                ocr score <ref-dir> --hyp <dir>

                --corrections polish turns on the rules for Polish print (off by default), see docs/corrections.md.
                --layout rows reads every page row by row, with no column analysis: for receipts and other one-column pages.
                Add --add-modules jdk.incubator.vector (see ocr.sh) for the fast backend.
                Scalar mode is used automatically when that module is absent.
                JPEG input is turned upright from EXIF Orientation.
                A PDF header in the first kilobyte belongs to ocr pdf. PNG, JPEG, GIF, BMP, and TIFF belong to ocr ocr, even when that header sits in the file.
                An encrypted PDF, and a TIFF or GIF with more than one image, stops before the model is loaded.
                An extra argument, an empty --model=, a --model path that is a file, and a repeated flag are rejected before a file is opened.
                Missing model files for ocr and bench, and an empty model file, are reported before the image is read.
                A flag the command does not use, such as --dpi on ocr, is rejected before a file is opened.
                A multi-page PDF prints "--- page N ---" before each page. Timings stay on stderr.
                A ruled grid is printed after the page text as "--- table N ---", with cells separated by tabs. A page with no rules prints no table.
                With --json, ocr and pdf print one JSON document instead of the page text: a page object, or {"pages":[...]} for a PDF. Timings stay on stderr.
                """);
    }

    private record Parsed(List<String> positionals, Map<String, String> flags) {
        static Parsed of(String[] args) {
            List<String> positionals = new ArrayList<>();
            Map<String, String> flags = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--") && arg.length() > 2) {
                    String body = arg.substring(2);
                    int eq = body.indexOf('=');
                    if (eq >= 0) {
                        if (eq == 0) {
                            throw new OcrException("bad flag " + arg);
                        }
                        String key = body.substring(0, eq);
                        String value = body.substring(eq + 1);
                        known(key);
                        putFlag(flags, key, SWITCHES.contains(key) ? switchValue(key, value) : value);
                    } else {
                        known(body);
                        if (SWITCHES.contains(body)) {
                            putFlag(flags, body, "true");
                        } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                            putFlag(flags, body, args[++i]);
                        } else {
                            throw new OcrException("--" + body + " needs a value");
                        }
                    }
                } else {
                    positionals.add(arg);
                }
            }
            return new Parsed(positionals, flags);
        }

        String flag(String name) {
            return flags.get(name);
        }

        boolean has(String name) {
            return "true".equals(flags.get(name));
        }
    }

    private static void putFlag(Map<String, String> flags, String key, String value) {
        if (flags.containsKey(key)) {
            throw new OcrException("duplicate flag --" + key);
        }
        flags.put(key, value);
    }
}
