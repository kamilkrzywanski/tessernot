package io.github.kamilkrzywanski.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where one model file comes from: a path on disk, or a {@link ModelBundle} on the class path.
 * {@link OcrConfig#defaults(Path)} points at files in a directory, {@link OcrConfig#defaults()} at
 * the bundled ones. A bundled file is read into memory; nothing is extracted to disk.
 */
public final class ModelSource {
    /** Bundled files whose SHA-256 matched {@link ModelFiles}, by bundle class and name. */
    private static final Set<String> VERIFIED = ConcurrentHashMap.newKeySet();

    private final Path path;
    private final String name;

    private ModelSource(Path path, String name) {
        this.path = path;
        this.name = name;
    }

    public static ModelSource of(Path path) {
        if (path == null) {
            throw new OcrException("model path is null");
        }
        Path file = path.getFileName();
        return new ModelSource(path, file == null ? path.toString() : file.toString());
    }

    /** A file that some {@link ModelBundle} on the class path holds, looked up when the engine loads. */
    public static ModelSource bundled(String name) {
        if (name == null || name.isBlank()) {
            throw new OcrException("bundled model name is empty");
        }
        return new ModelSource(null, name);
    }

    /** The path on disk, or null for a bundled file. */
    public Path path() {
        return path;
    }

    /** The file name: the last element of the path, or the name in the bundle. */
    public String name() {
        return name;
    }

    public boolean isBundled() {
        return path == null;
    }

    @Override
    public String toString() {
        return path != null ? path.toString() : "bundled " + name;
    }

    /** Fails when the file is missing or empty, without reading a whole bundled file. */
    void require(String label) {
        if (path != null) {
            ModelFiles.requireFile(path, label);
            return;
        }
        try (InputStream in = open()) {
            if (in == null) {
                throw new OcrException("missing " + label + ": " + name + " is in no ModelBundle on the class path"
                        + " (add io.github.kamilkrzywanski:ocr-pro-models, or use OcrConfig.defaults(dir))");
            }
            if (in.read() < 0) {
                throw new OcrException(label + " is empty: " + this);
            }
        } catch (IOException e) {
            throw new OcrException("cannot read " + this + ": " + e.getMessage(), e);
        }
    }

    /** True for a regular file on disk, or a name that a bundle holds. */
    boolean exists() {
        if (path != null) {
            return Files.isRegularFile(path);
        }
        try (InputStream in = open()) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The whole file. A bundled default model must match the SHA-256 in {@link ModelFiles}, so a
     * bundle from another release fails here instead of reading with thresholds tuned for other
     * weights. The check runs once per bundle and file in a JVM.
     */
    byte[] read() throws IOException {
        if (path != null) {
            return Files.readAllBytes(path);
        }
        for (ModelBundle bundle : bundles()) {
            try (InputStream in = bundle.open(name)) {
                if (in == null) {
                    continue;
                }
                byte[] data = in.readAllBytes();
                verify(bundle, data);
                return data;
            }
        }
        throw new OcrException("missing " + name + ": it is in no ModelBundle on the class path");
    }

    /** Where ONNX external data would be: the directory of a file on disk, null for a bundled file. */
    Path directory() {
        if (path == null) {
            return null;
        }
        return path.getParent() == null ? Path.of(".") : path.getParent();
    }

    /**
     * A file with another name from the same place: the directory of a file on disk, else a bundle
     * on the class path. Null when neither has it. A directory that holds only the model files
     * still finds the word lists of {@code ocr-pro-models-pl} this way.
     */
    ModelSource sibling(String other) {
        Path dir = directory();
        if (dir != null) {
            ModelSource beside = of(dir.resolve(other));
            if (beside.exists()) {
                return beside;
            }
        }
        ModelSource bundled = bundled(other);
        return bundled.exists() ? bundled : null;
    }

    private InputStream open() throws IOException {
        for (ModelBundle bundle : bundles()) {
            InputStream in = bundle.open(name);
            if (in != null) {
                return in;
            }
        }
        return null;
    }

    private void verify(ModelBundle bundle, byte[] data) {
        String expected = ModelFiles.sha256Of(name);
        String key = bundle.getClass().getName() + "/" + name;
        if (expected == null || VERIFIED.contains(key)) {
            return;
        }
        String actual = ModelFiles.sha256(data);
        if (!expected.equals(actual)) {
            throw new OcrException("bundled " + name + " has SHA-256 " + actual + ", not the one this ocr-pro"
                    + " expects; use ocr-pro-models of the same version as ocr-pro");
        }
        VERIFIED.add(key);
    }

    /** Bundles seen by the thread's context class loader and by the loader of this library, each once. */
    private static List<ModelBundle> bundles() {
        List<ModelBundle> found = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        ClassLoader[] loaders = {Thread.currentThread().getContextClassLoader(), ModelBundle.class.getClassLoader()};
        try {
            for (ClassLoader loader : loaders) {
                for (ModelBundle bundle : ServiceLoader.load(ModelBundle.class, loader)) {
                    if (seen.add(bundle.getClass())) {
                        found.add(bundle);
                    }
                }
            }
        } catch (ServiceConfigurationError e) {
            throw new OcrException("cannot list model bundles: " + e.getMessage(), e);
        }
        return found;
    }
}
