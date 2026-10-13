package org.krzywanski.ocr.post;

import org.krzywanski.ocr.OcrException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Restores Polish diacritics on a city that follows a postal code.
 * The line has to be only the code and the place name. The code has to have one
 * official spelling, the letters have to already match, and that spelling has to
 * carry at least as many diacritics as the recognition. Anything else stays,
 * including a code shared by several places.
 * <p>
 * The list is built from the GeoNames postal dump for Poland, which GeoNames
 * publishes under CC-BY-4.0 (https://www.geonames.org/). Codes whose place names
 * do not share one diacritic-insensitive spelling are left out. The file is
 * optional. {@link #optional(Path)} does nothing when it is missing, so an engine
 * without it reads exactly as before.
 */
public final class PostalCities {
    public static final String FILE = "postal-pl.txt.gz";
    private static final Pattern CODE = Pattern.compile("^(\\d{2}-\\d{3})\\s+(\\S.*)$");
    private static final String MARKS = "ąęćłńóśźż";

    private final Map<String, String> cities;

    private PostalCities(Map<String, String> cities) {
        this.cities = Map.copyOf(cities);
    }

    /** Gzip list from a model directory, or null when the file is absent. */
    public static PostalCities optional(Path modelDirectory) {
        if (modelDirectory == null) {
            return null;
        }
        Path file = modelDirectory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return load(file);
    }

    static PostalCities of(String... entries) {
        Map<String, String> cities = new HashMap<>();
        for (String entry : entries) {
            int tab = entry.indexOf('\t');
            cities.put(entry.substring(0, tab), entry.substring(tab + 1));
        }
        return new PostalCities(cities);
    }

    /**
     * The same line, or the official city when only diacritics were missing.
     * The code and the spaces around it stay as recognized.
     */
    public String apply(String line) {
        if (line == null || line.isEmpty() || cities.isEmpty()) {
            return line;
        }
        int from = 0;
        int to = line.length();
        while (from < to && line.charAt(from) == ' ') {
            from++;
        }
        while (to > from && line.charAt(to - 1) == ' ') {
            to--;
        }
        Matcher matcher = CODE.matcher(line).region(from, to);
        if (!matcher.matches()) {
            return line;
        }
        String city = matcher.group(2);
        String official = cities.get(matcher.group(1));
        if (official == null || official.equals(city) || marks(official) < marks(city)) {
            return line;
        }
        if (!fold(city).equals(fold(official))) {
            return line;
        }
        return line.substring(0, matcher.start(2)) + official + line.substring(to);
    }

    private static int marks(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int count = 0;
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            if (MARKS.indexOf(cp) >= 0) {
                count++;
            }
            i += Character.charCount(cp);
        }
        return count;
    }

    /** Lower case, with Polish diacritics folded. ź and ż both become z. */
    private static String fold(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            int base = switch (cp) {
                case 'ą' -> 'a';
                case 'ć' -> 'c';
                case 'ę' -> 'e';
                case 'ł' -> 'l';
                case 'ń' -> 'n';
                case 'ó' -> 'o';
                case 'ś' -> 's';
                case 'ź', 'ż' -> 'z';
                default -> cp;
            };
            out.appendCodePoint(base);
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static PostalCities load(Path file) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> cities = new HashMap<>();
            int cursor = 0;
            while (cursor < text.length()) {
                int end = text.indexOf('\n', cursor);
                if (end < 0) {
                    end = text.length();
                }
                int lineEnd = end;
                if (lineEnd > cursor && text.charAt(lineEnd - 1) == '\r') {
                    lineEnd--;
                }
                int tab = text.indexOf('\t', cursor);
                if (tab > cursor && tab < lineEnd) {
                    cities.put(text.substring(cursor, tab), text.substring(tab + 1, lineEnd));
                }
                cursor = end + 1;
            }
            return new PostalCities(cities);
        } catch (IOException e) {
            throw new OcrException("cannot read postal cities " + file + ": " + e.getMessage(), e);
        }
    }
}
