package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.OcrException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * Sorted UTF-8 words, one per line, searched without building a string per entry.
 * The bytes stay in Unicode code-point order, which matches {@link String#compareTo} for these letters.
 */
final class WordList implements Vocabulary {
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
        return sorted(data, path.getFileName().toString().endsWith(".gz"), path.toString());
    }

    /**
     * A list that ships next to the models, gzip or plain, and must already be what the search
     * needs: one word per {@code \n}, lowercase, strictly increasing by its bytes. A file in
     * another order or with {@code \r\n} line ends would find nothing and say nothing, so it fails
     * here. {@code label} names it in errors.
     */
    static WordList sorted(byte[] data, boolean gzip, String label) {
        byte[] raw = bytes(data, gzip, label);
        int line = firstUnsorted(raw);
        if (line >= 0) {
            throw new OcrException("word list " + label + " is not sorted lowercase words, one per line: line "
                    + (line + 1) + " (sort it by bytes with LC_ALL=C sort -u, with \\n line ends)");
        }
        return new WordList(raw, offsets(raw));
    }

    /**
     * The first line that is empty, holds a control character, a space or an ASCII capital, or
     * does not come after the line before it; -1 when there is none. Bytes only, so four million
     * words take a fraction of a second.
     */
    private static int firstUnsorted(byte[] raw) {
        int line = 0;
        int previous = -1;
        int previousEnd = -1;
        int start = 0;
        for (int i = 0; i <= raw.length; i++) {
            if (i < raw.length && raw[i] != '\n') {
                int b = raw[i] & 255;
                if (b <= ' ' || b >= 'A' && b <= 'Z') {
                    return line;
                }
                continue;
            }
            if (i == start) {
                if (i < raw.length) {
                    return line;
                }
                break;
            }
            if (previous >= 0 && Arrays.compareUnsigned(raw, previous, previousEnd, raw, start, i) >= 0) {
                return line;
            }
            previous = start;
            previousEnd = i;
            start = i + 1;
            line++;
        }
        return -1;
    }

    private static byte[] bytes(byte[] data, boolean gzip, String label) {
        if (!gzip) {
            return data;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new OcrException("cannot read word list " + label + ": " + e.getMessage(), e);
        }
    }

    /**
     * A list in any order and case, as {@link Vocabulary#read} describes it. The words are sorted
     * by their UTF-8 bytes, the order the binary search compares in.
     */
    static WordList normalized(byte[] data, String label) {
        boolean gzip = data.length >= 2 && (data[0] & 255) == 0x1f && (data[1] & 255) == 0x8b;
        byte[] raw = bytes(data, gzip, label);
        if (ready(raw)) {
            return new WordList(raw, offsets(raw));
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new OcrException("cannot read word list " + label + ": not UTF-8", e);
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<byte[]> words = new ArrayList<>();
        for (String line : text.split("\\R")) {
            // İ on its own: toLowerCase would leave an i and a combining dot, which no reading has.
            String word = Normalizer.normalize(line.strip(), Normalizer.Form.NFC).replace('İ', 'i')
                    .toLowerCase(Locale.ROOT);
            if (!word.isEmpty() && word.codePoints().noneMatch(Character::isWhitespace)) {
                words.add(word.getBytes(StandardCharsets.UTF_8));
            }
        }
        words.sort(Arrays::compareUnsigned);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        byte[] previous = null;
        for (byte[] word : words) {
            if (previous != null && Arrays.equals(previous, word)) {
                continue;
            }
            previous = word;
            out.writeBytes(word);
            out.write('\n');
        }
        byte[] sorted = out.toByteArray();
        return new WordList(sorted, offsets(sorted));
    }

    /**
     * Whether the bytes are already what {@link #normalized} would build: valid UTF-8, one word per
     * {@code \n}, lowercase, NFC, strictly increasing. One pass, and only a line with non-ASCII bytes
     * is decoded, so a large sorted list loads without a copy per word.
     */
    private static boolean ready(byte[] raw) {
        int start = 0;
        int previous = -1;
        int previousEnd = -1;
        var decoder = StandardCharsets.UTF_8.newDecoder();
        for (int i = 0; i <= raw.length; i++) {
            if (i < raw.length && raw[i] != '\n') {
                continue;
            }
            if (i == start) {
                if (i < raw.length) {
                    return false;
                }
                break;
            }
            boolean ascii = true;
            for (int k = start; k < i; k++) {
                int b = raw[k] & 255;
                if (b >= 0x80) {
                    ascii = false;
                } else if (b <= ' ' || b >= 'A' && b <= 'Z') {
                    return false;
                }
            }
            if (!ascii) {
                String word;
                try {
                    word = decoder.decode(java.nio.ByteBuffer.wrap(raw, start, i - start)).toString();
                } catch (java.nio.charset.CharacterCodingException e) {
                    return false;
                }
                if (!Normalizer.isNormalized(word, Normalizer.Form.NFC) || !word.equals(word.toLowerCase(Locale.ROOT))
                        || word.codePoints().anyMatch(Character::isWhitespace) || word.charAt(0) == '\uFEFF') {
                    return false;
                }
            }
            if (previous >= 0 && Arrays.compareUnsigned(raw, previous, previousEnd, raw, start, i) >= 0) {
                return false;
            }
            previous = start;
            previousEnd = i;
            start = i + 1;
        }
        return true;
    }

    @Override
    public boolean word(String lower) {
        return contains(lower);
    }

    @Override
    public boolean prefix(String lower) {
        return contains(lower) || extendsPrefix(lower);
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
        // Two passes over the bytes: a boxed list of four million line starts costs more than the words.
        int count = raw[0] != '\n' ? 1 : 0;
        for (int i = 0; i + 1 < raw.length; i++) {
            if (raw[i] == '\n' && raw[i + 1] != '\n') {
                count++;
            }
        }
        int[] start = new int[count];
        int n = 0;
        if (raw[0] != '\n') {
            start[n++] = 0;
        }
        for (int i = 0; i + 1 < raw.length; i++) {
            if (raw[i] == '\n' && raw[i + 1] != '\n') {
                start[n++] = i + 1;
            }
        }
        return start;
    }
}
