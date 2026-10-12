package org.krzywanski.ocr;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.krzywanski.ocr.image.Images;
import org.krzywanski.ocr.pdf.PdfPages;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliArgsTest {
    @Test
    void helpAndUnknownCommands() {
        Run help = run(() -> Cli.execute(new String[]{"help"}));
        assertEquals(0, help.code);
        assertTrue(help.err.contains(
                "ocr pdf <file> [--dpi N] [--model dir] [--limit N] [--no-cls] [--no-det] [--threads N]"), help.err);
        assertTrue(help.err.contains("--no-det"), help.err);
        assertTrue(help.err.contains("EXIF"), help.err);
        assertTrue(help.err.contains("--- page N ---"), help.err);
        assertTrue(help.err.contains("encrypted PDF"), help.err);
        assertTrue(help.err.contains("more than one image"), help.err);
        assertTrue(help.err.contains("repeated flag"), help.err);
        assertTrue(help.err.contains("empty --model="), help.err);
        assertTrue(help.err.contains("before the image is read"), help.err);
        assertTrue(help.err.contains("empty model file"), help.err);
        assertTrue(help.err.contains("does not use"), help.err);

        Run empty = run(() -> Cli.execute(new String[0]));
        assertEquals(2, empty.code);

        Run unknown = run(() -> Cli.execute(new String[]{"nope"}));
        assertEquals(2, unknown.code);
    }

    @Test
    void selfcheckPrintsTheScalarOrVectorDot() {
        Run check = run(() -> Cli.execute(new String[]{"selfcheck"}));
        assertEquals(0, check.code, check.out + check.err);
        assertTrue(check.out.contains("y=11.000"), check.out);

        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("pl-PL"));
        try {
            Run polish = run(() -> Cli.execute(new String[]{"selfcheck"}));
            assertEquals(0, polish.code, polish.out + polish.err);
            assertTrue(polish.out.contains("y=11.000"), polish.out);
            assertFalse(polish.out.contains("y=11,000"), polish.out);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void badNumbersNameTheFlagAndDoNotOpenAModel() {
        Run dpi = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--dpi", "nope"}));
        assertEquals(1, dpi.code);
        assertTrue(dpi.err.contains("--dpi"), dpi.err);

        Run threads = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--threads", "many"}));
        assertEquals(1, threads.code);
        assertTrue(threads.err.contains("--threads"), threads.err);

        Run limit = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--limit", "wide"}));
        assertEquals(1, limit.code);
        assertTrue(limit.err.contains("--limit"), limit.err);

        Run dpiEquals = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--dpi=nope"}));
        assertEquals(1, dpiEquals.code);
        assertTrue(dpiEquals.err.contains("--dpi"), dpiEquals.err);
        assertFalse(dpiEquals.err.contains("cannot render"), dpiEquals.err);

        Run eval = run(() -> Cli.execute(new String[]{"eval", "--threads", "nope"}));
        assertEquals(1, eval.code);
        assertTrue(eval.err.contains("--threads"), eval.err);
    }

    @Test
    void nonPositiveNumbersNameTheFlagBeforeAnyFile() {
        Run dpi = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--dpi", "0"}));
        assertEquals(1, dpi.code);
        assertTrue(dpi.err.contains("--dpi"), dpi.err);
        assertFalse(dpi.err.contains("cannot render"), dpi.err);

        Run negativeDpi = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--dpi", "-2"}));
        assertEquals(1, negativeDpi.code);
        assertTrue(negativeDpi.err.contains("--dpi"), negativeDpi.err);

        Run dpiEquals = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--dpi=0"}));
        assertEquals(1, dpiEquals.code);
        assertTrue(dpiEquals.err.contains("--dpi"), dpiEquals.err);
        assertFalse(dpiEquals.err.contains("cannot render"), dpiEquals.err);

        Run bareEquals = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--=12"}));
        assertEquals(1, bareEquals.code);
        assertTrue(bareEquals.err.contains("bad flag"), bareEquals.err);

        Run typo = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--thread", "4"}));
        assertEquals(1, typo.code);
        assertTrue(typo.err.contains("unknown flag --thread"), typo.err);
        assertFalse(typo.err.contains("cannot render"), typo.err);

        Run bareModel = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--model"}));
        assertEquals(1, bareModel.code);
        assertTrue(bareModel.err.contains("--model needs a value"), bareModel.err);
        assertFalse(bareModel.err.contains("cannot read"), bareModel.err);

        Run bareDpi = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--dpi"}));
        assertEquals(1, bareDpi.code);
        assertTrue(bareDpi.err.contains("--dpi needs a value"), bareDpi.err);
        assertFalse(bareDpi.err.contains("cannot render"), bareDpi.err);

        Run threads = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--threads", "0"}));
        assertEquals(1, threads.code);
        assertTrue(threads.err.contains("--threads"), threads.err);
        assertFalse(threads.err.contains("cannot render"), threads.err);

        Run limit = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--limit", "-5"}));
        assertEquals(1, limit.code);
        assertTrue(limit.err.contains("--limit"), limit.err);
        assertFalse(limit.err.contains("cannot read"), limit.err);

        Run stillReads = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--limit", "32"}));
        assertEquals(1, stillReads.code);
        assertTrue(stillReads.err.contains("cannot read"), stillReads.err);

        Run eval = run(() -> Cli.execute(new String[]{"eval", "--threads", "-3"}));
        assertEquals(1, eval.code);
        assertTrue(eval.err.contains("--threads"), eval.err);
        assertFalse(eval.err.contains("missing"), eval.err);
    }

    @Test
    void switchesDoNotSwallowTheNextArgument() throws Exception {
        assertTrue(Cli.enabled(new String[]{"ocr", "--no-cls"}, "no-cls"));
        assertTrue(Cli.enabled(new String[]{"ocr", "--no-cls=TRUE"}, "no-cls"));
        assertFalse(Cli.enabled(new String[]{"ocr", "--no-cls=false"}, "no-cls"));
        assertFalse(Cli.enabled(new String[]{"ocr", "--no-det=false", "--no-cls"}, "no-det"));
        assertTrue(Cli.enabled(new String[]{"ops", "--raw", "model.onnx"}, "raw"));

        Path dir = Files.createTempDirectory("ocr-switch");
        Path png = dir.resolve("page.png");
        Path model = dir.resolve("missing.onnx");
        try {
            assertTrue(ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png.toFile()));
            Run cls = run(() -> Cli.execute(new String[]{
                    "ocr", "--no-cls", png.toString(), "--model", dir.toString()}));
            assertEquals(1, cls.code);
            assertTrue(cls.err.contains("detection model"), cls.err);
            assertFalse(cls.err.contains("cannot read"), cls.err);

            Run det = run(() -> Cli.execute(new String[]{
                    "ocr", "--no-det", png.toString(), "--model", dir.toString()}));
            assertEquals(1, det.code);
            assertTrue(det.err.contains("recognition model"), det.err);
            assertFalse(det.err.contains("detection model"), det.err);

            Run limited = run(() -> Cli.execute(new String[]{
                    "ocr", "--limit", "32", "--no-cls", png.toString(), "--model", dir.toString()}));
            assertEquals(1, limited.code);
            assertTrue(limited.err.contains("detection model"), limited.err);

            Run raw = run(() -> Cli.execute(new String[]{"ops", "--raw", model.toString()}));
            assertEquals(1, raw.code);
            assertTrue(raw.err.contains("missing.onnx"), raw.err);
            assertFalse(raw.err.contains("usage:"), raw.err);

            Run bad = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--no-cls=maybe"}));
            assertEquals(1, bad.code);
            assertTrue(bad.err.contains("true or false"), bad.err);
            assertFalse(bad.err.contains("cannot read"), bad.err);

            Run pdf = run(() -> Cli.execute(new String[]{
                    "pdf", "--no-det", dir.resolve("missing.pdf").toString()}));
            assertEquals(1, pdf.code);
            assertTrue(pdf.err.contains("missing.pdf"), pdf.err);
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void pdfGivenToOcrNamesThePdfCommand() throws Exception {
        Path pdf = Files.createTempFile("ocr-not-image", ".pdf");
        try {
            Files.writeString(pdf, "%PDF-1.4\n");
            Run run = run(() -> Cli.execute(new String[]{"ocr", pdf.toString(), "--limit", "960"}));
            assertEquals(1, run.code);
            assertTrue(run.err.contains("ocr pdf"), run.err);
            assertFalse(run.err.contains("detection model"), run.err);

            Files.write(pdf, new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '%', 'P', 'D', 'F', '-', '1'});
            Run bom = run(() -> Cli.execute(new String[]{"ocr", pdf.toString(), "--limit", "960"}));
            assertEquals(1, bom.code);
            assertTrue(bom.err.contains("ocr pdf"), bom.err);
            assertFalse(bom.err.contains("detection model"), bom.err);

            byte[] marker = "%PDF-".getBytes(StandardCharsets.US_ASCII);
            byte[] atEdge = new byte[1024];
            Arrays.fill(atEdge, (byte) 'x');
            System.arraycopy(marker, 0, atEdge, 1019, marker.length);
            Files.write(pdf, atEdge);
            OcrException edge = assertThrows(OcrException.class, () -> Images.read(pdf));
            assertTrue(edge.getMessage().contains("ocr pdf"), edge.getMessage());

            byte[] pastWindow = new byte[1030];
            Arrays.fill(pastWindow, (byte) 'x');
            System.arraycopy(marker, 0, pastWindow, 1024, marker.length);
            Files.write(pdf, pastWindow);
            OcrException late = assertThrows(OcrException.class, () -> Images.read(pdf));
            assertFalse(late.getMessage().contains("ocr pdf"), late.getMessage());
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    @Test
    void imageWithPdfMarkerStaysAnImage() throws Exception {
        Path png = Files.createTempFile("ocr-pdf-text", ".png");
        Path jpg = Files.createTempFile("ocr-pdf-text", ".jpg");
        try {
            BufferedImage page = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            page.setRGB(0, 0, 0xffff0000);
            byte[] pngBytes = pngWithPdfMarker(page);
            int pngAt = indexOf(pngBytes, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            assertTrue(pngAt >= 0 && pngAt < 1024, "marker at " + pngAt);
            Files.write(png, pngBytes);
            BufferedImage fromPng = Images.read(png);
            assertEquals(2, fromPng.getWidth());
            assertEquals(2, fromPng.getHeight());
            assertEquals(0xffff0000, fromPng.getRGB(0, 0));
            OcrException pngAsPdf = assertThrows(OcrException.class, () -> PdfPages.requirePdf(png));
            assertTrue(pngAsPdf.getMessage().contains("ocr ocr"), pngAsPdf.getMessage());

            byte[] jpgBytes = jpegWithPdfMarker(page);
            int jpgAt = indexOf(jpgBytes, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            assertTrue(jpgAt >= 0 && jpgAt < 1024, "marker at " + jpgAt);
            Files.write(jpg, jpgBytes);
            BufferedImage fromJpg = Images.read(jpg);
            assertEquals(2, fromJpg.getWidth());
            assertEquals(2, fromJpg.getHeight());
            OcrException jpgAsPdf = assertThrows(OcrException.class, () -> PdfPages.requirePdf(jpg));
            assertTrue(jpgAsPdf.getMessage().contains("ocr ocr"), jpgAsPdf.getMessage());
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(jpg);
        }
    }

    @Test
    void imageGivenToPdfNamesTheOcrCommandBeforeModels() throws Exception {
        Path dir = Files.createTempDirectory("ocr-nomodel-pdf");
        Path png = dir.resolve("page.png");
        try {
            assertTrue(ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", png.toFile()));
            Run image = run(() -> Cli.execute(new String[]{"pdf", png.toString(), "--model", dir.toString()}));
            assertEquals(1, image.code);
            assertTrue(image.err.contains("ocr ocr"), image.err);
            assertFalse(image.err.contains("detection model"), image.err);

            Run missing = run(() -> Cli.execute(new String[]{
                    "pdf", dir.resolve("missing.pdf").toString(), "--model", dir.toString(), "--dpi", "72"}));
            assertEquals(1, missing.code);
            assertTrue(missing.err.contains("missing.pdf"), missing.err);
            assertFalse(missing.err.contains("detection model"), missing.err);
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void encryptedPdfFailsBeforeModels() throws Exception {
        Path dir = Files.createTempDirectory("ocr-nomodel-lock");
        Path pdf = dir.resolve("locked.pdf");
        try {
            try (PDDocument document = new PDDocument()) {
                document.addPage(new PDPage(PDRectangle.A5));
                StandardProtectionPolicy policy =
                        new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
                document.save(pdf.toFile());
            }
            Run run = run(() -> Cli.execute(new String[]{"pdf", pdf.toString(), "--model", dir.toString(), "--dpi", "72"}));
            assertEquals(1, run.code);
            assertTrue(run.err.contains("encrypted"), run.err);
            assertFalse(run.err.contains("detection model"), run.err);
        } finally {
            Files.deleteIfExists(pdf);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void blankModelAndExtraArgumentsFailBeforeAnyFile() throws Exception {
        Run blank = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--model="}));
        assertEquals(1, blank.code);
        assertTrue(blank.err.contains("--model needs a value"), blank.err);
        assertFalse(blank.err.contains("cannot read"), blank.err);

        Run spaces = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--model", " ", "--dpi", "72"}));
        assertEquals(1, spaces.code);
        assertTrue(spaces.err.contains("--model needs a value"), spaces.err);
        assertFalse(spaces.err.contains("cannot render"), spaces.err);

        Run download = run(() -> Cli.execute(new String[]{"download", ""}));
        assertEquals(1, download.code);
        assertTrue(download.err.contains("model directory is empty"), download.err);
        assertFalse(download.err.contains("cannot prepare"), download.err);

        OcrException latin = assertThrows(OcrException.class, () -> OcrConfig.latin(Path.of("")));
        assertTrue(latin.getMessage().contains("model directory is empty"), latin.getMessage());

        Run extra = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "also.png"}));
        assertEquals(1, extra.code);
        assertTrue(extra.err.contains("unexpected argument also.png"), extra.err);
        assertFalse(extra.err.contains("cannot read"), extra.err);

        Run selfcheck = run(() -> Cli.execute(new String[]{"selfcheck", "now"}));
        assertEquals(1, selfcheck.code);
        assertTrue(selfcheck.err.contains("unexpected argument now"), selfcheck.err);
        assertFalse(selfcheck.out.contains("vector="), selfcheck.out);

        Run eval = run(() -> Cli.execute(new String[]{"eval", "invoice.png"}));
        assertEquals(1, eval.code);
        assertTrue(eval.err.contains("unexpected argument invoice.png"), eval.err);
        assertFalse(eval.out.contains("CER"), eval.out);

        Run help = run(() -> Cli.execute(new String[]{"help", "ocr"}));
        assertEquals(1, help.code);
        assertTrue(help.err.contains("unexpected argument ocr"), help.err);
        assertFalse(help.err.contains("ocr-pro"), help.err);

        Run convert = run(() -> Cli.execute(new String[]{"convert", "in.onnx", "out.jir", "extra"}));
        assertEquals(1, convert.code);
        assertTrue(convert.err.contains("unexpected argument extra"), convert.err);
        assertFalse(convert.err.contains("cannot load"), convert.err);

        Run ops = run(() -> Cli.execute(new String[]{"ops", "missing.onnx", "extra"}));
        assertEquals(1, ops.code);
        assertTrue(ops.err.contains("unexpected argument extra"), ops.err);
        assertFalse(ops.err.contains("cannot load"), ops.err);

        Run twice = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--limit", "32", "--limit", "64"}));
        assertEquals(1, twice.code);
        assertTrue(twice.err.contains("duplicate flag --limit"), twice.err);
        assertFalse(twice.err.contains("cannot read"), twice.err);

        Run dpiOnOcr = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--dpi", "72"}));
        assertEquals(1, dpiOnOcr.code);
        assertTrue(dpiOnOcr.err.contains("--dpi is not used by ocr"), dpiOnOcr.err);
        assertFalse(dpiOnOcr.err.contains("cannot read"), dpiOnOcr.err);

        Run rawOnPdf = run(() -> Cli.execute(new String[]{"pdf", "missing.pdf", "--raw"}));
        assertEquals(1, rawOnPdf.code);
        assertTrue(rawOnPdf.err.contains("--raw is not used by pdf"), rawOnPdf.err);
        assertFalse(rawOnPdf.err.contains("cannot render"), rawOnPdf.err);

        Run dpiOnEval = run(() -> Cli.execute(new String[]{"eval", "--dpi", "72"}));
        assertEquals(1, dpiOnEval.code);
        assertTrue(dpiOnEval.err.contains("--dpi is not used by eval"), dpiOnEval.err);
        assertFalse(dpiOnEval.out.contains("CER"), dpiOnEval.out);

        Run modelOnCheck = run(() -> Cli.execute(new String[]{"selfcheck", "--model", "models"}));
        assertEquals(1, modelOnCheck.code);
        assertTrue(modelOnCheck.err.contains("--model is not used by selfcheck"), modelOnCheck.err);
        assertFalse(modelOnCheck.out.contains("vector="), modelOnCheck.out);

        Run twiceSwitch = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--no-cls", "--no-cls=false"}));
        assertEquals(1, twiceSwitch.code);
        assertTrue(twiceSwitch.err.contains("duplicate flag --no-cls"), twiceSwitch.err);
        assertFalse(twiceSwitch.err.contains("cannot read"), twiceSwitch.err);
    }

    @Test
    void validWeightDropsALeftoverPartFile() throws Exception {
        Path dir = Files.createTempDirectory("ocr-part");
        Path dest = dir.resolve("weight.bin");
        Path part = dir.resolve("weight.bin.part");
        try {
            Files.write(dest, new byte[]{1, 2, 3});
            Files.write(part, new byte[]{9});
            assertTrue(ModelFiles.alreadyValid(dest, ModelFiles.sha256(dest)));
            assertFalse(Files.exists(part));
            assertEquals(3, Files.size(dest));

            Files.write(part, new byte[]{9});
            assertFalse(ModelFiles.alreadyValid(dest, "00"));
            assertTrue(Files.exists(part));
            assertEquals(3, Files.size(dest));

            assertFalse(ModelFiles.alreadyValid(dir.resolve("missing.bin"), ModelFiles.sha256(dest)));
            assertTrue(Files.exists(part));
        } finally {
            Files.deleteIfExists(part);
            Files.deleteIfExists(dest);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void missingModelsAreReportedBeforeAMissingImage() throws Exception {
        Path dir = Files.createTempDirectory("ocr-models-first");
        try {
            Run ocr = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--model", dir.toString()}));
            assertEquals(1, ocr.code);
            assertTrue(ocr.err.contains("detection model"), ocr.err);
            assertTrue(ocr.err.contains("ocr download"), ocr.err);
            assertFalse(ocr.err.contains("cannot read"), ocr.err);

            Run bench = run(() -> Cli.execute(new String[]{"bench", "missing.png", "--model", dir.toString()}));
            assertEquals(1, bench.code);
            assertTrue(bench.err.contains("detection model"), bench.err);
            assertFalse(bench.err.contains("cannot read"), bench.err);

            Run det = run(() -> Cli.execute(new String[]{
                    "ocr", "--no-det", "missing.png", "--model", dir.toString()}));
            assertEquals(1, det.code);
            assertTrue(det.err.contains("recognition model"), det.err);
            assertFalse(det.err.contains("detection model"), det.err);
            assertFalse(det.err.contains("cannot read"), det.err);

            Files.write(dir.resolve(ModelFiles.DET), new byte[0]);
            Run empty = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--model", dir.toString()}));
            assertEquals(1, empty.code);
            assertTrue(empty.err.contains("detection model is empty"), empty.err);
            assertTrue(empty.err.contains("ocr download"), empty.err);
            assertFalse(empty.err.contains("cannot read"), empty.err);
            assertFalse(empty.err.contains("truncated"), empty.err);

            Files.write(dir.resolve(ModelFiles.DET), new byte[]{1});
            Files.write(dir.resolve(ModelFiles.REC), new byte[0]);
            Run rec = run(() -> Cli.execute(new String[]{
                    "ocr", "--no-det", "missing.png", "--model", dir.toString()}));
            assertEquals(1, rec.code);
            assertTrue(rec.err.contains("recognition model is empty"), rec.err);
            assertFalse(rec.err.contains("detection model"), rec.err);
            assertFalse(rec.err.contains("cannot read"), rec.err);

            Files.write(dir.resolve(ModelFiles.REC), new byte[]{1});
            Files.write(dir.resolve(ModelFiles.DICT), new byte[0]);
            Run dict = run(() -> Cli.execute(new String[]{"ocr", "--no-det", "missing.png", "--model", dir.toString()}));
            assertEquals(1, dict.code);
            assertTrue(dict.err.contains("recognition dict is empty"), dict.err);
            assertFalse(dict.err.contains("cannot read"), dict.err);

            Files.write(dir.resolve(ModelFiles.DICT), new byte[]{'a'});
            Files.write(dir.resolve(ModelFiles.CLS), new byte[0]);
            Run cls = run(() -> Cli.execute(new String[]{"ocr", "--no-det", "missing.png", "--model", dir.toString()}));
            assertEquals(1, cls.code);
            assertTrue(cls.err.contains("classifier model is empty"), cls.err);
            assertFalse(cls.err.contains("recognition"), cls.err);
            assertFalse(cls.err.contains("cannot read"), cls.err);

            Run skipped = run(() -> Cli.execute(new String[]{
                    "ocr", "--no-cls", "missing.png", "--model", dir.toString()}));
            assertEquals(1, skipped.code);
            assertTrue(skipped.err.contains("cannot read"), skipped.err);
            assertFalse(skipped.err.contains("classifier"), skipped.err);
            assertFalse(skipped.err.contains("is empty"), skipped.err);

            Path blank = dir.resolve("blank.onnx");
            Files.write(blank, new byte[0]);
            Run ops = run(() -> Cli.execute(new String[]{"ops", blank.toString()}));
            assertEquals(1, ops.code);
            assertTrue(ops.err.contains("file is empty"), ops.err);
            assertTrue(ops.err.contains("blank.onnx"), ops.err);
            assertFalse(ops.err.contains("truncated"), ops.err);
        } finally {
            Files.deleteIfExists(dir.resolve("blank.onnx"));
            Files.deleteIfExists(dir.resolve(ModelFiles.DET));
            Files.deleteIfExists(dir.resolve(ModelFiles.REC));
            Files.deleteIfExists(dir.resolve(ModelFiles.DICT));
            Files.deleteIfExists(dir.resolve(ModelFiles.CLS));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void closedEngineRejectsFurtherCalls() {
        OcrEngine engine = OcrEngine.create(OcrConfig.builder()
                .threads(1)
                .useDet(false)
                .useRec(false)
                .useCls(false)
                .build());
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        OcrException blank = assertThrows(OcrException.class, () -> engine.recognize(null));
        assertTrue(blank.getMessage().contains("image is null"), blank.getMessage());
        OcrResult open = engine.recognize(image);
        assertEquals("", open.text());
        assertEquals(1, open.lines().size());
        engine.close();
        OcrException closed = assertThrows(OcrException.class, () -> engine.recognize(image));
        assertTrue(closed.getMessage().contains("engine is closed"), closed.getMessage());
        OcrException line = assertThrows(OcrException.class, () -> engine.recognizeLine(image));
        assertTrue(line.getMessage().contains("engine is closed"), line.getMessage());
        OcrException pdf = assertThrows(OcrException.class, () -> engine.recognizePdf(Path.of("missing.pdf"), 72f));
        assertTrue(pdf.getMessage().contains("engine is closed"), pdf.getMessage());
        assertFalse(pdf.getMessage().contains("cannot render"), pdf.getMessage());
        engine.close();
    }

    @Test
    void modelPathThatIsAFileFailsBeforeTheDocument() throws Exception {
        Path dir = Files.createTempDirectory("ocr-modelfile");
        Path file = dir.resolve("weights");
        try {
            Files.writeString(file, "not-a-directory");
            Run ocr = run(() -> Cli.execute(new String[]{"ocr", "missing.png", "--model", file.toString()}));
            assertEquals(1, ocr.code);
            assertTrue(ocr.err.contains("not a directory"), ocr.err);
            assertFalse(ocr.err.contains("cannot read"), ocr.err);
            assertFalse(ocr.err.contains("detection model"), ocr.err);

            Run pdf = run(() -> Cli.execute(new String[]{
                    "pdf", "missing.pdf", "--model", file.toString(), "--dpi", "72"}));
            assertEquals(1, pdf.code);
            assertTrue(pdf.err.contains("not a directory"), pdf.err);
            assertFalse(pdf.err.contains("cannot render"), pdf.err);

            Run download = run(() -> Cli.execute(new String[]{"download", file.toString()}));
            assertEquals(1, download.code);
            assertTrue(download.err.contains("not a directory"), download.err);
            assertFalse(download.err.contains("cannot prepare"), download.err);
            assertFalse(download.err.contains("download failed"), download.err);

            Run downloadFlag = run(() -> Cli.execute(new String[]{"download", "--model", file.toString()}));
            assertEquals(1, downloadFlag.code);
            assertTrue(downloadFlag.err.contains("not a directory"), downloadFlag.err);

            OcrException latin = assertThrows(OcrException.class, () -> OcrConfig.latin(file));
            assertTrue(latin.getMessage().contains("not a directory"), latin.getMessage());
            assertEquals(ModelFiles.DET, OcrConfig.latin(dir.resolve("absent")).detModel.getFileName().toString());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void missingModelsNameTheDownloadCommand() throws Exception {
        Path dir = Files.createTempDirectory("ocr-nomodel");
        Path png = dir.resolve("blank.png");
        try {
            assertTrue(ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", png.toFile()));
            Run run = run(() -> Cli.execute(new String[]{"ocr", png.toString(), "--model", dir.toString()}));
            assertEquals(1, run.code);
            assertTrue(run.err.contains("ocr download"), run.err);
            assertTrue(run.err.contains("detection model"), run.err);
            assertThrows(OcrException.class, () -> OcrEngine.load(null));
            OcrException config = assertThrows(OcrException.class, () -> OcrEngine.create(null));
            assertTrue(config.getMessage().contains("config is null"), config.getMessage());
            assertThrows(OcrException.class, () -> OcrConfig.latin(null));
            OcrConfig.Builder builder = OcrConfig.latin(Path.of("models")).toBuilder();
            OcrException std = assertThrows(OcrException.class, () -> builder.std(0f).build());
            assertTrue(std.getMessage().contains("std"), std.getMessage());
            assertThrows(OcrException.class, () -> builder.std(-0.5f).build());
            assertThrows(OcrException.class, () -> builder.std(Float.NaN).build());
            OcrException mean = assertThrows(OcrException.class, () -> builder.std(0.5f).mean(Float.NaN).build());
            assertTrue(mean.getMessage().contains("mean"), mean.getMessage());
            OcrConfig.Builder limits = OcrConfig.builder();
            assertEquals(960, limits.build().detLimitSideLen);
            OcrException side = assertThrows(OcrException.class, () -> limits.detLimitSideLen(0).build());
            assertTrue(side.getMessage().contains("detLimitSideLen"), side.getMessage());
            limits.detLimitSideLen(960);
            OcrException type = assertThrows(OcrException.class, () -> limits.detLimitType("MAX").build());
            assertTrue(type.getMessage().contains("detLimitType"), type.getMessage());
            assertEquals("min", limits.detLimitType("min").build().detLimitType);
            limits.detLimitType("max");
            assertThrows(OcrException.class, () -> limits.detThresh(Float.NaN).build());
            limits.detThresh(0.3f);
            assertThrows(OcrException.class, () -> limits.unclipRatio(Float.POSITIVE_INFINITY).build());
            assertEquals(0f, limits.unclipRatio(0f).build().unclipRatio, 0f);
            limits.unclipRatio(1.6f);
            assertThrows(OcrException.class, () -> limits.clsThresh(Float.NaN).build());
            limits.clsThresh(0.9f);
            assertThrows(OcrException.class, () -> limits.recHeight(0).build());
            limits.recHeight(48);
            assertThrows(OcrException.class, () -> limits.recMaxWidth(-1).build());
            limits.recMaxWidth(4096);
            assertThrows(OcrException.class, () -> limits.maxCandidates(0).build());
            limits.maxCandidates(1000);
            OcrException huge = assertThrows(OcrException.class,
                    () -> limits.recHeight(1_000_000).recMaxWidth(1_000_000).build());
            assertTrue(huge.getMessage().contains("too large"), huge.getMessage());
            OcrException download = assertThrows(OcrException.class, () -> ModelFiles.ensureLatin(null));
            assertTrue(download.getMessage().contains("model directory is null"), download.getMessage());
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(dir);
        }
    }

    /** PNG with a {@code tEXt} chunk that contains {@code %PDF-} inside the first kilobyte. */
    private static byte[] pngWithPdfMarker(BufferedImage image) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", raw));
        byte[] png = raw.toByteArray();
        int length = u32be(png, 8);
        int afterIhdr = 8 + 8 + length + 4;
        byte[] chunk = pngChunk("tEXt", "Comment\0%PDF-".getBytes(StandardCharsets.US_ASCII));
        byte[] out = new byte[png.length + chunk.length];
        System.arraycopy(png, 0, out, 0, afterIhdr);
        System.arraycopy(chunk, 0, out, afterIhdr, chunk.length);
        System.arraycopy(png, afterIhdr, out, afterIhdr + chunk.length, png.length - afterIhdr);
        return out;
    }

    private static byte[] pngChunk(String type, byte[] data) {
        byte[] kind = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(kind);
        crc.update(data);
        byte[] out = new byte[12 + data.length];
        putU32be(out, 0, data.length);
        System.arraycopy(kind, 0, out, 4, 4);
        System.arraycopy(data, 0, out, 8, data.length);
        putU32be(out, 8 + data.length, (int) crc.getValue());
        return out;
    }

    /** JPEG with a COM segment that contains {@code %PDF-} right after the start of image. */
    private static byte[] jpegWithPdfMarker(BufferedImage image) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", raw));
        byte[] jpg = raw.toByteArray();
        byte[] payload = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        int len = payload.length + 2;
        byte[] segment = new byte[4 + payload.length];
        segment[0] = (byte) 0xff;
        segment[1] = (byte) 0xfe;
        segment[2] = (byte) (len >> 8);
        segment[3] = (byte) len;
        System.arraycopy(payload, 0, segment, 4, payload.length);
        byte[] out = new byte[jpg.length + segment.length];
        out[0] = jpg[0];
        out[1] = jpg[1];
        System.arraycopy(segment, 0, out, 2, segment.length);
        System.arraycopy(jpg, 2, out, 2 + segment.length, jpg.length - 2);
        return out;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        int last = data.length - needle.length;
        for (int i = 0; i <= last; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static int u32be(byte[] data, int index) {
        return ((data[index] & 0xff) << 24)
                | ((data[index + 1] & 0xff) << 16)
                | ((data[index + 2] & 0xff) << 8)
                | (data[index + 3] & 0xff);
    }

    private static void putU32be(byte[] data, int index, int value) {
        data[index] = (byte) (value >> 24);
        data[index + 1] = (byte) (value >> 16);
        data[index + 2] = (byte) (value >> 8);
        data[index + 3] = (byte) value;
    }

    private static Run run(Callable<Integer> call) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBytes, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        try {
            int code = call.call();
            return new Run(code, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private record Run(int code, String out, String err) {
    }
}
