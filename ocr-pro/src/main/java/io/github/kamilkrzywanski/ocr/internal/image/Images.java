package io.github.kamilkrzywanski.ocr.internal.image;

import io.github.kamilkrzywanski.ocr.DetLimit;
import io.github.kamilkrzywanski.ocr.OcrException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;

/** Pure Java image ops matched to the PP-OCR / OpenCV preprocess (BGR, half-pixel bilinear). */
public final class Images {
    private Images() {
    }

    /**
     * Decodes an image file. A JPEG is turned upright with its EXIF Orientation tag.
     * PNG and other formats are returned as stored. A missing tag leaves the pixels unchanged.
     * A missing path or a directory fails before the bytes are read.
     */
    public static BufferedImage read(Path path) {
        if (path == null) {
            throw new OcrException("image is null");
        }
        if (!Files.isRegularFile(path)) {
            throw new OcrException("cannot read image " + path + ": not a file");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new OcrException("cannot read image " + path + ": " + e.getMessage(), e);
        }
        if (looksLikePdf(bytes)) {
            throw new OcrException(path + " is a PDF; use ocr pdf");
        }
        return Exif.upright(decode(bytes, path), Exif.orientation(bytes));
    }

    /**
     * Decodes from memory: ImageIO's own stream for an {@code InputStream} is a cache file, which
     * fails where the temporary directory cannot be written. Only a TIFF or a GIF is asked how many
     * images it holds. The JPEG reader counts every start marker after the first picture, so a
     * photo with a gain map or a motion clip behind it would look like several images.
     */
    private static BufferedImage decode(byte[] bytes, Path path) {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new OcrException("cannot read image " + path);
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                boolean container = format.startsWith("tif") || format.equals("gif");
                // Counting needs a stream that can go back; the other formats read forward only, as ImageIO.read does.
                reader.setInput(in, !container, true);
                if (container) {
                    int images = reader.getNumImages(true);
                    if (images > 1) {
                        throw new OcrException(path + " has " + images + " images; ocr reads one image");
                    }
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new OcrException("cannot read image " + path);
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            // A truncated TIFF fails inside its reader with an index error, not an IOException.
            if (e instanceof OcrException ocr) {
                throw ocr;
            }
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new OcrException("cannot read image " + path + ": " + reason, e);
        }
    }

    /**
     * Acrobat accepts a {@code %PDF-} header anywhere in the first 1024 bytes.
     * A match points the caller at {@code ocr pdf} before ImageIO tries to decode the file.
     * PNG, JPEG, GIF, BMP, and TIFF stay images: a comment can contain those bytes,
     * and {@code ocr pdf} would only send the caller back here.
     */
    private static boolean looksLikePdf(byte[] bytes) {
        if (hasImageHeader(bytes, bytes.length)) {
            return false;
        }
        int limit = Math.min(bytes.length, 1024);
        for (int i = 0; i + 5 <= limit; i++) {
            if (bytes[i] == '%' && bytes[i + 1] == 'P' && bytes[i + 2] == 'D'
                    && bytes[i + 3] == 'F' && bytes[i + 4] == '-') {
                return true;
            }
        }
        return false;
    }

    /** Whether {@link #read} shows the pixels of these file bytes as they are stored: no EXIF tag asks for a turn. */
    public static boolean storedUpright(byte[] bytes) {
        return Exif.orientation(bytes) <= 1;
    }

    /** PNG, JPEG, GIF, BMP, and both TIFF byte orders. {@code n} is how many leading bytes are valid. */
    public static boolean hasImageHeader(byte[] head, int n) {
        if (head == null || n <= 0) {
            return false;
        }
        if (n > head.length) {
            n = head.length;
        }
        if (n >= 8 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return true;
        }
        if (n >= 3 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8 && (head[2] & 0xff) == 0xff) {
            return true;
        }
        if (n >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8'
                && (head[4] == '7' || head[4] == '9') && head[5] == 'a') {
            return true;
        }
        // BMP file header: "BM" and the four reserved bytes are zero.
        if (n >= 10 && head[0] == 'B' && head[1] == 'M'
                && head[6] == 0 && head[7] == 0 && head[8] == 0 && head[9] == 0) {
            return true;
        }
        boolean littleTiff = n >= 4 && head[0] == 'I' && head[1] == 'I' && head[2] == 42 && head[3] == 0;
        boolean bigTiff = n >= 4 && head[0] == 'M' && head[1] == 'M' && head[2] == 0 && head[3] == 42;
        return littleTiff || bigTiff;
    }

