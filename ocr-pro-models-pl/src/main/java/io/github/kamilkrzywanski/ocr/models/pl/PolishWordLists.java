package io.github.kamilkrzywanski.ocr.models.pl;

import io.github.kamilkrzywanski.ocr.ModelBundle;
import io.github.kamilkrzywanski.ocr.internal.post.Lexicon;
import io.github.kamilkrzywanski.ocr.internal.post.PostalCities;

import java.io.InputStream;
import java.util.Set;

/**
 * The word lists of {@link io.github.kamilkrzywanski.ocr.Corrections#POLISH}: the sjp.pl Polish
 * list, the SCOWL English list and GeoNames postal codes for Poland. Licenses and authors are in
 * {@code META-INF/models/NOTICE} and {@code META-INF/models/LICENSE-SCOWL.txt}.
 */
public final class PolishWordLists implements ModelBundle {
    private static final Set<String> FILES = Set.of(Lexicon.POLISH_FILE, Lexicon.ENGLISH_FILE, PostalCities.FILE);

    @Override
    public InputStream open(String name) {
        return FILES.contains(name) ? PolishWordLists.class.getResourceAsStream(name) : null;
    }
}
