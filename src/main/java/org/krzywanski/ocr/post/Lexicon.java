package org.krzywanski.ocr.post;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Repairs a recognition token when the dictionary has exactly one equally shaped word.
 * The token has to be mixed or lower case, absent from both lists, and between 4 and 24 letters.
 * A single Polish diacritic is tried first, then two, then {@code t} read as {@code ł}, then {@code i}
 * read as {@code j}. Then {@code ó}, {@code e} or {@code o} read as {@code ć}, alone or together with
 * {@code s} read as {@code ś}, then up to two {@code l} read as {@code i} or {@code j}, then a single
 * {@code o} read as {@code c}. An all-capitals token that is still unknown is replaced when exactly
 * one dictionary word is one inserted letter and one of those diacritics away. Each of those steps
 * applies only when exactly one dictionary word matches. A lowercase one-letter preposition glued
 * to the next word is split.
 * <p>
 * The Polish list is the sjp.pl alternative dictionary, which its authors offer under Apache-2.0
 * (also GPL-2, LGPL-2.1, MPL-1.1, or CC-BY-4.0). The English list is SCOWL, copyright 2000-2011
 * Kevin Atkinson, usable for any purpose with that notice kept. Both files are optional.
 * {@link #optional(Path)} does nothing when either file is missing, so an engine without them
 * reads exactly as before. The stem {@code polędw} is always known: sjp lists {@code polędwica}
 * and its inflections, while a receipt prints that stem cut off after {@code w}.
 */
public final class Lexicon {
    public static final String POLISH_FILE = "lexicon-pl.txt.gz";
    public static final String ENGLISH_FILE = "lexicon-en.txt.gz";
    private static final String BASE = "acelnosz";
    private static final String[] ALT = {"ą", "ć", "ę", "ł", "ń", "ó", "ś", "źż"};
    /** Letters that may be the one character missing from an all-capitals token. */
    private static final String GAP_ALPHABET = "aąbcćdeęfghijklłmnńoópqrsśtuvwxyzźż";
    /** Sentinel for two different dictionary hits. Not a word the lists can contain. */
    private static final String AMBIGUOUS = "\0";

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

    static Lexicon of(String[] polish, String[] english) {
        return new Lexicon(WordList.of(polish), WordList.of(english));
    }

    /**
     * The line with each letter-run repaired. A run of digits glued to a known word of at least
     * four letters is split, so a quantity does not stay stuck to the name. Punctuation stays.
     */
    public String apply(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        StringBuilder out = new StringBuilder(line.length() + 8);
        int i = 0;
        while (i < line.length()) {
            int start = i;
            boolean letters = Character.isLetter(line.charAt(i));
            i++;
            while (i < line.length() && Character.isLetter(line.charAt(i)) == letters) {
                i++;
            }
            String token = line.substring(start, i);
            if (!letters && digits(token) && i < line.length() && Character.isLetter(line.charAt(i))) {
                int wordStart = i;
                while (i < line.length() && Character.isLetter(line.charAt(i))) {
                    i++;
                }
                String word = fix(line.substring(wordStart, i));
                if (word.length() >= 4 && dictionaryWord(word)) {
                    out.append(token).append(' ').append(word);
                    continue;
                }
                out.append(token).append(word);
                continue;
            }
            out.append(letters ? fix(token) : token);
        }
        return out.toString();
    }

    private static boolean digits(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return !token.isEmpty();
    }

    private boolean dictionaryWord(String token) {
        if (token.length() < 4 || token.length() > 24 || capitals(token)) {
            return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return english.contains(lower) || known(lower);
    }

    private String fix(String token) {
        if (token.length() < 4 || token.length() > 24) {
            return token;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (english.contains(lower) || known(lower)) {
            return token;
        }
        if (capitals(token)) {
            String gap = uniqueGap(lower);
            return gap == null ? token : gap.toUpperCase(Locale.ROOT);
        }
        String hit = unique(lower, 1);
        if (hit == null) {
            hit = unique(lower, 2);
        }
        if (hit == null) {
            hit = uniqueTee(lower);
        }
        if (hit == null) {
            hit = uniqueJay(lower);
        }
        if (hit == null) {
            hit = uniqueCaron(lower);
        }
        if (hit == null) {
            hit = uniqueEll(lower);
        }
        if (hit == null) {
            hit = uniqueCee(lower);
        }
        if (hit != null) {
            return matchCase(token, hit);
        }
        return glue(token, lower);
    }

    private String glue(String token, String lower) {
        if (!Character.isLowerCase(token.charAt(0)) || !preposition(lower.charAt(0))) {
            return token;
        }
        String rest = lower.substring(1);
        if (rest.length() < 4 || !known(rest)) {
            return token;
        }
        return token.charAt(0) + " " + token.substring(1);
    }

    private static boolean preposition(char c) {
        return c == 'i' || c == 'w' || c == 'z' || c == 'o' || c == 'u' || c == 'a';
    }

    private static boolean capitals(String token) {
        boolean letter = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            letter = true;
            if (Character.isLowerCase(c)) {
                return false;
            }
        }
        return letter;
    }

    private String unique(String lower, int edits) {
        List<String> hits = new ArrayList<>();
        collect(lower, edits, 0, 0, hits);
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private void collect(String word, int left, int index, int used, List<String> hits) {
        if (hits.size() > 1) {
            return;
        }
        if (index == word.length()) {
            if (used == left && known(word)) {
                hits.add(word);
            }
            return;
        }
        collect(word, left, index + 1, used, hits);
        if (used >= left) {
            return;
        }
        String alts = alternatives(word.charAt(index));
        for (int k = 0; k < alts.length(); ) {
            int cp = alts.codePointAt(k);
            k += Character.charCount(cp);
            collect(word.substring(0, index) + new String(Character.toChars(cp)) + word.substring(index + 1),
                    left, index + 1, used + 1, hits);
        }
    }

    private String uniqueTee(String lower) {
        String hit = null;
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) != 't') {
                continue;
            }
            String variant = lower.substring(0, i) + "ł" + lower.substring(i + 1);
            if (!known(variant)) {
                continue;
            }
            if (hit != null) {
                return null;
            }
            hit = variant;
        }
        return hit;
    }

    private String uniqueJay(String lower) {
        String hit = null;
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) != 'i') {
                continue;
            }
            String variant = lower.substring(0, i) + "j" + lower.substring(i + 1);
            if (!known(variant)) {
                continue;
            }
            if (hit != null) {
                return null;
            }
            hit = variant;
        }
        return hit;
    }

    /**
     * {@code ó}, {@code e} or {@code o} read as {@code ć}, with {@code s} allowed to become {@code ś}
     * in the same step. A repair that only adds {@code ś} stays with the diacritic pass above.
     */
    private String uniqueCaron(String lower) {
        List<String> hits = new ArrayList<>();
        collectCaron(lower, 0, 0, false, hits);
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private void collectCaron(String word, int index, int used, boolean caron, List<String> hits) {
        if (hits.size() > 1) {
            return;
        }
        if (index == word.length()) {
            if (caron && used <= 2 && known(word)) {
                hits.add(word);
            }
            return;
        }
        collectCaron(word, index + 1, used, caron, hits);
        if (used >= 2) {
            return;
        }
        char c = word.charAt(index);
        char alt = switch (c) {
            case 'ó', 'e', 'o' -> 'ć';
            case 's' -> 'ś';
            default -> 0;
        };
        if (alt == 0) {
            return;
        }
        String next = word.substring(0, index) + alt + word.substring(index + 1);
        collectCaron(next, index + 1, used + 1, caron || c != 's', hits);
    }

    /**
     * Up to two {@code l} read as {@code i} or {@code j}, when that spelling is the only dictionary word.
     */
    private String uniqueEll(String lower) {
        int count = 0;
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) == 'l') {
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        int[] at = new int[count];
        for (int i = 0, n = 0; i < lower.length(); i++) {
            if (lower.charAt(i) == 'l') {
                at[n++] = i;
            }
        }
        String hit = null;
        char[] alts = {'i', 'j'};
        for (int i = 0; i < at.length; i++) {
            for (char alt : alts) {
                hit = only(hit, swap(lower, at[i], alt));
                if (hit == AMBIGUOUS) {
                    return null;
                }
            }
        }
        for (int a = 0; a < at.length; a++) {
            for (int b = a + 1; b < at.length; b++) {
                for (char first : alts) {
                    for (char second : alts) {
                        StringBuilder variant = new StringBuilder(lower);
                        variant.setCharAt(at[a], first);
                        variant.setCharAt(at[b], second);
                        hit = only(hit, variant.toString());
                        if (hit == AMBIGUOUS) {
                            return null;
                        }
                    }
                }
            }
        }
        return hit;
    }

    private String only(String hit, String variant) {
        if (!known(variant)) {
            return hit;
        }
        if (hit != null && !hit.equals(variant)) {
            return AMBIGUOUS;
        }
        return variant;
    }

    private static String swap(String word, int index, char alt) {
        return word.substring(0, index) + alt + word.substring(index + 1);
    }

    /**
     * One inserted Polish letter and one diacritic, when that spelling is the only dictionary word.
     * Measured on the invoice watermark: {@code orygnal} reaches only {@code oryginał}.
     * {@code sprzedaz} reaches four words, so the fiscal line stays.
     */
    private String uniqueGap(String lower) {
        List<String> hits = new ArrayList<>(2);
        for (int i = 0; i <= lower.length() && hits.size() < 2; i++) {
            for (int a = 0; a < GAP_ALPHABET.length() && hits.size() < 2; a++) {
                char inserted = GAP_ALPHABET.charAt(a);
                String word = lower.substring(0, i) + inserted + lower.substring(i);
                oneDiacritic(word, hits);
            }
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private void oneDiacritic(String word, List<String> hits) {
        for (int i = 0; i < word.length() && hits.size() < 2; i++) {
            String alts = alternatives(word.charAt(i));
            for (int k = 0; k < alts.length() && hits.size() < 2; ) {
                int cp = alts.codePointAt(k);
                k += Character.charCount(cp);
                String variant = word.substring(0, i) + new String(Character.toChars(cp)) + word.substring(i + 1);
                if (known(variant) && !hits.contains(variant)) {
                    hits.add(variant);
                }
            }
        }
    }

    /** One {@code o} read as {@code c}, when that spelling is the only dictionary word. */
    private String uniqueCee(String lower) {
        String hit = null;
        for (int i = 0; i < lower.length(); i++) {
            if (lower.charAt(i) != 'o') {
                continue;
            }
            String variant = lower.substring(0, i) + 'c' + lower.substring(i + 1);
            if (!known(variant)) {
                continue;
            }
            if (hit != null) {
                return null;
            }
            hit = variant;
        }
        return hit;
    }

    /** File list, or the one stem sjp does not spell on its own. */
    private boolean known(String word) {
        return polish.contains(word) || "polędw".equals(word);
    }

    private static String alternatives(char c) {
        int at = BASE.indexOf(c);
        return at < 0 ? "" : ALT[at];
    }

    private static String matchCase(String src, String hit) {
        StringBuilder out = new StringBuilder(hit.length());
        for (int i = 0; i < hit.length(); i++) {
            char c = hit.charAt(i);
            if (i < src.length() && Character.isUpperCase(src.charAt(i))) {
                c = Character.toUpperCase(c);
            }
            out.append(c);
        }
        return out.toString();
    }
}
