package org.krzywanski.ocr.image;

import org.krzywanski.ocr.OcrException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
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
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (looksLikePdf(bytes)) {
                throw new OcrException(path + " is a PDF; use ocr pdf");
            }
            int images = imageCount(bytes);
            if (images > 1) {
                throw new OcrException(path + " has " + images + " images; ocr reads one image");
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new OcrException("cannot read image " + path);
            }
            return Exif.upright(image, Exif.orientation(bytes));
        } catch (IOException e) {
            throw new OcrException("cannot read image " + path + ": " + e.getMessage(), e);
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

    /** {@code -1} when the count is unknown. More than one image is rejected by {@link #read}. */
    private static int imageCount(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return -1;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return -1;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, false);
                return reader.getNumImages(true);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return -1;
        }
    }

    public static Bgr toBgr(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int bytes = packedBytes(w, h);
        Bgr direct = fromRaster(image, w, h);
        if (direct != null) {
            return direct;
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
     * A child raster or an unusual sample model returns null and the caller uses {@code getRGB}.
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
        return null;
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

    /** Sides rounded to a multiple of 32, minimum 32. {@code limitType} is {@code max} or {@code min}. */
    public static int[] detSize(int width, int height, int limit, String limitType) {
        double ratio = 1.0;
        int maxSide = Math.max(width, height);
        int minSide = Math.max(1, Math.min(width, height));
        if ("min".equals(limitType)) {
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
    public static float[] recTensor(Bgr image, int height, int minWidth, int maxWidth, float mean, float std) {
        int resizedW = (int) Math.ceil(height * (image.width() / (double) Math.max(1, image.height())));
        if (resizedW < 1) {
            resizedW = 1;
        }
        if (resizedW > maxWidth) {
            resizedW = maxWidth;
        }
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
        int resizedW = (int) Math.ceil(height * (image.width() / (double) Math.max(1, image.height())));
        if (resizedW < 1) {
            resizedW = 1;
        }
        if (resizedW > width) {
            resizedW = width;
        }
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
