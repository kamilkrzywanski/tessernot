package io.github.kamilkrzywanski.ocr.internal.post;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Polish and English word lists of {@code Corrections.POLISH}, as one {@link Vocabulary} for
 * the CTC word-list search ({@link CtcWords}). The stem {@code polędw} is always known: sjp lists
 * {@code polędwica} and its inflections, while a receipt prints that stem cut off after {@code w}.
 * <p>
 * Until 2026-10-17 this class also repaired tokens by a fixed, ordered family of letter swaps
 * measured on three scans. Next to the search those swaps cost more than they fixed on the
 * benchmark, so they were removed.
 * <p>
 * The Polish list is the sjp.pl alternative dictionary, which its authors offer under Apache-2.0
 * (also GPL-2, LGPL-2.1, MPL-1.1, or CC-BY-4.0). The English list is SCOWL, copyright 2000-2011
 * Kevin Atkinson, usable for any purpose with that notice kept. Both files are optional.
 * {@link #optional(Path)} returns null when either file is missing.
 */
public final class Lexicon implements Vocabulary {
    public static final String POLISH_FILE = "lexicon-pl.txt.gz";
    public static final String ENGLISH_FILE = "lexicon-en.txt.gz";

    private final WordList polish;
    private final WordList english;

    private Lexicon(WordList polish, WordList english) {
        this.polish = polish;
        this.english = english;
    }

    /** Both gzip lists from a model directory, or null when either one is absent. */
    public static Lexicon optional(Path modelDirectory) {
        if (modelDirectory == null) {
            return null;
        }
        Path polish = modelDirectory.resolve(POLISH_FILE);
        Path english = modelDirectory.resolve(ENGLISH_FILE);
        if (!Files.isRegularFile(polish) || !Files.isRegularFile(english)) {
            return null;
        }
        return new Lexicon(WordList.load(polish), WordList.load(english));
    }

    /** Both gzip lists already in memory, for files that come from a JAR. The labels name them in errors. */
    public static Lexicon read(byte[] polish, String polishLabel, byte[] english, String englishLabel) {
        return new Lexicon(WordList.sorted(polish, true, polishLabel), WordList.sorted(english, true, englishLabel));
    }

    static Lexicon of(String[] polish, String[] english) {
        return new Lexicon(WordList.of(polish), WordList.of(english));
    }

    /** Whether a lowercase word is on either list. */
    @Override
    public boolean word(String lower) {
        return english.contains(lower) || polish.contains(lower) || "polędw".equals(lower);
    }

    /** Whether a lowercase string is a listed word or starts one. */
    @Override
    public boolean prefix(String lower) {
        return word(lower) || polish.extendsPrefix(lower) || english.extendsPrefix(lower)
                || "polędw".startsWith(lower);
    }
}
