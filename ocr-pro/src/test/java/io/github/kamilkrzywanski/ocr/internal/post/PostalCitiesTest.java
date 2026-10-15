package io.github.kamilkrzywanski.ocr.internal.post;

import org.junit.jupiter.api.Test;
import io.github.kamilkrzywanski.ocr.OcrException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostalCitiesTest {
    private final PostalCities cities = PostalCities.of(
            "90-031\tŁódź",
            "05-800\tPruszków",
            "05-820\tPiastów",
            "59-300\tLubin");

    @Test
    void restoresDiacriticsOnlyWhenTheCodeHasOneSpelling() {
        assertEquals("90-031 Łódź", cities.apply("90-031 Lódź"));
        assertEquals("90-031 Łódź", cities.apply("90-031 Lodz"));
        assertEquals("90-031 Łódź", cities.apply("90-031 L\u00f3dz"));
        assertEquals("90-031 Łódź", cities.apply("90-031 Łódź"));
        assertEquals("  90-031 Łódź  ", cities.apply("  90-031 Lódź  "));
        assertEquals("90-031  Łódź", cities.apply("90-031  Lódź"));
        assertEquals("05-800 Pruszków", cities.apply("05-800 Pruszków"));
        assertEquals("05-800 Pruszków", cities.apply("05-800 Pruszkow"));
        assertEquals("Biuro i magazyn: ul. Warszawska 55, 05-820 Piastów",
                cities.apply("Biuro i magazyn: ul. Warszawska 55, 05-820 Piastów"));
        assertEquals("62-025 Kostrzyn", cities.apply("62-025 Kostrzyn"));
        assertEquals("90-031 t6dż", cities.apply("90-031 t6dż"));
        assertEquals("90-031 Łubin", cities.apply("90-031 Łubin"));
        assertEquals("59-300 Łubin", cities.apply("59-300 Łubin"));
        assertEquals("59-300 Lubin", cities.apply("59-300 Lubin"));
        assertEquals("2014-06-25", cities.apply("2014-06-25"));
        assertEquals("THE WHITE HOUSE", cities.apply("THE WHITE HOUSE"));
        assertEquals("", cities.apply(""));
        assertNull(cities.apply(null));
    }

    @Test
    void anAsciiOfficialSpellingDoesNotStripMarks() {
        PostalCities ascii = PostalCities.of("90-031\tLodz");
        assertEquals("90-031 Łódź", ascii.apply("90-031 Łódź"));
        assertEquals("90-031 Lodz", ascii.apply("90-031 Lodz"));
    }

    @Test
    void missingFileSkipsTheStep() throws Exception {
        Path dir = Files.createTempDirectory("postal-missing");
        assertNull(PostalCities.optional(dir));
        assertNull(PostalCities.optional(null));
    }

    @Test
    void aBrokenFileFailsTheLoad() throws Exception {
        Path dir = Files.createTempDirectory("postal-broken");
        Files.writeString(dir.resolve(PostalCities.FILE), "not gzip");
        assertThrows(OcrException.class, () -> PostalCities.optional(dir));
    }

    @Test
    void theShippedListFixesTheMeasuredCityAndLeavesASharedCode() {
        assumeTrue(Files.isRegularFile(Path.of("models", PostalCities.FILE)));
        PostalCities shipped = PostalCities.optional(Path.of("models"));
        assertEquals("90-031 Łódź", shipped.apply("90-031 Lódź"));
        assertEquals("90-031 Łódź", shipped.apply("90-031 Lodz"));
        assertEquals("05-800 Pruszków", shipped.apply("05-800 Pruszków"));
        assertEquals("05-820 Piastów", shipped.apply("05-820 Piastow"));
        assertEquals("62-025 Kostrzyn", shipped.apply("62-025 Kostrzyn"));
        assertEquals("50-079 Wrocław", shipped.apply("50-079 Wroclaw"));
    }
}
