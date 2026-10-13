package io.github.kamilkrzywanski.ocr.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.Samples;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfPagesTest {
    @Test
    void outOfRangePageNamesThePage() throws Exception {
        Path pdf = Files.createTempFile("ocr-range", ".pdf");
        try {
            Samples.writeAsciiPages(pdf, "HELLO");
            try (PdfPages.Source source = PdfPages.open(pdf, 72f)) {
                assertEquals(1, source.pages());
                OcrException error = assertThrows(OcrException.class, () -> source.render(1));
                assertEquals("cannot render page 2 of 1", error.getMessage());
                OcrException negative = assertThrows(OcrException.class, () -> source.render(-1));
                assertEquals("cannot render page 0 of 1", negative.getMessage());
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void rejectsANonPositiveDpiWithoutReading() {
        Path missing = Path.of("missing-ocr-pro.pdf");
        assertThrows(OcrException.class, () -> PdfPages.render(missing, 0f));
        assertThrows(OcrException.class, () -> PdfPages.render(missing, -1f));
        assertThrows(OcrException.class, () -> PdfPages.render(missing, Float.NaN));
        assertThrows(OcrException.class, () -> PdfPages.open(null, 72f));
    }

    @Test
    void imageIsNotOpenedAsPdf() throws Exception {
        Path png = Files.createTempFile("ocr-page", ".png");
        Path jpg = Files.createTempFile("ocr-page", ".jpg");
        Path gif = Files.createTempFile("ocr-page", ".gif");
        Path bmp = Files.createTempFile("ocr-page", ".bmp");
        Path tiff = Files.createTempFile("ocr-page", ".tif");
        Path leTiff = Files.createTempFile("ocr-page", ".tif");
        Path notBmp = Files.createTempFile("ocr-page", ".bin");
        try {
            BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            assertTrue(ImageIO.write(image, "png", png.toFile()));
            assertImage(png);
            Files.write(jpg, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00});
            assertImageHeader(jpg);
            assertTrue(ImageIO.write(image, "gif", gif.toFile()));
            assertImage(gif);
            assertTrue(ImageIO.write(image, "bmp", bmp.toFile()));
            assertImage(bmp);
            assertTrue(ImageIO.write(image, "tiff", tiff.toFile()));
            assertImage(tiff);
            Files.write(leTiff, new byte[]{'I', 'I', 42, 0});
            assertImageHeader(leTiff);
            Files.write(notBmp, new byte[]{'B', 'M', 0, 0, 0, 0, 1, 0, 0, 0});
            PdfPages.requirePdf(notBmp);
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(jpg);
            Files.deleteIfExists(gif);
            Files.deleteIfExists(bmp);
            Files.deleteIfExists(tiff);
            Files.deleteIfExists(leTiff);
            Files.deleteIfExists(notBmp);
        }
    }

    @Test
    void encryptedPdfNeedsAPassword() throws Exception {
        Path locked = Files.createTempFile("ocr-locked", ".pdf");
        Path ownerOnly = Files.createTempFile("ocr-owner", ".pdf");
        try {
            writeProtected(locked, "owner-secret", "user-secret");
            OcrException lockedEarly = assertThrows(OcrException.class, () -> PdfPages.requireUnlocked(locked));
            assertTrue(lockedEarly.getMessage().contains("encrypted"), lockedEarly.getMessage());
            OcrException error = assertThrows(OcrException.class, () -> PdfPages.open(locked, 72f));
            assertTrue(error.getMessage().contains("encrypted"), error.getMessage());
            assertTrue(error.getMessage().contains(locked.getFileName().toString()), error.getMessage());
            assertTrue(error.getCause() instanceof InvalidPasswordException);

            writeProtected(ownerOnly, "owner-secret", "");
            PdfPages.requireUnlocked(ownerOnly);
            try (PdfPages.Source source = PdfPages.open(ownerOnly, 72f)) {
                assertEquals(1, source.pages());
            }
        } finally {
            Files.deleteIfExists(locked);
            Files.deleteIfExists(ownerOnly);
        }
    }

    @Test
    void encryptedMarkerAboveTheTailStillStopsEarly() throws Exception {
        Path locked = Files.createTempFile("ocr-locked-long", ".pdf");
        Path ownerOnly = Files.createTempFile("ocr-owner-long", ".pdf");
        Path plain = Files.createTempFile("ocr-plain-long", ".pdf");
        try {
            writeProtected(locked, "owner-secret", "user-secret");
            byte[] marker = "startxref".getBytes(StandardCharsets.US_ASCII);
            padBeforeLast(locked, marker, 80 * 1024);
            insertAfterLast(locked, marker, "\n% note".getBytes(StandardCharsets.US_ASCII));
            assertTrue(markerSitsAboveTheTail(locked, "/Encrypt"));
            OcrException error = assertThrows(OcrException.class, () -> PdfPages.requireUnlocked(locked));
            assertTrue(error.getMessage().contains("encrypted"), error.getMessage());
            assertTrue(error.getCause() instanceof InvalidPasswordException);

            writeProtected(ownerOnly, "owner-secret", "");
            padBeforeLast(ownerOnly, "startxref".getBytes(StandardCharsets.US_ASCII), 80 * 1024);
            assertTrue(markerSitsAboveTheTail(ownerOnly, "/Encrypt"));
            PdfPages.requireUnlocked(ownerOnly);
            try (PdfPages.Source source = PdfPages.open(ownerOnly, 72f)) {
                assertEquals(1, source.pages());
            }

            Samples.writeAsciiPages(plain, "HELLO");
            padBeforeLast(plain, "startxref".getBytes(StandardCharsets.US_ASCII), 80 * 1024);
            PdfPages.requireUnlocked(plain);
            try (PdfPages.Source source = PdfPages.open(plain, 72f)) {
                assertEquals(1, source.pages());
            }
        } finally {
            Files.deleteIfExists(locked);
            Files.deleteIfExists(ownerOnly);
            Files.deleteIfExists(plain);
        }
    }

    @Test
    void encryptWordWithoutAPasswordIsARenderError() throws Exception {
        Path junk = Files.createTempFile("ocr-encrypt-word", ".pdf");
        try {
            Files.writeString(junk, "%PDF-1.4\n/Encrypt\nstartxref\n9\n%%EOF\n");
            OcrException error = assertThrows(OcrException.class, () -> PdfPages.requireUnlocked(junk));
            assertTrue(error.getMessage().contains("cannot render"), error.getMessage());
            assertTrue(!error.getMessage().contains("encrypted"), error.getMessage());
        } finally {
            Files.deleteIfExists(junk);
        }
    }

    @Test
    void brokenPdfStaysARenderError() throws Exception {
        Path junk = Files.createTempFile("ocr-junk", ".pdf");
        try {
            Files.writeString(junk, "%PDF-1.4\nnot a pdf\n");
            OcrException error = assertThrows(OcrException.class, () -> PdfPages.open(junk, 72f));
            assertTrue(error.getMessage().contains("cannot render"), error.getMessage());
            assertTrue(!error.getMessage().contains("encrypted"), error.getMessage());
        } finally {
            Files.deleteIfExists(junk);
        }
    }

    @Test
    void missingFileIsAnOcrException() {
        Path missing = Path.of("missing-ocr-pro.pdf");
        OcrException error = assertThrows(OcrException.class, () -> PdfPages.render(missing, 72f));
        assertTrue(error.getMessage().contains("missing-ocr-pro.pdf"), error.getMessage());
    }

    @Test
    void emptyPdfHasNoPages() throws Exception {
        Path pdf = Files.createTempFile("empty-ocr", ".pdf");
        try {
            Samples.writeAsciiPages(pdf);
            assertEquals(List.of(), PdfPages.render(pdf, 72f));
            try (PdfPages.Source source = PdfPages.open(pdf, 72f)) {
                assertEquals(0, source.pages());
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void pagesRenderOneAtATime() throws Exception {
        Path pdf = Files.createTempFile("pages-ocr", ".pdf");
        try {
            Samples.writeAsciiPages(pdf, "FAKTURA 2024", "NIP 1234567890");
            List<BufferedImage> all = PdfPages.render(pdf, 72f);
            assertEquals(2, all.size());
            assertTrue(hasInk(all.get(0)));
            assertTrue(hasInk(all.get(1)));
            try (PdfPages.Source source = PdfPages.open(pdf, 72f)) {
                assertEquals(2, source.pages());
                BufferedImage first = source.render(0);
                BufferedImage second = source.render(1);
                assertEquals(all.get(0).getWidth(), first.getWidth());
                assertEquals(all.get(0).getHeight(), first.getHeight());
                assertEquals(all.get(1).getWidth(), second.getWidth());
                assertEquals(all.get(1).getHeight(), second.getHeight());
                source.close();
                assertThrows(OcrException.class, () -> source.render(0));
                source.close();
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    private static void assertImage(Path path) {
        OcrException error = assertThrows(OcrException.class, () -> PdfPages.open(path, 72f));
        assertTrue(error.getMessage().contains("ocr ocr"), error.getMessage());
    }

    private static void assertImageHeader(Path path) {
        OcrException error = assertThrows(OcrException.class, () -> PdfPages.requirePdf(path));
        assertTrue(error.getMessage().contains("ocr ocr"), error.getMessage());
    }

    /** Inserts {@code extra} immediately after the last {@code marker}. Offsets before that marker stay put. */
    private static void insertAfterLast(Path path, byte[] marker, byte[] extra) throws Exception {
        byte[] data = Files.readAllBytes(path);
        int at = last(data, marker);
        assertTrue(at >= 0, new String(marker, StandardCharsets.US_ASCII));
        int pos = at + marker.length;
        byte[] out = new byte[data.length + extra.length];
        System.arraycopy(data, 0, out, 0, pos);
        System.arraycopy(extra, 0, out, pos, extra.length);
        System.arraycopy(data, pos, out, pos + extra.length, data.length - pos);
        Files.write(path, out);
    }

    /** Inserts {@code padding} spaces in front of the last {@code marker}, leaving earlier offsets intact. */
    private static void padBeforeLast(Path path, byte[] marker, int padding) throws Exception {
        byte[] data = Files.readAllBytes(path);
        int at = last(data, marker);
        assertTrue(at >= 0, new String(marker, StandardCharsets.US_ASCII));
        byte[] padded = new byte[data.length + padding];
        System.arraycopy(data, 0, padded, 0, at);
        Arrays.fill(padded, at, at + padding, (byte) ' ');
        System.arraycopy(data, at, padded, at + padding, data.length - at);
        Files.write(path, padded);
    }

    /** The marker is in the file and outside the last 64 KiB, so a tail-only scan would miss it. */
    private static boolean markerSitsAboveTheTail(Path path, String marker) throws Exception {
        byte[] data = Files.readAllBytes(path);
        int at = last(data, marker.getBytes(StandardCharsets.US_ASCII));
        return at >= 0 && data.length - at > 64 * 1024;
    }

    private static int last(byte[] data, byte[] needle) {
        int found = -1;
        for (int i = 0; i + needle.length <= data.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                found = i;
            }
        }
        return found;
    }

    private static void writeProtected(Path path, String ownerPassword, String userPassword) throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A5));
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(ownerPassword, userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(path.toFile());
        }
    }

    private static boolean hasInk(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xffffff) != 0xffffff) {
                    return true;
                }
            }
        }
        return false;
    }
}
