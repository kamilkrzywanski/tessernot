package io.github.kamilkrzywanski.ocr.internal.runtime;

import io.github.kamilkrzywanski.ocr.OcrException;

/** Minimal protobuf wire reader. Groups (wire 3/4) are rejected. */
final class Proto {
    final byte[] data;
    int pos;
    final int end;

    Proto(byte[] data, int pos, int end) {
        this.data = data;
        this.pos = pos;
        this.end = end;
    }

    boolean done() {
        return pos >= end;
    }

    long readVarint() {
        long result = 0;
        for (int shift = 0; shift <= 63; shift += 7) {
            if (pos >= end) {
                throw new OcrException("truncated varint");
            }
            int b = data[pos++] & 0xff;
            if (shift == 63 && (b & 0x7f) > 1) {
                throw new OcrException("varint overflow");
            }
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new OcrException("varint too long");
    }

    int readFixed32() {
        if (pos + 4 > end) {
            throw new OcrException("truncated fixed32");
        }
        int v = (data[pos] & 0xff)
                | ((data[pos + 1] & 0xff) << 8)
                | ((data[pos + 2] & 0xff) << 16)
                | ((data[pos + 3] & 0xff) << 24);
        pos += 4;
        return v;
    }

    long readFixed64() {
        long lo = readFixed32() & 0xffffffffL;
        long hi = readFixed32() & 0xffffffffL;
        return lo | (hi << 32);
    }

    float readFloat() {
        return Float.intBitsToFloat(readFixed32());
    }

    double readDouble() {
        return Double.longBitsToDouble(readFixed64());
    }

    byte[] readBytes() {
        int len = readLength();
        byte[] out = new byte[len];
        System.arraycopy(data, pos, out, 0, len);
        pos += len;
        return out;
    }

    String readString() {
        int len = readLength();
        String s = new String(data, pos, len, java.nio.charset.StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    Proto sub() {
        int len = readLength();
        Proto child = new Proto(data, pos, pos + len);
        pos += len;
        return child;
    }

    byte[] remainingBytes() {
        int len = end - pos;
        byte[] out = new byte[len];
        System.arraycopy(data, pos, out, 0, len);
        pos = end;
        return out;
    }

    void skip(int wire) {
        switch (wire) {
            case 0 -> readVarint();
            case 1 -> {
                if (pos + 8 > end) {
                    throw new OcrException("truncated fixed64");
                }
                pos += 8;
            }
            case 2 -> {
                int len = readLength();
                pos += len;
            }
            case 5 -> {
                if (pos + 4 > end) {
                    throw new OcrException("truncated fixed32");
                }
                pos += 4;
            }
            default -> throw new OcrException("unsupported wire type " + wire);
        }
        if (pos > end) {
            throw new OcrException("protobuf cursor past end");
        }
    }

    private int readLength() {
        long v = readVarint();
        if (v < 0 || v > Integer.MAX_VALUE || pos + v > end) {
            throw new OcrException("bad protobuf length " + v);
        }
        return (int) v;
    }
}
