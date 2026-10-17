package io.github.kamilkrzywanski.ocr.internal.post;

import java.util.List;

/**
 * Words the CTC lexicon search may read, in lowercase. Any language works: a list is only its
 * words, with no rules for its letters. See {@link CtcWords}.
 */
public interface Vocabulary {
    /** Whether a lowercase word is listed. */
    boolean word(String lower);

    /** Whether a lowercase string is a listed word or starts one. */
    boolean prefix(String lower);

    /**
     * A user list: UTF-8, one word per line, plain or gzip (told apart by its first two bytes), in
     * any order and case. Words are lowercased and NFC-normalized; an empty line or a line with a
     * space is skipped. {@code label} names the list in errors.
     */
    static Vocabulary read(byte[] data, String label) {
        return WordList.normalized(data, label);
    }

    /** Every word of every list, or null when there is none. */
    static Vocabulary of(List<? extends Vocabulary> lists) {
        List<Vocabulary> all = List.copyOf(lists);
        if (all.isEmpty()) {
            return null;
        }
        if (all.size() == 1) {
            return all.get(0);
        }
        return new Vocabulary() {
            @Override
            public boolean word(String lower) {
                for (Vocabulary list : all) {
                    if (list.word(lower)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean prefix(String lower) {
                for (Vocabulary list : all) {
                    if (list.prefix(lower)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
