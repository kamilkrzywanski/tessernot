package org.krzywanski.ocr.post;

import org.junit.jupiter.api.Test;
import org.krzywanski.ocr.OcrException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexiconTest {
    private final Lexicon lexicon = Lexicon.of(
            new String[] {
                    "żniwna", "żniwną", "świat", "spółka", "słownie", "usługi", "miejsce", "magazyn",
                    "wspólnicy", "zyt", "żyt", "kos", "sprzedaż", "wysyłka", "według", "wystawił",
                    "odebrał", "łata", "tała", "żaba", "źaba", "statę", "wartość", "stawki",
                    "podpis", "odbioru", "liczba", "upoważnionej", "bolć", "bćle", "bial", "blai", "bcco", "bocc",
                    "oryginał", "banał", "binał"
            },
            new String[] {"state", "poland", "hello"});

    @Test
    void restoresTheMeasuredConfusionsAndLeavesTheRest() {
        assertEquals("ul. Żniwna 5", lexicon.apply("ul. Zniwna 5"));
        assertEquals("Ser ż.Świat. 150g", lexicon.apply("Ser ż.Swiat. 150g"));
        assertEquals("Spółka Jawna", lexicon.apply("Spolka Jawna"));
        assertEquals("Słownie:", lexicon.apply("Stownie:"));
        assertEquals("wykonania usługi", lexicon.apply("wykonania ustugi"));
        assertEquals("Miejsce wystawienia:", lexicon.apply("Mieisce wystawienia:"));
        assertEquals("i magazyn", lexicon.apply("imagazyn"));
        assertEquals("i Wspólnicy", lexicon.apply("iWspólnicy"));
        assertEquals("Wysyłka", lexicon.apply("Wysylka"));
        assertEquals("według", lexicon.apply("wedlug"));
        assertEquals("Wystawił", lexicon.apply("Wystawil"));
        assertEquals("Odebrał", lexicon.apply("Odebral"));
        assertEquals("SPRZEDAZ", lexicon.apply("SPRZEDAZ"));
        assertEquals("DZIEKUJEMY", lexicon.apply("DZIEKUJEMY"));
        assertEquals("Poland", lexicon.apply("Poland"));
        assertEquals("state", lexicon.apply("state"));
        assertEquals("Zyt", lexicon.apply("Zyt"));
        assertEquals("kos", lexicon.apply("kos"));
        assertEquals("Polędw", lexicon.apply("Poledw"));
        assertEquals("polędw", lexicon.apply("polędw"));
        assertEquals("Imagazyn", lexicon.apply("Imagazyn"));
        assertEquals("tata", lexicon.apply("tata"));
        assertEquals("zaba", lexicon.apply("zaba"));
        assertEquals("Wartość netto", lexicon.apply("Wartosó netto"));
        assertEquals("Wartość", lexicon.apply("Wartose"));
        assertEquals("wartość", lexicon.apply("wartoso"));
        assertEquals("według stawki VAT", lexicon.apply("według stawkl VAT"));
        assertEquals("Podpis", lexicon.apply("Podpls"));
        assertEquals("odbioru", lexicon.apply("odbloru"));
        assertEquals("Liczba", lexicon.apply("Liozba"));
        assertEquals("upoważnionej", lexicon.apply("upoważnlonel"));
        assertEquals("2 Wysyłka", lexicon.apply("2Wysyłka"));
        assertEquals("2 Wysyłka", lexicon.apply("2Wysylka"));
        assertEquals("100g", lexicon.apply("100g"));
        assertEquals("550g", lexicon.apply("Chleb 550g").substring("Chleb ".length()));
        assertEquals("boco", lexicon.apply("boco"));
        assertEquals("bole", lexicon.apply("bole"));
        assertEquals("blal", lexicon.apply("blal"));
        assertEquals("ORYGINAŁ", lexicon.apply("ORYGNAL"));
        assertEquals("ORYGINAŁ", lexicon.apply("ORYGINAŁ"));
        assertEquals("SPRZEDAZ", lexicon.apply("SPRZEDAZ"));
        assertEquals("DZIEKUJEMY", lexicon.apply("DZIEKUJEMY"));
        assertEquals("BNAL", lexicon.apply("BNAL"));
        assertEquals("", lexicon.apply(""));
    }

    @Test
    void wordListDoesNotMatchAPrefix() {
        WordList list = WordList.of("ser", "serce", "ą");
        assertTrue(list.contains("ser"));
        assertTrue(list.contains("serce"));
        assertTrue(list.contains("ą"));
        assertFalse(list.contains("se"));
        assertFalse(list.contains("serc"));
        assertFalse(list.contains(""));
    }

    @Test
    void optionalLoadsGzipListsBesideTheModels() throws Exception {
        Path dir = Files.createTempDirectory("lexicon");
        writeGz(dir.resolve(Lexicon.POLISH_FILE), "magazyn\n");
        assertNull(Lexicon.optional(dir));
        writeGz(dir.resolve(Lexicon.ENGLISH_FILE), "poland\n");
        Lexicon loaded = Lexicon.optional(dir);
        assertEquals("i magazyn", loaded.apply("imagazyn"));
        assertNull(Lexicon.optional(null));
        Files.writeString(dir.resolve(Lexicon.POLISH_FILE), "not gzip");
        assertThrows(OcrException.class, () -> Lexicon.optional(dir));
    }

    private static void writeGz(Path path, String text) throws Exception {
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
