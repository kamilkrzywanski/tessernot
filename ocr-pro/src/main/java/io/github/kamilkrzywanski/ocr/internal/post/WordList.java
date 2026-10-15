package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Sorted UTF-8 words, one per line, searched without building a string per entry.
 * The bytes stay in Unicode code-point order, which matches {@link String#compareTo} for these letters.
 */
final class WordList {
    private final byte[] data;
    private final int[] start;

    private WordList(byte[] data, int[] start) {
        this.data = data;
        this.start = start;
    }

    static WordList load(Path path) {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new OcrException("cannot read word list " + path + ": " + e.getMessage(), e);
        }
        return read(data, path.getFileName().toString().endsWith(".gz"), path.toString());
    }

    /** A list already in memory, gzip or plain. {@code label} names it in errors. */
    static WordList read(byte[] data, boolean gzip, String label) {
        try {
            byte[] raw = data;
            if (gzip) {
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
                    raw = in.readAllBytes();
                }
            }
            return new WordList(raw, offsets(raw));
        } catch (IOException e) {
            throw new OcrException("cannot read word list " + label + ": " + e.getMessage(), e);
        }
    }

    /** Test lists. Words are lowercased and sorted here, so the caller can pass them in any order. */
    static WordList of(String... words) {
        String[] sorted = words.clone();
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = sorted[i].toLowerCase(java.util.Locale.ROOT);
        }
        Arrays.sort(sorted);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String previous = "";
        for (String word : sorted) {
            if (word.isEmpty() || word.equals(previous)) {
                continue;
            }
            previous = word;
            out.writeBytes(word.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
        }
        byte[] raw = out.toByteArray();
        return new WordList(raw, offsets(raw));
    }

    boolean contains(String word) {
        if (word == null || word.isEmpty() || start.length == 0) {
            return false;
        }
        byte[] key = word.getBytes(StandardCharsets.UTF_8);
        int lo = 0;
        int hi = start.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = compare(mid, key);
            if (cmp == 0) {
                return true;
            }
            if (cmp < 0) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return false;
    }

    /** Whether a longer word starts with {@code prefix}. */
    boolean extendsPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || start.length == 0) {
            return false;
        }
        byte[] key = prefix.getBytes(StandardCharsets.UTF_8);
        int lo = 0;
        int hi = start.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(mid, key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        // The first entry at or after the prefix is the prefix itself or a longer word that starts with it.
        for (int line = lo; line < start.length && line <= lo + 1; line++) {
            if (lineEnd(line) - start[line] > key.length && startsWith(line, key)) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWith(int line, byte[] key) {
        int i = start[line];
        if (lineEnd(line) - i < key.length) {
            return false;
        }
        for (int k = 0; k < key.length; k++) {
            if (data[i + k] != key[k]) {
                return false;
            }
        }
        return true;
    }

    private int compare(int line, byte[] key) {
        int i = start[line];
        int end = lineEnd(line);
        int k = 0;
        while (i < end && k < key.length) {
            int left = data[i] & 255;
            int right = key[k] & 255;
            if (left != right) {
                return left - right;
            }
            i++;
            k++;
        }
        return Integer.compare(end - i, key.length - k);
    }

    private int lineEnd(int line) {
        int i = start[line];
        while (i < data.length && data[i] != '\n') {
            i++;
        }
        return i;
    }

    private static int[] offsets(byte[] raw) {
        if (raw.length == 0) {
            return new int[0];
        }
        List<Integer> at = new ArrayList<>();
        if (raw[0] != '\n') {
            at.add(0);
        }
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '\n' && i + 1 < raw.length && raw[i + 1] != '\n') {
                at.add(i + 1);
            }
        }
        int[] start = new int[at.size()];
        for (int i = 0; i < at.size(); i++) {
            start[i] = at.get(i);
        }
        return start;
    }
}
