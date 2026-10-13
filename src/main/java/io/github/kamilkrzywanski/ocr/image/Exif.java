package io.github.kamilkrzywanski.ocr.image;

import java.awt.image.BufferedImage;

/**
 * JPEG EXIF Orientation, values 1 through 8. Missing or unreadable tags stay at 1,
 * which leaves the pixels untouched. PNG and other files have no tag here.
 */
final class Exif {
    private Exif() {
    }

    static int orientation(byte[] data) {
        if (data == null || data.length < 4 || (data[0] & 0xff) != 0xff || (data[1] & 0xff) != 0xd8) {
            return 1;
        }
        int pos = 2;
        while (pos + 3 < data.length) {
            if ((data[pos] & 0xff) != 0xff) {
                return 1;
            }
            int markerAt = pos + 1;
            while (markerAt < data.length && (data[markerAt] & 0xff) == 0xff) {
                markerAt++;
            }
            if (markerAt >= data.length) {
                return 1;
            }
            int marker = data[markerAt] & 0xff;
            int header = markerAt + 1;
            if (marker == 0xda || marker == 0xd9) {
                return 1;
            }
            if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                pos = header;
                continue;
            }
            int segLen = u16(data, header, false);
            // header + segLen overflows when the buffer is larger than about 2 GiB.
            if (segLen < 2 || header > data.length || segLen > data.length - header) {
                return 1;
            }
            if (marker == 0xe1) {
                int value = app1(data, header + 2, segLen - 2);
                if (value > 0) {
                    return value;
                }
            }
            pos = header + segLen;
        }
        return 1;
    }

    /** Same instance when no turn or flip is required. */
    static BufferedImage upright(BufferedImage src, int orientation) {
        if (src == null || orientation <= 1 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
        boolean swapped = orientation >= 5;
        int dw = swapped ? h : w;
        int dh = swapped ? w : h;
        int[] out = new int[dw * dh];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx = switch (orientation) {
                    case 2, 3 -> w - 1 - x;
                    case 5, 8 -> y;
                    case 6, 7 -> h - 1 - y;
                    default -> x;
                };
                int dy = switch (orientation) {
                    case 3, 4 -> h - 1 - y;
                    case 5, 6 -> x;
                    case 7, 8 -> w - 1 - x;
                    default -> y;
                };
                out[dy * dw + dx] = pixels[y * w + x];
            }
        }
        BufferedImage dst = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
        dst.setRGB(0, 0, dw, dh, out, 0, dw);
        return dst;
    }

    /** Positive orientation, or 0 when this APP1 is not Exif. */
    private static int app1(byte[] data, int offset, int length) {
        if (length < 14 || offset < 0 || offset + length > data.length) {
            return 0;
        }
        if (data[offset] != 'E' || data[offset + 1] != 'x' || data[offset + 2] != 'i' || data[offset + 3] != 'f'
                || data[offset + 4] != 0 || data[offset + 5] != 0) {
            return 0;
        }
        int tiff = offset + 6;
        int remain = length - 6;
        boolean little;
        if (data[tiff] == 'I' && data[tiff + 1] == 'I') {
            little = true;
        } else if (data[tiff] == 'M' && data[tiff + 1] == 'M') {
            little = false;
        } else {
            return 0;
        }
        if (u16(data, tiff + 2, little) != 42) {
            return 0;
        }
        int ifdOff = u32(data, tiff + 4, little);
        if (ifdOff < 8 || ifdOff > remain - 2) {
            return 0;
        }
        int ifd = tiff + ifdOff;
        int entries = u16(data, ifd, little);
        if (entries < 0) {
            return 0;
        }
        long need = 2L + (long) entries * 12L;
        if (ifd + need > data.length) {
            return 0;
        }
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            int tag = u16(data, entry, little);
            int type = u16(data, entry + 2, little);
            int count = u32(data, entry + 4, little);
            if (tag == 0x0112 && count == 1 && (type == 3 || type == 4)) {
                int value = type == 3 ? u16(data, entry + 8, little) : u32(data, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] data, int index, boolean little) {
        if (index < 0 || index + 1 >= data.length) {
            return -1;
        }
        int a = data[index] & 0xff;
        int b = data[index + 1] & 0xff;
        return little ? (a | (b << 8)) : ((a << 8) | b);
    }

    private static int u32(byte[] data, int index, boolean little) {
        if (index < 0 || index + 3 >= data.length) {
            return -1;
        }
        int a = data[index] & 0xff;
        int b = data[index + 1] & 0xff;
        int c = data[index + 2] & 0xff;
        int d = data[index + 3] & 0xff;
        return little ? (a | (b << 8) | (c << 16) | (d << 24)) : ((a << 24) | (b << 16) | (c << 8) | d);
    }
}
