package io.github.kamilkrzywanski.ocr.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;
import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.image.Images;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders PDF pages with PDFBox. The engine OCRs the bitmaps and does not read the text layer.
 * The file is read into memory once, so later rendering does not keep the path open.
 */
public final class PdfPages {
    private static final byte[] ENCRYPT = {'/', 'E', 'n', 'c', 'r', 'y', 'p', 't'};
    private static final byte[] STARTXREF = {'s', 't', 'a', 'r', 't', 'x', 'r', 'e', 'f'};
    /** Tail of the file, and the object {@code startxref} points at. */
    private static final int SCAN_WINDOW = 64 * 1024;

    private PdfPages() {
    }

    public static List<BufferedImage> render(Path pdf, float dpi) {
        try (Source source = open(pdf, dpi)) {
            List<BufferedImage> images = new ArrayList<>(source.pages());
            for (int i = 0; i < source.pages(); i++) {
                images.add(source.render(i));
            }
            return images;
        }
    }

    /** Rejects a missing path, and an image that was passed where a PDF belongs, before any render. */
    public static void requirePdf(Path pdf) {
        if (pdf == null) {
            throw new OcrException("pdf is null");
        }
        if (!Files.isRegularFile(pdf)) {
            throw new OcrException("cannot render " + pdf + ": not a file");
        }
        byte[] head = new byte[14];
        try (InputStream in = Files.newInputStream(pdf)) {
            int n = in.read(head);
            if (Images.hasImageHeader(head, n)) {
                throw new OcrException(pdf + " is an image; use ocr ocr");
            }
        } catch (IOException e) {
            throw new OcrException("cannot render " + pdf + ": " + e.getMessage(), e);
        }
    }

    /**
     * Rejects a user password before the caller loads a model.
     * An owner password with an empty user password still opens.
     * The scan reads the last 64 KiB and the object named by {@code startxref}.
     * A long cross-reference stream keeps {@code /Encrypt} in that object, above the tail.
     * The parse is skipped when neither place contains the marker.
     */
    public static void requireUnlocked(Path pdf) {
        requirePdf(pdf);
        if (!trailerMentionsEncrypt(pdf)) {
            return;
        }
        try {
            try (PDDocument document = Loader.loadPDF(Files.readAllBytes(pdf))) {
                // The empty password opened this file.
            }
        } catch (InvalidPasswordException e) {
            throw new OcrException(pdf + " is encrypted", e);
        } catch (IOException e) {
            throw new OcrException("cannot render " + pdf + ": " + e.getMessage(), e);
        }
    }

    /** Opens every page for rendering one at a time. Close when the last page has been rendered. */
    public static Source open(Path pdf, float dpi) {
        if (!Float.isFinite(dpi) || dpi <= 0f) {
            throw new OcrException("dpi must be positive");
        }
        requirePdf(pdf);
        try {
            byte[] bytes = Files.readAllBytes(pdf);
            PDDocument document = Loader.loadPDF(bytes);
            return new Source(document, dpi);
        } catch (InvalidPasswordException e) {
            throw new OcrException(pdf + " is encrypted", e);
        } catch (IOException e) {
            throw new OcrException("cannot render " + pdf + ": " + e.getMessage(), e);
        }
    }

    private static boolean trailerMentionsEncrypt(Path pdf) {
        try (SeekableByteChannel channel = Files.newByteChannel(pdf)) {
            long size = channel.size();
            int window = (int) Math.min(size, SCAN_WINDOW);
            if (window < ENCRYPT.length) {
                return false;
            }
            byte[] tail = readAt(channel, size - window, window);
            if (indexOf(tail, tail.length, ENCRYPT) >= 0) {
                return true;
            }
            long xref = startXref(tail);
            if (xref < 0 || xref >= size) {
                return false;
            }
            int xrefWindow = (int) Math.min(SCAN_WINDOW, size - xref);
            if (xrefWindow < ENCRYPT.length) {
                return false;
            }
            byte[] atXref = readAt(channel, xref, xrefWindow);
            return indexOf(atXref, atXref.length, ENCRYPT) >= 0;
        } catch (IOException e) {
            throw new OcrException("cannot render " + pdf + ": " + e.getMessage(), e);
        }
    }

    /** Last {@code startxref} offset in {@code tail}, or {@code -1} when the keyword has no number. */
    private static long startXref(byte[] tail) {
        int at = lastIndexOf(tail, tail.length, STARTXREF);
        if (at < 0) {
            return -1;
        }
        int i = at + STARTXREF.length;
        while (i < tail.length) {
            byte b = tail[i];
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == '\f' || b == 0) {
                i++;
                continue;
            }
            if (b == '%') {
                i++;
                while (i < tail.length && tail[i] != '\n' && tail[i] != '\r') {
                    i++;
                }
                continue;
            }
            break;
        }
        if (i >= tail.length || tail[i] < '0' || tail[i] > '9') {
            return -1;
        }
        long value = 0;
        while (i < tail.length && tail[i] >= '0' && tail[i] <= '9') {
            int digit = tail[i] - '0';
            if (value > (Long.MAX_VALUE - digit) / 10L) {
                return -1;
            }
            value = value * 10L + digit;
            i++;
        }
        return value;
    }

    private static byte[] readAt(SeekableByteChannel channel, long offset, int length) throws IOException {
        channel.position(offset);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                break;
            }
        }
        if (buffer.position() == length) {
            return buffer.array();
        }
        byte[] filled = new byte[buffer.position()];
        buffer.flip();
        buffer.get(filled);
        return filled;
    }

    private static int indexOf(byte[] data, int length, byte[] needle) {
        int last = length - needle.length;
        for (int i = 0; i <= last; i++) {
            if (matches(data, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(byte[] data, int length, byte[] needle) {
        int found = -1;
        int last = length - needle.length;
        for (int i = 0; i <= last; i++) {
            if (matches(data, i, needle)) {
                found = i;
            }
        }
        return found;
    }

    private static boolean matches(byte[] data, int offset, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (data[offset + j] != needle[j]) {
                return false;
            }
        }
        return true;
    }

    /** One PDF, rendered by the calling thread. PDFRenderer is not safe to share across threads. */
    public static final class Source implements AutoCloseable {
        private final PDDocument document;
        private final PDFRenderer renderer;
        private final float dpi;
        private boolean closed;

        private Source(PDDocument document, float dpi) {
            this.document = document;
            this.renderer = new PDFRenderer(document);
            this.dpi = dpi;
        }

        public int pages() {
            return document.getNumberOfPages();
        }

        public BufferedImage render(int index) {
            if (closed) {
                throw new OcrException("pdf is closed");
            }
            int count = document.getNumberOfPages();
            if (index < 0 || index >= count) {
                throw new OcrException("cannot render page " + (index + 1) + " of " + count);
            }
            try {
                return renderer.renderImageWithDPI(index, dpi);
            } catch (IOException | RuntimeException e) {
                if (e instanceof OcrException ocr) {
                    throw ocr;
                }
                throw new OcrException("cannot render page " + (index + 1) + ": " + e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                document.close();
            } catch (IOException e) {
                throw new OcrException("cannot close pdf: " + e.getMessage(), e);
            }
        }
    }
}
