package io.github.kamilkrzywanski.ocr.internal.eval;

import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.internal.image.Images;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Synthetic invoice shared by the CLI and the tests. The PDF samples are in tessernot-pdf ({@code PdfSamples}). */
public final class Samples {
    public static final List<String> INVOICE_LINES = List.of(
            "FAKTURA 2024/10",
            "Hello OCR Poland",
            "NIP 123-456-78-90",
            "Razem: 1 234,50 PLN",
            "Zażółć gęślą jaźń"
    );

    private static final String[] FONT_PATHS = {
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf"
    };

    private Samples() {
    }

    public static String invoiceText() {
        return String.join("\n", INVOICE_LINES);
    }

    public static BufferedImage invoice() {
        BufferedImage image = new BufferedImage(1200, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = Images.prepare(image);
        try {
            g.setFont(font(48f));
            int y = 120;
            for (String line : INVOICE_LINES) {
                g.drawString(line, 80, y);
                y += 100;
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    public static void writePng(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!ImageIO.write(invoice(), "png", path.toFile())) {
                throw new OcrException("no PNG writer");
            }
        } catch (IOException e) {
            throw new OcrException("cannot write " + path + ": " + e.getMessage(), e);
        }
    }

    public static Font font(float size) {
        for (String candidate : FONT_PATHS) {
            Path path = Path.of(candidate);
            if (!Files.exists(path)) {
                continue;
            }
            try {
                return Font.createFont(Font.TRUETYPE_FONT, path.toFile()).deriveFont(size);
            } catch (Exception ignored) {
                // Try the next face. SansSerif is the fallback below.
            }
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size));
    }
}
