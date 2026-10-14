package io.github.kamilkrzywanski.ocr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.github.kamilkrzywanski.ocr.internal.image.Images;
import io.github.kamilkrzywanski.ocr.internal.post.Lexicon;
import io.github.kamilkrzywanski.ocr.internal.post.PostalCities;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Printed text on three public scans, read with {@link Corrections#POLISH}: these are the scans
 * those rules were measured on, so the ceilings guard the rules, not general accuracy
 * (that is the held-out set in {@code bench/}). The ceiling is the edit count of the current
 * PP-OCRv6 small reader. A larger count fails. Handwriting, stamps, and the invoice
 * table grid are not part of the reference, and extra detections do not add edits.
 * Ruled cells are checked on the same result and do not change that count.
 */
class QualityGateTest {
    /** Typed letter, read exactly. The signature and the blue time stamp are not scored. */
    private static final int NIXON_MAX_EDITS = 0;
    /**
     * Thermal receipt with the optional lexicon. The ł in łos remains.
     * The stem polędw puts the ogonek back on Polędw. A dot above the notched Z restores Żyt.
     * Without the lists, Żniwna and Poledw remain. Świat is the capital-S runner-up,
     * so it does not need the lists. The dot still restores Żyt.
     * Wrocław, łącznie, and both {@code x} rows match.
     */
    private static final int RECEIPT_MAX_EDITS = 1;
    private static final int RECEIPT_MAX_EDITS_WITHOUT_LEXICON = 3;
    /**
     * Invoice fields outside the table, plus the product name and the amount due.
     * With the lexicon and {@code postal-pl.txt.gz} the invoice matches: 90-031 is Łódź
     * when the letters already agree. Without that file the capital stays L, one edit.
     * A postal code whose city still contains a digit is read again at ink threshold 220,
     * and kept when the code matches and the city is letters. The Lp digit glued to
     * Ziemia Kłodzka is cut when a second read is the first read without the digit.
     * The cut drops ł and ó. Without the word lists the first read already has both,
     * so a shorter read that only lost those marks still confirms the cut and the digit
     * goes. The postal list still writes Łódź without the word lists, so that line is
     * not two edits. Without the word lists the line is read again with four white rows
     * above and below, then with the ink shifted down four pixels, then with eight white
     * rows underneath. A read stays only when the only changes are ł for t or l, or a
     * space between letters, and the read that repairs more of those wins. That restores
     * the warehouse space, usługi, and the whole of Spółka, including the space before
     * Wspólnicy. The ó in that word is the runner-up on o. A usual VAT label that is
     * one edit from exactly one known label takes that label, so Miejsce and Słownie
     * match without the word lists. With the postal list the invoice then matches.
     * A line that is only a postal code skips the margin read, and a label step skips
     * any line with a digit, so without either file the city stays {@code Lodz} with
     * an ascii z and the invoice is two edits. Both NIP lines keep the
     * space after the colon. zapłaty, Zapłacono, dziewięćdziesiąt, and both amounts
     * match. The grid and the signature are not scored. The red stamp is scored only
     * as ORYGINAŁ.
     */
    private static final int INVOICE_MAX_EDITS = 0;
    private static final int INVOICE_MAX_EDITS_WITHOUT_POSTAL = 1;
    private static final int INVOICE_MAX_EDITS_WITHOUT_LEXICON = 0;
    private static final int INVOICE_MAX_EDITS_WITHOUT_LISTS = 2;
    private static final int PLAIN_NIXON_MAX_EDITS = 0;
    private static final int PLAIN_RECEIPT_MAX_EDITS = 9;
    private static final int PLAIN_INVOICE_MAX_EDITS = 18;

    private static final List<String> NIXON = List.of(
            "THE WHITE HOUSE",
            "WASHINGTON",
            "August 9, 1974",
            "Dear Mr. Secretary:",
            "I hereby resign the Office of President of the",
            "United States.",
            "Sincerely,",
            "The Honorable Henry A. Kissinger",
            "The Secretary of State",
            "Washington, D.C. 20520"
    );

    private static final List<String> RECEIPT = List.of(
            "Biedronka",
            "Codziennie niskie ceny",
            "Sklep 5163 ul. Grabarska 2, 50-079 Wrocław",
            "Jeronimo Martins Polska S.A.",
            "ul. Żniwna 5, 62-025 Kostrzyn",
            "NIP 779-10-11-327",
            "2020-01-09 13:18",
            "422351",
            "PARAGON FISKALNY",
            "ChlebPszen-Żyt550g",
            "1 x1,99 1,99C",
            "Ser ż.Świat. 150g",
            "1 x3.29 3,29C",
            "Polędw łos 100g",
            "1 x4,99 4,99C",
            "Rabat",
            "-2,00",
            "2,99C",
            "SPRZEDAZ OPODATKOWANA C",
            "8,27",
            "PTU C 5,00 %",
            "0,39",
            "SUMA PTU",
            "0,39",
            "SUMA PLN",
            "8,27",
            "00098 #Kasa 2 Kasjer nr 5",
            "2020-01-09 13:18",
            "7A867BBB57E0D223E2006F5A914C03216172E61C",
            "CCH 1701363955",
            "Nr sys. 5667",
            "Karta Visa Debit 05 1",
            "8,27 PLN",
            "Nr transakcji:",
            "5667",
            "Numer:",
            "5163200109566702",
            "Udzielono łącznie rabatów:",
            "2,00",
            "DZIEKUJEMY I ZAPRASZAMY PONOWNIE",
            "BDO 000004585"
    );

    private static final List<String> INVOICE = List.of(
            "ORYGINAŁ",
            "Oficyna Wydawnicza \"Rewasz\", tel. (22) 798-33-84, fax 435-55-90",
            "Biuro i magazyn: ul. Warszawska 55, 05-820 Piastów",
            "Konto: PKO BP XV O/W-wa, 05 1020 1156 0000 7902 0010 4927",
            "www.rewasz.com.pl",
            "rewasz@rewasz.com.pl",
            "Miejsce wystawienia:",
            "Pruszków",
            "Data dostawy/wykonania usługi",
            "2014-06-25",
            "Data wystawienia:",
            "25 czerwiec 2014",
            "Sprzedawca:",
            "\"Rewasz\" Antoniak i Wspólnicy Spółka Jawna",
            "Dobra 5/36",
            "05-800 Pruszków",
            "NIP: 534-000-67-48",
            "Nabywca:",
            "Stowarzyszenie Wikimedia Polska",
            "Tuwima 95/pok. 15",
            "90-031 Łódź",
            "NIP: 728-25-97-388",
            "Symbol: W HALICKI",
            "Faktura VAT 268/APV/2014",
            "Ziemia Kłodzka",
            "Razem do zapłaty:",
            "90,00",
            "Słownie:",
            "dziewięćdziesiąt PLN 0/100",
            "Zapłacono przelewem:",
            "90,00"
    );

    @Test
    void extraLinesDoNotRaiseThePrintedCer() {
        assertEquals(0, PrintedCer.score(List.of("ALA", "kot"), List.of("ALA", "kot")).edits());
        assertEquals(0, PrintedCer.score(List.of("ALA", "śmieć", "kot"), List.of("ALA", "kot")).edits());
        assertEquals(1, PrintedCer.score(List.of("ALA", "kOt"), List.of("ALA", "kot")).edits());
        assertEquals(3, PrintedCer.score(List.of("ALA"), List.of("ALA", "kot")).edits());
        assertEquals(2, PrintedCer.score(List.of("AA"), List.of("AA", "AA")).edits());
        assertEquals(0, PrintedCer.score(List.of("a", "ab"), List.of("ab", "a")).edits());
        assertEquals(0, PrintedCer.score(List.of(), List.of()).edits());
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void realPrintDoesNotRegress() throws Exception {
        assumeTrue(modelsPresent(), "models/ is missing");
        // The rules were measured on crops from detection at 960 px, and the counts below describe
        // those crops. The default 2048 px draws tighter boxes; the plain gate covers that pipeline.
        OcrConfig polish = OcrConfig.defaults(Path.of("models")).toBuilder()
                .detLimitSideLen(960)
                .corrections(Corrections.POLISH)
                .build();
        try (OcrEngine engine = OcrEngine.create(polish)) {
            boolean lexicon = lexiconPresent();
            boolean postal = postalPresent();
            OcrResult nixon = check(engine, "nixon-resign.jpg", NIXON, NIXON_MAX_EDITS);
            OcrResult receipt = check(engine, "receipt-biedronka.jpg", RECEIPT,
                    lexicon ? RECEIPT_MAX_EDITS : RECEIPT_MAX_EDITS_WITHOUT_LEXICON);
            OcrResult invoice = check(engine, "faktura-klodzka.jpg", INVOICE,
                    invoiceCeiling(lexicon, postal));
            assertJoinedReadingOrder(nixon.text(), receipt.text(), invoice.text());
            assertTrue(nixon.tables().isEmpty(), dump(nixon.tables()));
            assertTrue(receipt.tables().isEmpty(), dump(receipt.tables()));
            assertRuledInvoice(invoice);
        }
    }

    /**
     * The same scans without corrections: detection, angle classifier, recognition, greedy CTC.
     * The ceilings are the edit counts of PP-OCRv6 small with nothing on top, so a change
     * in the runtime or pre-processing that costs characters fails here.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void plainPipelineDoesNotRegress() throws Exception {
        assumeTrue(modelsPresent(), "models/ is missing");
        try (OcrEngine engine = OcrEngine.create(OcrConfig.defaults(Path.of("models")))) {
            check(engine, "nixon-resign.jpg", NIXON, PLAIN_NIXON_MAX_EDITS);
            check(engine, "receipt-biedronka.jpg", RECEIPT, PLAIN_RECEIPT_MAX_EDITS);
            check(engine, "faktura-klodzka.jpg", INVOICE, PLAIN_INVOICE_MAX_EDITS);
        }
    }

    /** The plain pipeline with the recognizer's last layer cut to Latin script stays within the same ceilings. */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void latinAlphabetDoesNotRegress() throws Exception {
        assumeTrue(modelsPresent(), "models/ is missing");
        OcrConfig latin = OcrConfig.defaults(Path.of("models")).toBuilder().alphabet(Alphabet.LATIN).build();
        try (OcrEngine engine = OcrEngine.create(latin)) {
            check(engine, "nixon-resign.jpg", NIXON, PLAIN_NIXON_MAX_EDITS);
            check(engine, "receipt-biedronka.jpg", RECEIPT, PLAIN_RECEIPT_MAX_EDITS);
            check(engine, "faktura-klodzka.jpg", INVOICE, PLAIN_INVOICE_MAX_EDITS);
        }
    }

    /** The Rewasz item row and the VAT grid. A price rail and the parties block are not cells. */
    private static void assertRuledInvoice(OcrResult result) {
        String dump = dump(result.tables());
        assertTrue(result.tables().size() <= 4, dump);
        boolean itemRow = false;
        boolean rates = false;
        for (TextTable table : result.tables()) {
            boolean vat23 = false;
            boolean vat5 = false;
            for (List<String> row : table.cells()) {
                int name = -1;
                int gross = -1;
                for (int column = 0; column < row.size(); column++) {
                    String cell = row.get(column);
                    assertFalse(cell.contains("Sprzedawca") && cell.contains("Nabywca"), cell);
                    if (name < 0 && cell.contains("Ziemia")) {
                        name = column;
                    }
                    if (gross < 0 && (cell.contains("80.00") || cell.contains("80,00"))) {
                        gross = column;
                    }
                    if (cell.contains("23%") || cell.contains("VAT 23")) {
                        vat23 = true;
                    }
                    if (cell.contains("5%") || cell.contains("VAT 5")) {
                        vat5 = true;
                    }
                }
                if (name >= 0 && gross >= 0 && name != gross) {
                    itemRow = true;
                }
            }
            if (vat23 && vat5) {
                rates = true;
            }
        }
        assertTrue(itemRow, dump);
        assertTrue(rates, dump);
    }

    private static String dump(List<TextTable> tables) {
        StringBuilder out = new StringBuilder();
        for (TextTable table : tables) {
            out.append(table.rows()).append('x').append(table.columns()).append('\n');
            for (List<String> row : table.cells()) {
                out.append(String.join(" | ", row)).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * The joined page, not the individual boxes. Seller and the letterhead come before the
     * facing column. A receipt price stays on the item's line. Nixon's typed lines stay in order.
     */
    private static void assertJoinedReadingOrder(String nixon, String receipt, String invoice) {
        assertInOrder(nixon, NIXON);
        int stamp = nixon.indexOf("11.35 AM");
        if (stamp >= 0) {
            assertTrue(nixon.indexOf("Sincerely,") < stamp, nixon);
        }
        assertSameLine(receipt, "Chleb", "1,99");
        assertSameLine(receipt, "Ser", "3,29");
        assertSameLine(receipt, "100g", "4,99");
        assertDifferentLines(receipt, "Ser", "4,99");
        assertDifferentLines(receipt, "100g", "3,29");
        assertSameLine(receipt, "2020-01-09", "422351");
        assertSameLine(receipt, "Rabat", "-2,00");
        assertSameLine(receipt, "SUMA PLN", "8,27");
        assertInOrder(invoice, List.of(
                "Oficyna",
                "Biuro",
                "Konto:",
                "www.rewasz.com.pl",
                "rewasz@rewasz.com.pl",
                "wystawienia:",
                "Pruszków",
                "Data dostawy",
                "2014-06-25",
                "Data wystawienia:",
                "25 czerwiec 2014",
                "Sprzedawca:",
                "Dobra",
                "534-000-67-48",
                "Nabywca:",
                "Tuwima",
                "728-25-97-388",
                "Symbol:"));
        assertSameLine(invoice, "www.rewasz.com.pl", "rewasz@rewasz.com.pl");
        assertDifferentLines(invoice, "rewasz@rewasz.com.pl", "2014-06-25");
        assertDifferentLines(invoice, "Sprzedawca:", "Nabywca:");
        assertSameLine(invoice, "Ziemia", "80.00");
        assertSameLine(invoice, "Razem do zap", "90,00");
        assertSameLine(invoice, "przelewem:", "90,00");
    }

    private static void assertInOrder(String text, List<String> parts) {
        int at = -1;
        for (String part : parts) {
            int found = text.indexOf(part, at + 1);
            assertTrue(found >= 0, "out of order \"" + part + "\"\n" + text);
            at = found + part.length() - 1;
        }
    }

    private static void assertSameLine(String text, String anchor, String other) {
        String line = lineWith(text, anchor);
        assertTrue(line.contains(other), anchor + " / " + other + "\n" + text);
    }

    private static void assertDifferentLines(String text, String anchor, String other) {
        assertFalse(lineWith(text, anchor).contains(other), anchor + " glued " + other + "\n" + text);
    }

    private static String lineWith(String text, String anchor) {
        for (String line : text.split("\n", -1)) {
            if (line.contains(anchor)) {
                return line;
            }
        }
        throw new AssertionError("missing \"" + anchor + "\"\n" + text);
    }

    private static OcrResult check(OcrEngine engine, String file, List<String> reference, int maxEdits) throws IOException {
        OcrResult result = engine.recognize(page(file));
        List<String> lines = new ArrayList<>();
        for (TextLine line : result.lines()) {
            lines.add(line.text());
        }
        PrintedCer.Result score = PrintedCer.score(lines, reference);
        System.out.print(score.detail(file));
        assertTrue(score.edits() <= maxEdits, score.detail(file));
        return result;
    }

    private static BufferedImage page(String name) throws IOException {
        try (InputStream in = QualityGateTest.class.getResourceAsStream("/quality/" + name)) {
            if (in == null) {
                throw new AssertionError("missing test scan " + name);
            }
            Path tmp = Files.createTempFile("quality-", "-" + name);
            tmp.toFile().deleteOnExit();
            Files.write(tmp, in.readAllBytes());
            return Images.read(tmp);
        }
    }

    private static boolean modelsPresent() {
        return Files.exists(Path.of("models", ModelFiles.DET))
                && Files.exists(Path.of("models", ModelFiles.REC))
                && Files.exists(Path.of("models", ModelFiles.CLS))
                && Files.exists(Path.of("models", ModelFiles.DICT));
    }

    private static boolean lexiconPresent() {
        return Files.isRegularFile(Path.of("models", Lexicon.POLISH_FILE))
                && Files.isRegularFile(Path.of("models", Lexicon.ENGLISH_FILE));
    }

    private static boolean postalPresent() {
        return Files.isRegularFile(Path.of("models", PostalCities.FILE));
    }

    /** Word lists and the postal list are independent. The ceiling is the run that had both, one, or neither. */
    private static int invoiceCeiling(boolean lexicon, boolean postal) {
        if (lexicon && postal) {
            return INVOICE_MAX_EDITS;
        }
        if (lexicon) {
            return INVOICE_MAX_EDITS_WITHOUT_POSTAL;
        }
        if (postal) {
            return INVOICE_MAX_EDITS_WITHOUT_LEXICON;
        }
        return INVOICE_MAX_EDITS_WITHOUT_LISTS;
    }
}