    public static Bgr toBgr(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int bytes = packedBytes(w, h);
        Bgr direct = fromRaster(image, w, h);
        if (direct != null) {
            return direct;
        }
        Bgr gray = fromGraySamples(image, w, h);
        if (gray != null) {
            return gray;
        }
        int[] px = image.getRGB(0, 0, w, h, null, 0, w);
        byte[] bgr = new byte[bytes];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = (p >>> 24) & 0xff;
            int r = (p >>> 16) & 0xff;
            int g = (p >>> 8) & 0xff;
            int b = p & 0xff;
            r = coverWhite(r, a);
            g = coverWhite(g, a);
            b = coverWhite(b, a);
            int o = i * 3;
            bgr[o] = (byte) b;
            bgr[o + 1] = (byte) g;
            bgr[o + 2] = (byte) r;
        }
        return new Bgr(bgr, w, h);
    }

    /**
     * Standard rasters already store the channels. {@code getRGB} converts them and is the slow part.
     * A child raster or an unusual sample model returns null; the caller then reads gray samples
     * with {@link #fromGraySamples} and everything else with {@code getRGB}.
     */
    private static Bgr fromRaster(BufferedImage image, int w, int h) {
        Raster raster = image.getRaster();
        if (raster.getWidth() != w || raster.getHeight() != h || raster.getDataBuffer().getNumBanks() != 1) {
            return null;
        }
        // Subimages keep a zero minX and park the parent origin in the sample-model translation.
        int sx = raster.getMinX() - raster.getSampleModelTranslateX();
        int sy = raster.getMinY() - raster.getSampleModelTranslateY();
        int type = image.getType();
        if (raster.getDataBuffer() instanceof DataBufferByte bytes
                && raster.getSampleModel() instanceof ComponentSampleModel cm
                && type == BufferedImage.TYPE_3BYTE_BGR) {
            return fromBgrBytes(bytes, cm, w, h, sx, sy);
        }
        if (raster.getDataBuffer() instanceof DataBufferInt ints
                && raster.getSampleModel() instanceof SinglePixelPackedSampleModel packed
                && (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB
                || type == BufferedImage.TYPE_INT_BGR)) {
            return fromPackedInts(ints, packed, w, h, type, sx, sy);
        }
        if (raster.getDataBuffer() instanceof DataBufferByte bytes
                && raster.getSampleModel() instanceof ComponentSampleModel cm
                && type == BufferedImage.TYPE_BYTE_GRAY) {
            return fromGrayBytes(bytes, cm, w, h, sx, sy);
        }
        if (raster.getDataBuffer() instanceof DataBufferByte bytes
                && raster.getSampleModel() instanceof ComponentSampleModel cm
                && type == BufferedImage.TYPE_4BYTE_ABGR) {
            return fromAbgrBytes(bytes, cm, w, h, sx, sy);
        }
        return null;
    }

    /**
     * One gray sample per pixel, the usual form of a gray scan. The sample is the value of all
     * three channels, as it is when the same scan is saved in color or rendered from a PDF.
     * {@code getRGB} would not return it: the JDK takes the gray of these images for linear light
     * and converts it to sRGB (128 comes out as 188), so the page would be read brighter than it
     * was scanned, and only when its file happens to be gray.
     */
    private static Bgr fromGrayBytes(DataBufferByte bytes, ComponentSampleModel cm, int w, int h, int sx, int sy) {
        if (w == 0 || h == 0) {
            return new Bgr(new byte[0], w, h);
        }
        if (cm.getNumBands() != 1 || cm.getPixelStride() != 1) {
            return null;
        }
        byte[] src = bytes.getData();
        int scan = cm.getScanlineStride();
        int start = bytes.getOffset() + cm.getOffset(sx, sy);
        if (scan < w || start < 0 || start + (h - 1) * scan + w > src.length) {
            return null;
        }
        byte[] dst = new byte[packedBytes(w, h)];
        for (int y = 0; y < h; y++) {
            int row = start + y * scan;
            int o = y * w * 3;
            for (int x = 0; x < w; x++) {
                byte v = src[row + x];
                dst[o] = v;
                dst[o + 1] = v;
                dst[o + 2] = v;
                o += 3;
            }
        }
        return new Bgr(dst, w, h);
    }

    /**
     * Every other gray image with one sample per pixel: 16 bits, gray with alpha, a raster the
     * byte path does not take. The samples are scaled to 8 bits and stay what they are, see
     * {@link #fromGrayBytes}. Null when the image is not gray, has a palette, or stores its samples
     * as anything but unsigned integers; the caller then uses {@code getRGB}.
     */
    private static Bgr fromGraySamples(BufferedImage image, int w, int h) {
        ColorModel model = image.getColorModel();
        if (!(model instanceof ComponentColorModel) || model.getColorSpace().getType() != ColorSpace.TYPE_GRAY
                || model.isAlphaPremultiplied()) {
            return null;
        }
        int transfer = model.getTransferType();
        boolean alpha = model.hasAlpha();
        Raster raster = image.getRaster();
        if (transfer != DataBuffer.TYPE_BYTE && transfer != DataBuffer.TYPE_USHORT
                || raster.getNumBands() != (alpha ? 2 : 1) || raster.getWidth() < w || raster.getHeight() < h) {
            return null;
        }
        int max = (1 << model.getComponentSize(0)) - 1;
        int alphaMax = alpha ? (1 << model.getComponentSize(1)) - 1 : 255;
        int[] gray = new int[w];
        int[] cover = alpha ? new int[w] : null;
        byte[] dst = new byte[packedBytes(w, h)];
        for (int y = 0; y < h; y++) {
            raster.getSamples(raster.getMinX(), raster.getMinY() + y, w, 1, 0, gray);
            if (alpha) {
                raster.getSamples(raster.getMinX(), raster.getMinY() + y, w, 1, 1, cover);
            }
            int o = y * w * 3;
            for (int x = 0; x < w; x++) {
                int v = max == 255 ? gray[x] : (gray[x] * 255 + max / 2) / max;
                if (alpha) {
                    v = coverWhite(v, alphaMax == 255 ? cover[x] : (cover[x] * 255 + alphaMax / 2) / alphaMax);
                }
                dst[o] = (byte) v;
                dst[o + 1] = (byte) v;
                dst[o + 2] = (byte) v;
                o += 3;
            }
        }
        return new Bgr(dst, w, h);
    }

    /** TYPE_4BYTE_ABGR, what ImageIO returns for a PNG with alpha: alpha, blue, green, red, laid over white. */
    private static Bgr fromAbgrBytes(DataBufferByte bytes, ComponentSampleModel cm, int w, int h, int sx, int sy) {
        if (w == 0 || h == 0) {
            return new Bgr(new byte[0], w, h);
        }
        if (cm.getNumBands() != 4 || cm.getPixelStride() != 4) {
            return null;
        }
        int[] bands = cm.getBandOffsets();
        if (bands.length != 4 || bands[0] != 3 || bands[1] != 2 || bands[2] != 1 || bands[3] != 0) {
            return null;
        }
        byte[] src = bytes.getData();
        int scan = cm.getScanlineStride();
        // getOffset addresses band 0 (red). The pixel itself starts three bytes earlier, at its alpha.
        int start = bytes.getOffset() + cm.getOffset(sx, sy) - bands[0];
        if (scan < w * 4 || start < 0 || start + (h - 1) * scan + w * 4 > src.length) {
            return null;
        }
        byte[] dst = new byte[packedBytes(w, h)];
        for (int y = 0; y < h; y++) {
            int i = start + y * scan;
            int o = y * w * 3;
            for (int x = 0; x < w; x++) {
                int a = src[i] & 255;
                if (a == 255) {
                    // Most pixels of a page are opaque: no blend.
                    dst[o] = src[i + 1];
                    dst[o + 1] = src[i + 2];
                    dst[o + 2] = src[i + 3];
                } else {
                    dst[o] = (byte) coverWhite(src[i + 1] & 255, a);
                    dst[o + 1] = (byte) coverWhite(src[i + 2] & 255, a);
                    dst[o + 2] = (byte) coverWhite(src[i + 3] & 255, a);
                }
                i += 4;
                o += 3;
            }
        }
        return new Bgr(dst, w, h);
    }

    /** TYPE_3BYTE_BGR stores blue, green, red at offsets 0, 1, 2. */
    private static Bgr fromBgrBytes(DataBufferByte bytes, ComponentSampleModel cm, int w, int h, int sx, int sy) {
        if (w == 0 || h == 0) {
            return new Bgr(new byte[0], w, h);
        }
        if (cm.getNumBands() != 3 || cm.getPixelStride() != 3) {
            return null;
        }
        int[] bands = cm.getBandOffsets();
        if (bands.length != 3 || bands[0] != 2 || bands[1] != 1 || bands[2] != 0) {
            return null;
        }
        byte[] src = bytes.getData();
        int scan = cm.getScanlineStride();
        int rowBytes = w * 3;
        // getOffset addresses band 0 (red). The pixel itself starts two bytes earlier.
        int start = bytes.getOffset() + cm.getOffset(sx, sy) - bands[0];
        if (scan < rowBytes || start < 0 || start + (h - 1) * scan + rowBytes > src.length) {
            return null;
        }
        byte[] dst = new byte[packedBytes(w, h)];
        if (scan == rowBytes) {
            System.arraycopy(src, start, dst, 0, dst.length);
        } else {
            for (int y = 0; y < h; y++) {
                System.arraycopy(src, start + y * scan, dst, y * rowBytes, rowBytes);
            }
        }
        return new Bgr(dst, w, h);
    }

    private static Bgr fromPackedInts(
            DataBufferInt ints, SinglePixelPackedSampleModel packed, int w, int h, int type, int sx, int sy
    ) {
        if (w == 0 || h == 0) {
            return new Bgr(new byte[0], w, h);
        }
        if (packed.getNumBands() < 3) {
            return null;
        }
        int[] src = ints.getData();
        int base = ints.getOffset() + packed.getOffset(sx, sy);
        int scan = packed.getScanlineStride();
        if (scan < w || base < 0 || base + (h - 1) * scan + (w - 1) >= src.length) {
            return null;
        }
        byte[] dst = new byte[packedBytes(w, h)];
        boolean bgr = type == BufferedImage.TYPE_INT_BGR;
        boolean alpha = type == BufferedImage.TYPE_INT_ARGB;
        for (int y = 0; y < h; y++) {
            int row = base + y * scan;
            int o = y * w * 3;
            for (int x = 0; x < w; x++) {
                int p = src[row + x];
                int r;
                int g;
                int b;
                if (bgr) {
                    r = p & 0xff;
                    g = (p >>> 8) & 0xff;
                    b = (p >>> 16) & 0xff;
                } else {
                    r = (p >>> 16) & 0xff;
                    g = (p >>> 8) & 0xff;
                    b = p & 0xff;
                }
                int a = alpha ? (p >>> 24) & 0xff : 255;
                r = coverWhite(r, a);
                g = coverWhite(g, a);
                b = coverWhite(b, a);
                dst[o] = (byte) b;
                dst[o + 1] = (byte) g;
                dst[o + 2] = (byte) r;
                o += 3;
            }
        }
        return new Bgr(dst, w, h);
    }

    /** Straight alpha over white, matching {@code BufferedImage.getRGB} composited onto opaque white. */
    private static int coverWhite(int channel, int alpha) {
        if (alpha == 255) {
            return channel;
        }
        return (channel * alpha + 255 * (255 - alpha)) / 255;
    }

    /** Sides rounded to a multiple of 32, minimum 32. */
    public static int[] detSize(int width, int height, int limit, DetLimit limitType) {
        double ratio = 1.0;
        int maxSide = Math.max(width, height);
        int minSide = Math.max(1, Math.min(width, height));
        if (limitType == DetLimit.MIN) {
            if (minSide < limit) {
                ratio = (double) limit / minSide;
            }
        } else if (maxSide > limit) {
            ratio = (double) limit / maxSide;
        }
        return new int[]{side32(width * ratio, width, height), side32(height * ratio, width, height)};
    }

    /**
     * Multiple of 32, at least 32. Truncates toward zero first, matching the previous cast,
     * then rounds half up. A side that does not fit in an {@code int} is rejected.
     */
    private static int side32(double scaled, int width, int height) {
        if (!Double.isFinite(scaled) || scaled > Integer.MAX_VALUE) {
            throw new OcrException("image is too large " + width + "x" + height);
        }
        return round32((int) scaled, width, height);
    }

    static int round32(int value) {
        return round32(value, value, value);
    }

    private static int round32(int value, int width, int height) {
        if (value <= 0) {
            return 32;
        }
        long rounded = Math.round(value / 32.0) * 32L;
        if (rounded > Integer.MAX_VALUE) {
            throw new OcrException("image is too large " + width + "x" + height);
        }
        return (int) Math.max(32L, rounded);
    }

    public static float[] resizeNormalize(Bgr image, int dw, int dh, float mean, float std) {
        return resizeNormalize(image, dw, dh, mean, std, null, 1);
    }

    /**
     * Half-pixel bilinear resize into planar BGR, then {@code (v / 255 - mean) / std}.
     * {@code pool} may split rows. A thread that already belongs to {@code pool} must pass null.
     */
    public static float[] resizeNormalize(
            Bgr image, int dw, int dh, float mean, float std, ExecutorService pool, int threads
    ) {
        if (dw <= 0 || dh <= 0) {
            throw new OcrException("bad resize " + dw + "x" + dh);
        }
        int sw = image.width();
        int sh = image.height();
        if (sw <= 0 || sh <= 0) {
            throw new OcrException("bad resize source " + sw + "x" + sh);
        }
        // Same bound as a packed BGR buffer: the float tensor has one value per byte.
        int bytes = packedBytes(dw, dh);
        float[] out = new float[bytes];
        int plane = bytes / 3;
        byte[] src = image.data();
        float invStd = 1f / std;
        if (dw == sw && dh == sh) {
            normalizeSameSize(src, out, plane, mean, invStd);
            return out;
        }
        float scaleX = sw / (float) dw;
        float scaleY = sh / (float) dh;
        int[] x0 = new int[dw];
        int[] x1 = new int[dw];
        float[] wx0 = new float[dw];
        float[] wx1 = new float[dw];
        for (int x = 0; x < dw; x++) {
            float sx = (x + 0.5f) * scaleX - 0.5f;
            int ix = (int) Math.floor(sx);
            float fx = sx - ix;
            x0[x] = ix;
            x1[x] = ix + 1;
            wx0[x] = 1f - fx;
            wx1[x] = fx;
        }
        int xLo = 0;
        while (xLo < dw && (x0[xLo] < 0 || x1[xLo] >= sw)) {
            xLo++;
        }
        int xHi = dw;
        while (xHi > xLo && (x0[xHi - 1] < 0 || x1[xHi - 1] >= sw)) {
            xHi--;
        }
        final int ixLo = xLo;
        final int ixHi = xHi;
        if (pool != null && threads > 1 && dh >= 64 && dw >= 64) {
            int parts = Math.min(threads, dh);
            int span = (dh + parts - 1) / parts;
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int p = 0; p < parts; p++) {
                int from = p * span;
                int until = Math.min(dh, from + span);
                if (from >= until) {
                    break;
                }
                int yFrom = from;
                int yUntil = until;
                jobs.add(() -> {
                    resizeRows(src, sw, sh, out, dw, plane, x0, x1, wx0, wx1, ixLo, ixHi,
                            scaleY, mean, invStd, yFrom, yUntil);
                    return null;
                });
            }
            await(pool, jobs);
            return out;
        }
        resizeRows(src, sw, sh, out, dw, plane, x0, x1, wx0, wx1, ixLo, ixHi,
                scaleY, mean, invStd, 0, dh);
        return out;
    }

    private static void await(ExecutorService pool, List<Callable<Void>> jobs) {
        try {
            for (Future<Void> future : pool.invokeAll(jobs)) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new OcrException(cause.getMessage(), cause);
        }
    }

    /**
     * Interior pixels write one plane at a time. The three output planes are far apart, and a
     * per-pixel branch on the sample window blocks the tight loop.
     */
    private static void resizeRows(
            byte[] src, int sw, int sh, float[] out, int dw, int plane,
            int[] x0, int[] x1, float[] wx0, float[] wx1, int xLo, int xHi,
            float scaleY, float mean, float invStd, int yFrom, int yUntil
    ) {
        for (int y = yFrom; y < yUntil; y++) {
            float sy = (y + 0.5f) * scaleY - 0.5f;
            int y0 = (int) Math.floor(sy);
            float fy = sy - y0;
            float wy0 = 1f - fy;
            float wy1 = fy;
            int dstRow = y * dw;
            boolean yInside = y0 >= 0 && y0 + 1 < sh;
            if (!yInside) {
                for (int x = 0; x < dw; x++) {
                    writeSample(src, sw, sh, out, plane, dstRow + x, x0[x], x1[x], y0, y0 + 1,
                            wx0[x], wx1[x], wy0, wy1, mean, invStd);
                }
                continue;
            }
            for (int x = 0; x < xLo; x++) {
                writeSample(src, sw, sh, out, plane, dstRow + x, x0[x], x1[x], y0, y0 + 1,
                        wx0[x], wx1[x], wy0, wy1, mean, invStd);
            }
            for (int x = xHi; x < dw; x++) {
                writeSample(src, sw, sh, out, plane, dstRow + x, x0[x], x1[x], y0, y0 + 1,
                        wx0[x], wx1[x], wy0, wy1, mean, invStd);
            }
            int row0 = y0 * sw;
            int row1 = row0 + sw;
            for (int c = 0; c < 3; c++) {
                int dstBase = c * plane + dstRow;
                for (int x = xLo; x < xHi; x++) {
                    int i00 = (row0 + x0[x]) * 3 + c;
                    int i10 = (row0 + x1[x]) * 3 + c;
                    int i01 = (row1 + x0[x]) * 3 + c;
                    int i11 = (row1 + x1[x]) * 3 + c;
                    float a = wx0[x] * wy0;
                    float b = wx1[x] * wy0;
                    float wc = wx0[x] * wy1;
                    float d = wx1[x] * wy1;
                    out[dstBase + x] = norm(a * (src[i00] & 0xff) + b * (src[i10] & 0xff)
                            + wc * (src[i01] & 0xff) + d * (src[i11] & 0xff), mean, invStd);
                }
            }
        }
    }

    private static void normalizeSameSize(byte[] src, float[] out, int plane, float mean, float invStd) {
        for (int i = 0; i < plane; i++) {
            int o = i * 3;
            out[i] = norm(src[o] & 0xff, mean, invStd);
            out[plane + i] = norm(src[o + 1] & 0xff, mean, invStd);
            out[2 * plane + i] = norm(src[o + 2] & 0xff, mean, invStd);
        }
    }

    private static void writeSample(
            byte[] src, int sw, int sh, float[] out, int plane, int dst,
            int x0, int x1, int y0, int y1, float wx0, float wx1, float wy0, float wy1,
            float mean, float invStd
    ) {
        for (int c = 0; c < 3; c++) {
            float v00 = sample(src, sw, sh, x0, y0, c);
            float v10 = sample(src, sw, sh, x1, y0, c);
            float v01 = sample(src, sw, sh, x0, y1, c);
            float v11 = sample(src, sw, sh, x1, y1, c);
            float v = wy0 * (wx0 * v00 + wx1 * v10) + wy1 * (wx0 * v01 + wx1 * v11);
            out[c * plane + dst] = norm(v, mean, invStd);
        }
    }

    private static float norm(float value, float mean, float invStd) {
        return (value / 255f - mean) * invStd;
    }

    /**
     * Byte length of a packed BGR image, or {@code -1} when the product does not fit in an {@code int}.
     * Pixel count is compared before multiplying by 3, so a wrapped {@code long} is not a small size.
     */
    static long bgrBytes(int width, int height) {
        if (width < 0 || height < 0) {
            return -1L;
        }
        long pixels = (long) width * (long) height;
        if (pixels > Integer.MAX_VALUE / 3L) {
            return -1L;
        }
        return pixels * 3L;
    }

    /**
     * Byte length of a packed BGR image. An {@code int[]} raster can be larger than the
     * {@code byte[]} copy, because {@code width * height * 3} no longer fits in an {@code int}.
     */
    static int packedBytes(int width, int height) {
        long bytes = bgrBytes(width, height);
        if (bytes < 0) {
            throw new OcrException("image is too large " + width + "x" + height);
        }
        return (int) bytes;
    }

    /** Resize to {@code height} and pad on the right with normalized zeros up to at least {@code minWidth}. */
    /** The width a line crop has in the recognizer's input once it is {@code height} high: what {@link #recTensor} fills before the padding. */
    public static int recWidth(Bgr image, int height, int maxWidth) {
        int resizedW = (int) Math.ceil(height * (image.width() / (double) Math.max(1, image.height())));
        return Math.max(1, Math.min(maxWidth, resizedW));
    }

    public static float[] recTensor(Bgr image, int height, int minWidth, int maxWidth, float mean, float std) {
        int resizedW = recWidth(image, height, maxWidth);
        int tensorW = Math.min(maxWidth, Math.max(resizedW, minWidth));
        float[] resized = resizeNormalize(image, resizedW, height, mean, std);
        if (tensorW == resizedW) {
            return resized;
        }
        int bytes = packedBytes(tensorW, height);
        float[] full = new float[bytes];
        int srcPlane = resizedW * height;
        int dstPlane = bytes / 3;
        for (int c = 0; c < 3; c++) {
            for (int y = 0; y < height; y++) {
                System.arraycopy(resized, c * srcPlane + y * resizedW, full, c * dstPlane + y * tensorW, resizedW);
            }
        }
        return full;
    }

    /** Aspect-fit to {@code height} and pad on the right to {@code width}. Used by the 80x160 classifier. */
    public static float[] clsTensor(Bgr image, int height, int width, float mean, float std) {
        return recTensor(image, height, width, width, mean, std);
    }

    public static Bgr rot90ccw(Bgr image) {
        int w = image.width();
        int h = image.height();
        if (w == 0 || h == 0) {
            return new Bgr(new byte[0], h, w);
        }
        byte[] out = new byte[packedBytes(h, w)];
        byte[] in = image.data();
        int nw = h;
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < h; x++) {
                int ox = w - 1 - y;
                int oy = x;
                System.arraycopy(in, (oy * w + ox) * 3, out, (y * nw + x) * 3, 3);
            }
        }
        return new Bgr(out, h, w);
    }

    public static Bgr rot90cw(Bgr image) {
        int w = image.width();
        int h = image.height();
        if (w == 0 || h == 0) {
            return new Bgr(new byte[0], h, w);
        }
        byte[] out = new byte[packedBytes(h, w)];
        byte[] in = image.data();
        int nw = h;
        int nh = w;
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int ox = y;
                int oy = h - 1 - x;
                System.arraycopy(in, (oy * w + ox) * 3, out, (y * nw + x) * 3, 3);
            }
        }
        return new Bgr(out, nw, nh);
    }

    public static Bgr rot180(Bgr image) {
        int w = image.width();
        int h = image.height();
        if (w == 0 || h == 0) {
            return new Bgr(new byte[0], w, h);
        }
        byte[] out = new byte[packedBytes(w, h)];
        byte[] in = image.data();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int ox = w - 1 - x;
                int oy = h - 1 - y;
                System.arraycopy(in, (oy * w + ox) * 3, out, (y * w + x) * 3, 3);
            }
        }
        return new Bgr(out, w, h);
    }

    /**
     * The page with its content turned about its centre by the angle of {@code cos} and
     * {@code sin}, on a white canvas of {@code width} by {@code height}: the output pixel
     * {@code (x, y)} shows the input at {@code (cos dx + sin dy, -sin dx + cos dy)} from the
     * centre, {@code dx} and {@code dy} being its offsets from the centre of the canvas.
     * Bilinear; what lies outside the input is white.
     */
    public static Bgr turn(Bgr image, double cos, double sin, int width, int height) {
        int w = image.width();
        int h = image.height();
        byte[] in = image.data();
        byte[] out = new byte[packedBytes(width, height)];
        double cx = width * 0.5;
        double cy = height * 0.5;
        for (int y = 0; y < height; y++) {
            double dy = y + 0.5 - cy;
            for (int x = 0; x < width; x++) {
                double dx = x + 0.5 - cx;
                // Continuous input coordinates of this pixel's centre, then pixel indices.
                float sx = (float) (cos * dx + sin * dy + w * 0.5 - 0.5);
                float sy = (float) (-sin * dx + cos * dy + h * 0.5 - 0.5);
                int o = (y * width + x) * 3;
                if (sx < -0.5f || sy < -0.5f || sx > w - 0.5f || sy > h - 0.5f) {
                    out[o] = (byte) 255;
                    out[o + 1] = (byte) 255;
                    out[o + 2] = (byte) 255;
                } else {
                    for (int c = 0; c < 3; c++) {
                        out[o + c] = (byte) Math.round(sampleBilinear(in, w, h, sx, sy, c));
                    }
                }
            }
        }
        return new Bgr(out, width, height);
    }

    /** Projection-profile deskew over about -10..10 degrees. Off by default in the engine. */
    public static Bgr deskew(Bgr image) {
        if (image.width() <= 0 || image.height() <= 0) {
            return image;
        }
        int maxSide = 220;
        float scale = Math.min(1f, maxSide / (float) Math.max(image.width(), image.height()));
        int sw = Math.max(8, Math.round(image.width() * scale));
        int sh = Math.max(8, Math.round(image.height() * scale));
        byte[] small = downGray(image, sw, sh);
        double bestAngle = 0;
        double best = variance(projection(small, sw, sh, 0));
        for (double angle = -10; angle <= 10.0001; angle += 0.5) {
            if (angle == 0) {
                continue;
            }
            double score = variance(projection(small, sw, sh, angle));
            if (score > best) {
                best = score;
                bestAngle = angle;
            }
        }
        if (Math.abs(bestAngle) < 0.25) {
            return image;
        }
        return rotate(image, bestAngle);
    }

    /** Sauvola binarization. Off by default; neural PP-OCR usually prefers the raw scan. */
    public static Bgr sauvola(Bgr image) {
        int w = image.width();
        int h = image.height();
        if (w == 0 || h == 0) {
            return image;
        }
        int n = w * h;
        int[] gray = new int[n];
        byte[] src = image.data();
        for (int i = 0; i < n; i++) {
            int b = src[i * 3] & 0xff;
            int g = src[i * 3 + 1] & 0xff;
            int r = src[i * 3 + 2] & 0xff;
            gray[i] = (r * 77 + g * 150 + b * 29) >> 8;
        }
        int stride = w + 1;
        long[] integral = new long[(w + 1) * (h + 1)];
        long[] square = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int g = gray[y * w + x];
                int idx = (y + 1) * stride + (x + 1);
                integral[idx] = g + integral[idx - 1] + integral[idx - stride] - integral[idx - stride - 1];
                square[idx] = (long) g * g + square[idx - 1] + square[idx - stride] - square[idx - stride - 1];
            }
        }
        byte[] out = new byte[n * 3];
        int half = 12;
        float k = 0.2f;
        float r = 128f;
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - half);
            int y1 = Math.min(h - 1, y + half);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - half);
                int x1 = Math.min(w - 1, x + half);
                int area = (x1 - x0 + 1) * (y1 - y0 + 1);
                long sum = rect(integral, stride, x0, y0, x1, y1);
                long sumSq = rect(square, stride, x0, y0, x1, y1);
                double mean = sum / (double) area;
                double var = Math.max(0, sumSq / (double) area - mean * mean);
                double std = Math.sqrt(var);
                double threshold = mean * (1.0 + k * (std / r - 1.0));
                int v = gray[y * w + x] < threshold ? 0 : 255;
                int o = (y * w + x) * 3;
                out[o] = out[o + 1] = out[o + 2] = (byte) v;
            }
        }
        return new Bgr(out, w, h);
    }

    /** Below this spread between the crop's ink and paper luma there is no ink to find bands in. */
    private static final int BAND_CONTRAST = 40;

    /**
     * Keeps the heavier ink band when a second band is split off by at least 20 white rows.
     * A shorter gap stays, so a dot over a letter is not cut away. Two bands of similar
     * weight stay as well. Recognition then scales the remaining glyphs to the model height
     * instead of sharing that height with a neighbor line. Ink and paper are the two luma
     * classes of the crop itself (Otsu's split), so the faint brown digits of a thermal
     * ticket count as ink too; with a fixed cut at 150 they did not, and a speck was the band.
     */
    public static Bgr mainInkBand(Bgr image) {
        int w = image.width();
        int h = image.height();
        if (h < 16 || w < 8) {
            return image;
        }
        byte[] data = image.data();
        int threshold = inkThreshold(data, w * h);
        if (threshold < 0) {
            return image;
        }
        int[] dark = new int[h];
        for (int y = 0; y < h; y++) {
            int row = y * w * 3;
            int count = 0;
            for (int x = 0; x < w; x++) {
                if (inkLuma(data, row + x * 3) < threshold) {
                    count++;
                }
            }
            dark[y] = count;
        }
        boolean[] rule = hairline(dark, w);
        int[] start = new int[h];
        int[] end = new int[h];
        int bands = 0;
        for (int y = 0; y < h;) {
            if (rule[y] || dark[y] == 0) {
                y++;
                continue;
            }
            int a = y;
            while (y < h && !rule[y] && dark[y] > 0) {
                y++;
            }
            // Nineteen white rows still belong to one line. Twenty is the next line.
            if (bands > 0 && a - end[bands - 1] - 1 < 20) {
                end[bands - 1] = y - 1;
            } else {
                start[bands] = a;
                end[bands] = y - 1;
                bands++;
            }
        }
        if (bands < 2) {
            return image;
        }
        int keep = 0;
        int keepInk = -1;
        int secondInk = -1;
        for (int i = 0; i < bands; i++) {
            int ink = 0;
            for (int y = start[i]; y <= end[i]; y++) {
                ink += dark[y];
            }
            if (ink > keepInk) {
                secondInk = keepInk;
                keepInk = ink;
                keep = i;
            } else if (ink > secondInk) {
                secondInk = ink;
            }
        }
        if (secondInk > 0 && keepInk < secondInk * 1.4f) {
            return image;
        }
        int y0 = start[keep];
        int y1 = end[keep];
        if (y0 > 0 && !rule[y0 - 1] && dark[y0 - 1] == 0) {
            y0--;
        }
        if (y1 + 1 < h && !rule[y1 + 1] && dark[y1 + 1] == 0) {
            y1++;
        }
        if (y0 == 0 && y1 == h - 1) {
            return image;
        }
        return copyRows(image, y0, y1 - y0 + 1);
    }

    /**
     * Cuts a row index glued to the words on its right.
     * The index is a narrow dark blob, then at least four white columns, then a vertical rule
     * at most four pixels wide. The rule lies in the left eighth of the crop and not past x = 56.
     * A letter stem, a wide blob, or a missing gap returns the same image.
     * The caller keeps the shorter crop only when a second read is the first read without that number.
     */
    public static Bgr cutLeftIndex(Bgr image) {
        int w = image.width();
        int h = image.height();
        if (w < 80 || h < 16) {
            return image;
        }
        int from = 4;
        int to = Math.min(w / 8, 56);
        if (to <= from + 2) {
            return image;
        }
        byte[] data = image.data();
        int[] dark = new int[w];
        int limit = Math.min(w, to + 8);
        for (int x = 0; x < limit; x++) {
            int count = 0;
            for (int y = 0; y < h; y++) {
                if (inkLuma(data, (y * w + x) * 3) < 140) {
                    count++;
                }
            }
            dark[x] = count;
        }
        int bestX = -1;
        int best = 0;
        for (int x = from; x < to; x++) {
            if (dark[x] > best) {
                best = dark[x];
                bestX = x;
            }
        }
        // The rule on the measured row is about two fifths of the crop height.
        if (bestX < 0 || best * 100 < h * 36) {
            return image;
        }
        int left = bestX;
        while (left > 0 && dark[left - 1] * 3 >= best) {
            left--;
        }
        int right = bestX;
        while (right + 1 < limit && dark[right + 1] * 3 >= best) {
            right++;
        }
        if (right - left + 1 > 4) {
            return image;
        }
        int x = left - 1;
        int gap = 0;
        while (x >= 0 && dark[x] * 10 < h) {
            gap++;
            x--;
        }
        if (gap < 4) {
            return image;
        }
        int blob = 0;
        int blobRight = x;
        int blobLeft = -1;
        while (x >= 0) {
            if (dark[x] > blob) {
                blob = dark[x];
            }
            if (dark[x] * 10 >= h * 2) {
                blobLeft = x;
            }
            x--;
        }
        // A digit is a few pixels wide. The letter before a stem is wider, so it stays.
        if (blob * 10 < h * 3 || blobLeft < 0 || blobRight - blobLeft + 1 > 12) {
            return image;
        }
        int start = right + 2;
        if (start >= w - 1) {
            return image;
        }
        return copyColumns(image, start, w - start);
    }

    /** A full-width stroke of at most four rows is a rule, not a second text line. */
    private static boolean[] hairline(int[] dark, int w) {
        int ruleCut = Math.max(4, (w * 7) / 10);
        boolean[] rule = new boolean[dark.length];
        for (int y = 0; y < dark.length;) {
            if (dark[y] < ruleCut) {
                y++;
                continue;
            }
            int a = y;
            while (y < dark.length && dark[y] >= ruleCut) {
                y++;
            }
            if (y - a <= 4) {
                for (int i = a; i < y; i++) {
                    rule[i] = true;
                }
            }
        }
        return rule;
    }

    /**
     * Luma below which a pixel of the crop is ink: Otsu's split of the crop's luma histogram
     * into the two classes with the largest variance between them, weighted by their sizes, so
     * a speck does not pull the split down to itself the way a percentile anchor would. -1 when
     * the class means are closer than {@link #BAND_CONTRAST}, as on a blank crop.
     */
    static int inkThreshold(byte[] data, int pixels) {
        int[] hist = new int[256];
        long sumAll = 0;
        for (int i = 0; i < pixels; i++) {
            int v = inkLuma(data, i * 3);
            hist[v]++;
            sumAll += v;
        }
        long weightDark = 0;
        long sumDark = 0;
        double best = -1;
        int split = -1;
        double meanDark = 0;
        double meanLight = 0;
        for (int t = 0; t < 256; t++) {
            weightDark += hist[t];
            sumDark += (long) t * hist[t];
            long weightLight = pixels - weightDark;
            if (weightDark == 0) {
                continue;
            }
            if (weightLight == 0) {
                break;
            }
            double mDark = sumDark / (double) weightDark;
            double mLight = (sumAll - sumDark) / (double) weightLight;
            double between = (double) weightDark * weightLight * (mDark - mLight) * (mDark - mLight);
            if (between > best) {
                best = between;
                split = t;
                meanDark = mDark;
                meanLight = mLight;
            }
        }
        if (split < 0 || meanLight - meanDark < BAND_CONTRAST) {
            return -1;
        }
        return split + 1;
    }

    private static int inkLuma(byte[] data, int i) {
        int b = data[i] & 255;
        int g = data[i + 1] & 255;
        int r = data[i + 2] & 255;
        return (r * 54 + g * 183 + b * 19) >> 8;
    }

    private static Bgr copyColumns(Bgr src, int x, int w) {
        int h = src.height();
        byte[] out = new byte[packedBytes(w, h)];
        byte[] data = src.data();
        int sw = src.width();
        for (int row = 0; row < h; row++) {
            System.arraycopy(data, (row * sw + x) * 3, out, row * w * 3, w * 3);
        }
        return new Bgr(out, w, h);
    }

    private static Bgr copyRows(Bgr src, int y, int h) {
        int w = src.width();
        if (h < 2) {
            return src;
        }
        byte[] out = new byte[packedBytes(w, h)];
        byte[] data = src.data();
        for (int row = 0; row < h; row++) {
            System.arraycopy(data, (y + row) * w * 3, out, row * w * 3, w * 3);
        }
        return new Bgr(out, w, h);
    }

    private static long rect(long[] integral, int stride, int x0, int y0, int x1, int y1) {
        int a = y0 * stride + x0;
        int b = y0 * stride + (x1 + 1);
        int c = (y1 + 1) * stride + x0;
        int d = (y1 + 1) * stride + (x1 + 1);
        return integral[d] - integral[b] - integral[c] + integral[a];
    }

    private static byte[] downGray(Bgr image, int dw, int dh) {
        byte[] out = new byte[dw * dh];
        float scaleX = image.width() / (float) dw;
        float scaleY = image.height() / (float) dh;
        for (int y = 0; y < dh; y++) {
            int sy = Math.min(image.height() - 1, (int) (y * scaleY));
            for (int x = 0; x < dw; x++) {
                int sx = Math.min(image.width() - 1, (int) (x * scaleX));
                int o = (sy * image.width() + sx) * 3;
                int b = image.data()[o] & 0xff;
                int g = image.data()[o + 1] & 0xff;
                int r = image.data()[o + 2] & 0xff;
                out[y * dw + x] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
            }
        }
        return out;
    }

    private static int[] projection(byte[] gray, int w, int h, double angleDeg) {
        double rad = Math.toRadians(angleDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double cx = (w - 1) / 2.0;
        double cy = (h - 1) / 2.0;
        int[] hist = new int[h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = x - cx;
                double dy = y - cy;
                int sx = (int) Math.round(cos * dx + sin * dy + cx);
                int sy = (int) Math.round(-sin * dx + cos * dy + cy);
                if (sx >= 0 && sy >= 0 && sx < w && sy < h && (gray[sy * w + sx] & 0xff) < 180) {
                    hist[y]++;
                }
            }
        }
        return hist;
    }

    private static double variance(int[] hist) {
        double mean = 0;
        for (int v : hist) {
            mean += v;
        }
        mean /= hist.length;
        double var = 0;
        for (int v : hist) {
            double d = v - mean;
            var += d * d;
        }
        return var / hist.length;
    }

    private static Bgr rotate(Bgr image, double angleDeg) {
        int w = image.width();
        int h = image.height();
        double rad = Math.toRadians(angleDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double cx = (w - 1) / 2.0;
        double cy = (h - 1) / 2.0;
        byte[] out = new byte[w * h * 3];
        byte[] in = image.data();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = x - cx;
                double dy = y - cy;
                float sx = (float) (cos * dx + sin * dy + cx);
                float sy = (float) (-sin * dx + cos * dy + cy);
                int o = (y * w + x) * 3;
                for (int c = 0; c < 3; c++) {
                    out[o + c] = (byte) Math.round(sampleBilinear(in, w, h, sx, sy, c));
                }
            }
        }
        return new Bgr(out, w, h);
    }

    private static float sampleBilinear(byte[] src, int w, int h, float sx, float sy, int c) {
        int x0 = (int) Math.floor(sx);
        int y0 = (int) Math.floor(sy);
        float fx = sx - x0;
        float fy = sy - y0;
        float v00 = sample(src, w, h, x0, y0, c);
        float v10 = sample(src, w, h, x0 + 1, y0, c);
        float v01 = sample(src, w, h, x0, y0 + 1, c);
        float v11 = sample(src, w, h, x0 + 1, y0 + 1, c);
        return (1f - fx) * (1f - fy) * v00 + fx * (1f - fy) * v10 + (1f - fx) * fy * v01 + fx * fy * v11;
    }

    private static float sample(byte[] src, int w, int h, int x, int y, int c) {
        if (w <= 0 || h <= 0) {
            return 0f;
        }
        if (x < 0) {
            x = 0;
        } else if (x >= w) {
            x = w - 1;
        }
        if (y < 0) {
            y = 0;
        } else if (y >= h) {
            y = h - 1;
        }
        return src[(y * w + x) * 3 + c] & 0xff;
    }

    /** Draws a white page. Callers set the font and strings. */
    public static Graphics2D prepare(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(java.awt.Color.BLACK);
        return g;
    }
}
