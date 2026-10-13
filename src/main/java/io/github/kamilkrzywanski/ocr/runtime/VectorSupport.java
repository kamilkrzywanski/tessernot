package io.github.kamilkrzywanski.ocr.runtime;

/**
 * Loads {@link VectorKernels} only through reflection. Without {@code --add-modules jdk.incubator.vector}
 * that load fails and the scalar backend stays in use.
 */
public final class VectorSupport {
    private static final boolean READY = probe();

    private VectorSupport() {
    }

    public static boolean available() {
        return READY;
    }

    private static boolean probe() {
        try {
            Class<?> cls = Class.forName("io.github.kamilkrzywanski.ocr.runtime.VectorKernels");
            return Boolean.TRUE.equals(cls.getMethod("ready").invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
