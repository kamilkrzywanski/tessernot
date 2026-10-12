package org.krzywanski.ocr.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.krzywanski.ocr.OcrException;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImagePrepTest {
    @Test
    void toBgrMatchesGetRgbOnStandardTypes() {
        BufferedImage bgr = new BufferedImage(17, 9, BufferedImage.TYPE_3BYTE_BGR);
        assertRgb(bgr, false);
        assertRgb(bgr.getSubimage(2, 1, 13, 6), false);
        assertRgb(new BufferedImage(17, 9, BufferedImage.TYPE_INT_RGB), false);
        assertRgb(new BufferedImage(17, 9, BufferedImage.TYPE_INT_BGR), false);
        BufferedImage argb = new BufferedImage(17, 9, BufferedImage.TYPE_INT_ARGB);
        assertRgb(argb, true);
        assertRgb(argb.getSubimage(3, 2, 11, 5), true);
        BufferedImage gray = new BufferedImage(8, 3, BufferedImage.TYPE_BYTE_GRAY);
        assertRgb(gray, false);
    }

    private static void assertRgb(BufferedImage image, boolean alpha) {
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (x * 17 + y * 29) & 0xff;
                int a = alpha ? ((x * 3 + y) * 40) & 0xff : 255;
                int rgb = (a << 24) | (v << 16) | ((v / 2) << 8) | (255 - v);
                image.setRGB(x, y, rgb);
            }
        }
        int[] px = image.getRGB(0, 0, w, h, null, 0, w);
        byte[] expect = new byte[w * h * 3];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = (p >>> 24) & 0xff;
            int r = (p >>> 16) & 0xff;
            int g = (p >>> 8) & 0xff;
            int b = p & 0xff;
            if (a != 255) {
                r = (r * a + 255 * (255 - a)) / 255;
                g = (g * a + 255 * (255 - a)) / 255;
                b = (b * a + 255 * (255 - a)) / 255;
            }
            expect[i * 3] = (byte) b;
            expect[i * 3 + 1] = (byte) g;
            expect[i * 3 + 2] = (byte) r;
        }
        assertArrayEquals(expect, Images.toBgr(image).data());
    }

    @Test
    void sameSizeNormalizeIsPerChannel() {
        byte[] packed = new byte[]{
                0, 0, (byte) 255,
                (byte) 255, 0, 0
        };
        float[] out = Images.resizeNormalize(new Bgr(packed, 2, 1), 2, 1, 0.5f, 0.5f);
        assertEquals(-1f, out[0], 1e-6f);
        assertEquals(1f, out[1], 1e-6f);
        assertEquals(-1f, out[2], 1e-6f);
        assertEquals(-1f, out[3], 1e-6f);
        assertEquals(1f, out[4], 1e-6f);
        assertEquals(-1f, out[5], 1e-6f);
    }

    @Test
    void bilinearResizeClampsLikeTheScalarFormula() {
        int sw = 3;
        int sh = 2;
        byte[] packed = new byte[sw * sh * 3];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = (byte) (i * 17 + 3);
        }
        int dw = 5;
        int dh = 4;
        float mean = 0.5f;
        float std = 0.5f;
        float[] actual = Images.resizeNormalize(new Bgr(packed, sw, sh), dw, dh, mean, std);
        float[] expect = oracle(packed, sw, sh, dw, dh, mean, std);
        assertArrayEquals(expect, actual, 1e-5f);
    }

    @Test
    void parallelRowsMatchTheSerialResize() {
        byte[] packed = new byte[40 * 30 * 3];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = (byte) (i * 17 + 3);
        }
        Bgr image = new Bgr(packed, 40, 30);
        float[] serial = Images.resizeNormalize(image, 80, 70, 0.5f, 0.5f);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            float[] parallel = Images.resizeNormalize(image, 80, 70, 0.5f, 0.5f, pool, 4);
            assertArrayEquals(serial, parallel, 0f);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void bgrRejectsAByteCountThatOverflowsInt() {
        assertThrows(IllegalArgumentException.class, () -> new Bgr(new byte[8], 50_000, 50_000));
        assertEquals(0, new Bgr(new byte[0], 0, 0).pixels());
        assertEquals(12, Images.packedBytes(2, 2));
        // 40_000 * 20_000 fits in an int raster. The BGR copy does not.
        OcrException tooLarge = assertThrows(OcrException.class, () -> Images.packedBytes(40_000, 20_000));
        assertTrue(tooLarge.getMessage().contains("40000x20000"), tooLarge.getMessage());
        // width * height * 3 wraps a long to a small positive value. That used to look allocatable.
        OcrException wrapped = assertThrows(OcrException.class,
                () -> Images.packedBytes(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertTrue(wrapped.getMessage().contains("too large"), wrapped.getMessage());
        assertThrows(OcrException.class, () -> Images.packedBytes(1_800_000_000, 1_800_000_000));
        assertEquals(2_147_483_646, Images.packedBytes(1, 715_827_882));
        assertThrows(OcrException.class, () -> Images.packedBytes(1, 715_827_883));
        assertThrows(OcrException.class, () -> Images.packedBytes(-1, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new Bgr(new byte[8], Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> new Bgr(new byte[8], 1_800_000_000, 1_800_000_000));
        Bgr pixel = new Bgr(new byte[]{1, 2, 3}, 1, 1);
        OcrException resize = assertThrows(OcrException.class,
                () -> Images.resizeNormalize(pixel, 50_000, 20_000, 0.5f, 0.5f));
        assertTrue(resize.getMessage().contains("50000x20000"), resize.getMessage());
        OcrException padded = assertThrows(OcrException.class,
                () -> Images.recTensor(pixel, 32, 800_000_000, 800_000_000, 0.5f, 0.5f));
        assertTrue(padded.getMessage().contains("800000000x32"), padded.getMessage());
    }

    @Test
    @Timeout(2)
    void zeroSideDoesNotWalkTheLongEdge() {
        Bgr flat = new Bgr(new byte[0], Integer.MAX_VALUE, 0);
        Bgr ccw = Images.rot90ccw(flat);
        assertEquals(0, ccw.width());
        assertEquals(Integer.MAX_VALUE, ccw.height());
        assertEquals(0, ccw.data().length);
        Bgr cw = Images.rot90cw(flat);
        assertEquals(0, cw.width());
        assertEquals(Integer.MAX_VALUE, cw.height());
        Bgr half = Images.rot180(new Bgr(new byte[0], 0, Integer.MAX_VALUE));
        assertEquals(0, half.width());
        assertEquals(Integer.MAX_VALUE, half.height());
        assertSame(flat, Images.sauvola(flat));
        Bgr warped = Geometry.warp(flat, new float[]{0, 0, 1, 0, 1, 1, 0, 1}, 0, Integer.MAX_VALUE);
        if (warped != null) {
            assertEquals(0, warped.width());
            assertEquals(Integer.MAX_VALUE, warped.height());
            assertEquals(0, warped.data().length);
        }
    }

    @Test
    void deskewLeavesAnEmptyOrBlankImageUntouched() {
        Bgr empty = new Bgr(new byte[0], 0, 0);
        assertSame(empty, Images.deskew(empty));
        byte[] white = new byte[16 * 16 * 3];
        Arrays.fill(white, (byte) 255);
        Bgr blank = new Bgr(white, 16, 16);
        assertSame(blank, Images.deskew(blank));
    }

    @Test
    void emptySourceDoesNotIndexBeforeTheBuffer() {
        Bgr empty = new Bgr(new byte[0], 0, 0);
        OcrException resize = assertThrows(OcrException.class,
                () -> Images.resizeNormalize(empty, 4, 4, 0.5f, 0.5f));
        assertEquals("bad resize source 0x0", resize.getMessage());
        Bgr warped = Geometry.warp(empty, new float[]{0, 0, 2, 0, 2, 2, 0, 2}, 2, 2);
        assertNotNull(warped);
        assertArrayEquals(new byte[2 * 2 * 3], warped.data());
    }

    private static float[] oracle(byte[] src, int sw, int sh, int dw, int dh, float mean, float std) {
        float[] out = new float[3 * dw * dh];
        int plane = dw * dh;
        float scaleX = sw / (float) dw;
        float scaleY = sh / (float) dh;
        float invStd = 1f / std;
        for (int y = 0; y < dh; y++) {
            float sy = (y + 0.5f) * scaleY - 0.5f;
            int y0 = (int) Math.floor(sy);
            float fy = sy - y0;
            for (int x = 0; x < dw; x++) {
                float sx = (x + 0.5f) * scaleX - 0.5f;
                int x0 = (int) Math.floor(sx);
                float fx = sx - x0;
                for (int c = 0; c < 3; c++) {
                    float v = (1f - fy) * ((1f - fx) * at(src, sw, sh, x0, y0, c) + fx * at(src, sw, sh, x0 + 1, y0, c))
                            + fy * ((1f - fx) * at(src, sw, sh, x0, y0 + 1, c) + fx * at(src, sw, sh, x0 + 1, y0 + 1, c));
                    out[c * plane + y * dw + x] = (v / 255f - mean) * invStd;
                }
            }
        }
        return out;
    }

    private static float at(byte[] src, int w, int h, int x, int y, int c) {
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
}
