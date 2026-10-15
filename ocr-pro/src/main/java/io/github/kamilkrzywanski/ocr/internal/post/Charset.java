package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CTC labels. Index 0 is the blank. Empty dict lines are skipped and character text is not trimmed.
 * {@code useSpaceChar} appends a single space. The PP-OCRv6 small dict then ends on that space,
 * and the head has 18710 classes.
 */
public final class Charset {
    private final String[] tokens;

    private Charset(String[] tokens) {
        this.tokens = tokens;
    }

    public static Charset of(String... characters) {
        String[] tokens = new String[characters.length + 1];
        tokens[0] = "";
        System.arraycopy(characters, 0, tokens, 1, characters.length);
        return new Charset(tokens);
    }

    public static Charset load(Path path, boolean useSpaceChar) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new OcrException("cannot read dict " + path + ": " + e.getMessage(), e);
        }
        return load(data, path.toString(), useSpaceChar);
    }

    /** A dict already in memory, strict UTF-8 as {@link Files#readAllLines} reads it. {@code label} names it in errors. */
    public static Charset load(byte[] data, String label, boolean useSpaceChar) {
        List<String> lines;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
            lines = new BufferedReader(new StringReader(text)).lines().toList();
        } catch (CharacterCodingException e) {
            throw new OcrException("cannot read dict " + label + ": " + e.getMessage(), e);
        }
        List<String> tokens = new ArrayList<>();
        tokens.add("");
        boolean first = true;
        for (String line : lines) {
            if (first) {
                first = false;
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1);
                }
            }
            if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                continue;
            }
            tokens.add(line);
        }
        if (useSpaceChar) {
            tokens.add(" ");
        }
        return new Charset(tokens.toArray(String[]::new));
    }

    public int size() {
        return tokens.length;
    }

    /**
     * Indices to keep for Latin-script text, in order: the CTC blank, then every token whose
     * characters are Latin or Greek letters, or digits, punctuation, symbols and marks shared by
     * scripts. CJK punctuation, full-width forms and the other CJK symbol blocks are left out.
     */
    public int[] latin() {
        List<Integer> keep = new ArrayList<>();
        keep.add(0);
        for (int i = 1; i < tokens.length; i++) {
            if (!tokens[i].isEmpty() && tokens[i].codePoints().allMatch(Charset::latinCodePoint)) {
                keep.add(i);
            }
        }
        int[] out = new int[keep.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = keep.get(i);
        }
        return out;
    }

    private static boolean latinCodePoint(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        if (block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS
                || block == Character.UnicodeBlock.VERTICAL_FORMS
                || block == Character.UnicodeBlock.KANBUN
                || block == Character.UnicodeBlock.IDEOGRAPHIC_DESCRIPTION_CHARACTERS) {
            return false;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.LATIN
                || script == Character.UnicodeScript.GREEK
                || script == Character.UnicodeScript.COMMON
                || script == Character.UnicodeScript.INHERITED;
    }

    /** The tokens at {@code keep}, in that order; index 0 must be the blank. */
    public Charset subset(int[] keep) {
        String[] out = new String[keep.length];
        for (int i = 0; i < keep.length; i++) {
            out[i] = tokens[keep[i]];
        }
        return new Charset(out);
    }

    public String at(int index) {
        if (index < 0 || index >= tokens.length) {
            return "";
        }
        return tokens[index];
    }

    public int indexOf(String token) {
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equals(token)) {
                return i;
            }
        }
        return -1;
    }
}
