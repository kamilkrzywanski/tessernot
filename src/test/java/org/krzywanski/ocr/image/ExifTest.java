package org.krzywanski.ocr.image;

import org.junit.jupiter.api.Test;
import org.krzywanski.ocr.OcrException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExifTest {
    private static final int A = 0xff112233;
    private static final int B = 0xff445566;
    private static final int C = 0xff778899;
    private static final int D = 0xffaabbcc;
    private static final int E = 0xffddeeff;
    private static final int F = 0xff010203;

    @Test
    void orientationMovesPixelsAndRoundTrips() {
        BufferedImage src = image(2, 3, new int[]{A, B, C, D, E, F});
        assertSame(src, Exif.upright(src, 1));
        assertSame(src, Exif.upright(src, 0));
        assertSame(src, Exif.upright(src, 9));
        assertPixels(Exif.upright(src, 2), 2, 3, B, A, D, C, F, E);
        assertPixels(Exif.upright(src, 3), 2, 3, F, E, D, C, B, A);
        assertPixels(Exif.upright(src, 4), 2, 3, E, F, C, D, A, B);
        assertPixels(Exif.upright(src, 5), 3, 2, A, C, E, B, D, F);
        assertPixels(Exif.upright(src, 6), 3, 2, E, C, A, F, D, B);
        assertPixels(Exif.upright(src, 7), 3, 2, F, D, B, E, C, A);
        assertPixels(Exif.upright(src, 8), 3, 2, B, D, F, A, C, E);
        for (int orientation : new int[]{2, 3, 4, 5, 7}) {
            assertPixels(src, Exif.upright(Exif.upright(src, orientation), orientation));
        }
        assertPixels(src, Exif.upright(Exif.upright(src, 6), 8));
        assertPixels(src, Exif.upright(Exif.upright(src, 8), 6));
    }

    @Test
    void readsOrientationFromExifApp1() {
        assertEquals(6, Exif.orientation(jpeg(exif(6, true))));
        assertEquals(6, Exif.orientation(jpeg(exif(6, true, 4))));
        assertEquals(8, Exif.orientation(jpeg(exif(8, false))));
        assertEquals(1, Exif.orientation(jpeg(exif(1, true))));
        assertEquals(1, Exif.orientation(jpeg(exif(0, true))));
        assertEquals(1, Exif.orientation(jpeg(exif(9, true))));
        assertEquals(6, Exif.orientation(jpeg(jfif(), exif(6, true))));
        assertEquals(3, Exif.orientation(jpeg(xmp(), exif(3, false))));
        assertEquals(6, Exif.orientation(stuffed(exif(6, true))));
        assertEquals(1, Exif.orientation(new byte[]{(byte) 0xff, (byte) 0xd8}));
        assertEquals(1, Exif.orientation("not a jpeg".getBytes()));
        assertEquals(1, Exif.orientation(null));
        byte[] truncated = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe1, 0x00, 0x64, 0x00};
        assertEquals(1, Exif.orientation(truncated));
    }

    @Test
    void readTurnsATaggedJpegAndLeavesPngAlone() throws Exception {
        BufferedImage page = new BufferedImage(48, 24, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < page.getHeight(); y++) {
            for (int x = 0; x < page.getWidth(); x++) {
                page.setRGB(x, y, x < 24 ? 0xffff0000 : 0xff0000ff);
            }
        }
        byte[] encoded = jpegBytes(page);
        Path turned = Files.createTempFile("exif-turn", ".jpg");
        Path plain = Files.createTempFile("exif-plain", ".jpg");
        Path png = Files.createTempFile("exif-plain", ".png");
        try {
            Files.write(turned, insert(encoded, exif(6, true)));
            Files.write(plain, insert(encoded, exif(1, true)));
            BufferedImage upright = Images.read(turned);
            assertEquals(24, upright.getWidth());
            assertEquals(48, upright.getHeight());
            assertTrue(red(upright.getRGB(12, 6)), hex(upright.getRGB(12, 6)));
            assertTrue(blue(upright.getRGB(12, 40)), hex(upright.getRGB(12, 40)));
            BufferedImage same = Images.read(plain);
            assertEquals(48, same.getWidth());
            assertEquals(24, same.getHeight());
            assertTrue(red(same.getRGB(8, 12)), hex(same.getRGB(8, 12)));
            assertTrue(blue(same.getRGB(36, 12)), hex(same.getRGB(36, 12)));
            assertTrue(ImageIO.write(page, "png", png.toFile()));
            BufferedImage fromPng = Images.read(png);
            assertEquals(48, fromPng.getWidth());
            assertEquals(0xffff0000, fromPng.getRGB(0, 0));
            assertEquals(0xff0000ff, fromPng.getRGB(47, 23));
        } finally {
            Files.deleteIfExists(turned);
            Files.deleteIfExists(plain);
            Files.deleteIfExists(png);
        }
    }

    @Test
    void multiPageTiffReportsTheImageCount() throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("tiff");
        assumeTrue(writers.hasNext(), "no tiff writer");
        ImageWriter writer = writers.next();
        Path many = Files.createTempFile("ocr-tiff", ".tif");
        Path one = Files.createTempFile("ocr-tiff", ".tif");
        try {
            writeTiff(writer, many, 2);
            OcrException error = assertThrows(OcrException.class, () -> Images.read(many));
            assertTrue(error.getMessage().contains("2"), error.getMessage());
            assertTrue(error.getMessage().contains("images"), error.getMessage());
            writeTiff(writer, one, 1);
            BufferedImage read = Images.read(one);
            assertEquals(2, read.getWidth());
            assertEquals(2, read.getHeight());
        } finally {
            writer.dispose();
            Files.deleteIfExists(many);
            Files.deleteIfExists(one);
        }
    }

    private static void writeTiff(ImageWriter writer, Path path, int pages) throws IOException {
        try (ImageOutputStream out = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < pages; i++) {
                writer.writeToSequence(new IIOImage(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), null, null),
                        null);
            }
            writer.endWriteSequence();
        }
    }

    @Test
    void readRejectsMissingAndUndecodableFiles() throws Exception {
        OcrException missing = assertThrows(OcrException.class, () -> Images.read(null));
        assertTrue(missing.getMessage().contains("null"), missing.getMessage());
        OcrException absent = assertThrows(OcrException.class, () -> Images.read(Path.of("missing-ocr-pro.png")));
        assertTrue(absent.getMessage().contains("not a file"), absent.getMessage());
        Path dir = Files.createTempDirectory("ocr-not-image");
        try {
            OcrException directory = assertThrows(OcrException.class, () -> Images.read(dir));
            assertTrue(directory.getMessage().contains("not a file"), directory.getMessage());
        } finally {
            Files.deleteIfExists(dir);
        }
        Path garbage = Files.createTempFile("exif-bad", ".bin");
        try {
            Files.write(garbage, new byte[]{1, 2, 3, 4});
            assertThrows(OcrException.class, () -> Images.read(garbage));
        } finally {
            Files.deleteIfExists(garbage);
        }
    }

    private static void assertPixels(BufferedImage image, int width, int height, int... expected) {
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
        int[] actual = image.getRGB(0, 0, width, height, null, 0, width);
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "pixel " + i);
        }
    }

    private static void assertPixels(BufferedImage expected, BufferedImage actual) {
        int[] pixels = expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth());
        assertPixels(actual, expected.getWidth(), expected.getHeight(), pixels);
    }

    private static BufferedImage image(int width, int height, int[] pixels) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static byte[] exif(int orientation, boolean little) {
        return exif(orientation, little, 3);
    }

    private static byte[] exif(int orientation, boolean little, int type) {
        byte[] payload = new byte[32];
        payload[0] = 'E';
        payload[1] = 'x';
        payload[2] = 'i';
        payload[3] = 'f';
        if (little) {
            payload[6] = 'I';
            payload[7] = 'I';
        } else {
            payload[6] = 'M';
            payload[7] = 'M';
        }
        put16(payload, 8, 42, little);
        put32(payload, 10, 8, little);
        put16(payload, 14, 1, little);
        put16(payload, 16, 0x0112, little);
        put16(payload, 18, type, little);
        put32(payload, 20, 1, little);
        if (type == 4) {
            put32(payload, 24, orientation, little);
        } else {
            put16(payload, 24, orientation, little);
        }
        return payload;
    }

    private static byte[] jfif() {
        return new byte[]{'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
    }

    private static byte[] xmp() {
        byte[] header = "http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>".getBytes();
        return header;
    }

    private static byte[] jpeg(byte[]... segments) {
        int size = 4;
        for (byte[] segment : segments) {
            size += 4 + segment.length;
        }
        byte[] out = new byte[size];
        out[0] = (byte) 0xff;
        out[1] = (byte) 0xd8;
        int pos = 2;
        for (byte[] segment : segments) {
            int marker = segment[0] == 'E' || segment[0] == 'h' ? 0xe1 : 0xe0;
            pos = putSegment(out, pos, marker, segment);
        }
        out[pos] = (byte) 0xff;
        out[pos + 1] = (byte) 0xda;
        return out;
    }

    private static byte[] stuffed(byte[] exifPayload) {
        byte[] body = jpeg(exifPayload);
        byte[] out = new byte[body.length + 1];
        out[0] = body[0];
        out[1] = body[1];
        out[2] = (byte) 0xff;
        System.arraycopy(body, 2, out, 3, body.length - 2);
        return out;
    }

    private static int putSegment(byte[] out, int pos, int marker, byte[] payload) {
        int len = payload.length + 2;
        out[pos] = (byte) 0xff;
        out[pos + 1] = (byte) marker;
        out[pos + 2] = (byte) (len >> 8);
        out[pos + 3] = (byte) len;
        System.arraycopy(payload, 0, out, pos + 4, payload.length);
        return pos + 4 + payload.length;
    }

    private static byte[] insert(byte[] jpeg, byte[] exifPayload) {
        byte[] segment = new byte[4 + exifPayload.length];
        int len = exifPayload.length + 2;
        segment[0] = (byte) 0xff;
        segment[1] = (byte) 0xe1;
        segment[2] = (byte) (len >> 8);
        segment[3] = (byte) len;
        System.arraycopy(exifPayload, 0, segment, 4, exifPayload.length);
        byte[] out = new byte[jpeg.length + segment.length];
        out[0] = jpeg[0];
        out[1] = jpeg[1];
        System.arraycopy(segment, 0, out, 2, segment.length);
        System.arraycopy(jpeg, 2, out, 2 + segment.length, jpeg.length - 2);
        return out;
    }

    private static byte[] jpegBytes(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(1f);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static void put16(byte[] data, int index, int value, boolean little) {
        if (little) {
            data[index] = (byte) value;
            data[index + 1] = (byte) (value >> 8);
        } else {
            data[index] = (byte) (value >> 8);
            data[index + 1] = (byte) value;
        }
    }

    private static void put32(byte[] data, int index, int value, boolean little) {
        if (little) {
            data[index] = (byte) value;
            data[index + 1] = (byte) (value >> 8);
            data[index + 2] = (byte) (value >> 16);
            data[index + 3] = (byte) (value >> 24);
        } else {
            data[index] = (byte) (value >> 24);
            data[index + 1] = (byte) (value >> 16);
            data[index + 2] = (byte) (value >> 8);
            data[index + 3] = (byte) value;
        }
    }

    private static boolean red(int rgb) {
        return ((rgb >> 16) & 0xff) > 200 && ((rgb >> 8) & 0xff) < 48 && (rgb & 0xff) < 48;
    }

    private static boolean blue(int rgb) {
        return ((rgb >> 16) & 0xff) < 48 && ((rgb >> 8) & 0xff) < 48 && (rgb & 0xff) > 200;
    }

    private static String hex(int rgb) {
        return Integer.toHexString(rgb);
    }
}
