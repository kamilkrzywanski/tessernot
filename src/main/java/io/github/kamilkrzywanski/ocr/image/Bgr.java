package io.github.kamilkrzywanski.ocr.image;

/** Packed BGR image, three bytes per pixel, row-major. */
public record Bgr(byte[] data, int width, int height) {
    public Bgr {
        // Compare against the overflow-safe count. A wrapped long used to look smaller than the buffer.
        long bytes = Images.bgrBytes(width, height);
        if (data == null || bytes < 0 || bytes > data.length) {
            throw new IllegalArgumentException("bad bgr buffer " + width + "x" + height);
        }
    }

    public int pixels() {
        return width * height;
    }
}
