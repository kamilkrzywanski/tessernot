package io.github.kamilkrzywanski.ocr.cli;

import io.github.kamilkrzywanski.ocr.internal.pdf.PdfSamples;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CLI reads a PDF with the bundled models (no --model). */
class CliPdfTest {
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void cliPdfPrintsPageHeadersOnStdout() throws Exception {
        Path pdf = Files.createTempFile("cli-pages", ".pdf");
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        try {
            PdfSamples.writeAsciiPages(pdf, "FAKTURA JEDEN", "HELLO POLAND");
            System.setOut(new PrintStream(outBytes, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBytes, true, StandardCharsets.UTF_8));
            int code = Cli.execute(new String[]{"pdf", pdf.toString(), "--dpi", "120", "--threads", "2"});
            String text = outBytes.toString(StandardCharsets.UTF_8);
            String errors = errBytes.toString(StandardCharsets.UTF_8);
            assertEquals(0, code, errors);
            assertTrue(text.contains("--- page 1 ---"), text);
            assertTrue(text.contains("FAKTURA"), text);
            assertTrue(text.contains("--- page 2 ---"), text);
            assertTrue(text.contains("HELLO"), text);
            assertTrue(text.indexOf("--- page 1 ---") < text.indexOf("--- page 2 ---"), text);
            assertTrue(errors.contains("det "), errors);
            assertEquals(-1, errors.indexOf("--- page"));
        } finally {
            System.setOut(out);
            System.setErr(err);
            Files.deleteIfExists(pdf);
        }
    }
}
