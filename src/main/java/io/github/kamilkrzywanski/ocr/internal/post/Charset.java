package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.io.IOException;
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
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new OcrException("cannot read dict " + path + ": " + e.getMessage(), e);
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
