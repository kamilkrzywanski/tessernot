import io.github.kamilkrzywanski.ocr.OcrConfig;
import io.github.kamilkrzywanski.ocr.OcrEngine;
import io.github.kamilkrzywanski.ocr.OcrImages;
import io.github.kamilkrzywanski.ocr.OcrJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * RoDump &lt;json-dir&gt; &lt;ref-dir&gt;... : reads the image of every reference once, with one engine,
 * and writes its JSON. Fragments are kept: the engine finds the reading order with them and
 * drops them afterwards, and the workbench has to do the same. A page that already has its JSON
 * is skipped.
 */
public class RoDump {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        OcrConfig config = OcrConfig.defaults(Path.of("models")).toBuilder().dropFragments(false).build();
        try (OcrEngine engine = OcrEngine.create(config)) {
            for (int a = 1; a < args.length; a++) {
                List<Path> refs = new ArrayList<>();
                try (var s = Files.list(Path.of(args[a]))) {
                    s.filter(p -> p.toString().endsWith(".txt")).sorted().forEach(refs::add);
                }
                for (Path ref : refs) {
                    String name = ref.getFileName().toString().replaceAll("\\.txt$", "");
                    Path json = out.resolve(name + ".json");
                    if (Files.exists(json) && Files.size(json) > 0) {
                        continue;
                    }
                    Path image = null;
                    for (String ext : new String[] {"jpg", "jpeg", "png", "tif", "tiff"}) {
                        Path p = Path.of("bench/data", name + "." + ext);
                        if (Files.isRegularFile(p)) {
                            image = p;
                            break;
                        }
                    }
                    if (image == null) {
                        System.err.println("no image for " + name);
                        continue;
                    }
                    Files.writeString(json, OcrJson.page(engine.recognize(OcrImages.read(image))));
                    System.err.println(name);
                }
            }
        }
    }
}
