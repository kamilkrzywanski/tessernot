package io.github.kamilkrzywanski.ocr.internal.pdf;

import io.github.kamilkrzywanski.ocr.OcrException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Synthetic PDFs for the tests of this module and the CLI. Helvetica has no Polish glyphs, so they are ASCII. */
public final class PdfSamples {
    private PdfSamples() {
    }

    /** One A5 page per line. Standard Helvetica, ASCII only. An empty list writes a PDF with no pages. */
    public static void writeAsciiPages(Path path, String... lines) {
        try (PDDocument document = new PDDocument()) {
            for (String line : lines) {
                PDPage page = new PDPage(PDRectangle.A5);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                    stream.newLineAtOffset(50, 480);
                    stream.showText(line);
                    stream.endText();
                }
            }
            writePdf(document, path);
        } catch (IOException e) {
            throw new OcrException("cannot write " + path + ": " + e.getMessage(), e);
        }
    }

    /** A5 page. Standard Helvetica, ASCII only. */
    public static void writeAsciiPdf(Path path) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A5);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                stream.newLineAtOffset(50, 480);
                stream.showText("FAKTURA 2024");
                stream.newLineAtOffset(0, -40);
                stream.showText("NIP 1234567890");
                stream.endText();
            }
            writePdf(document, path);
        } catch (IOException e) {
            throw new OcrException("cannot write " + path + ": " + e.getMessage(), e);
        }
    }

    private static void writePdf(PDDocument document, Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        document.save(path.toFile());
    }
}
