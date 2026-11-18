package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.internal.runtime.Tensor;

import java.util.Arrays;

/**
 * Greedy CTC. Blank is index 0. Repeats are collapsed and blanks are dropped.
 * <p>
 * With a {@link RunnerUps} table, which {@code Corrections.POLISH} brings, a few runner-ups win:
 * the class that came second replaces the winner of a run of timesteps where that table says so.
 * The score of a character is the probability of the winner on the first timestep of its run,
 * also when a runner-up replaced it: the score says how sure the recognizer is that a character
 * stands there, and a correction must not make a line look like noise.
 */
public final class Ctc {
    /**
     * A line as read. {@code from} and {@code to} say where each character of {@code text}
     * stands along the line, as fractions of the recognizer's timesteps: a character that came
     * out of timesteps {@code first..last} runs from {@code (first - 1) / time} to
     * {@code (last + 1) / time}. The first timestep of a character lies about one timestep right
     * of its left edge: on rendered lines that correction puts the start of a word within 0.06
     * line heights of the truth in the median, the same as its end. Both arrays are null when the
     * text did not come from one read, as after a second read of the Polish profile.
     */
    public record Result(String text, float score, float[] from, float[] to) {
        public Result(String text, float score) {
            this(text, score, null, null);
        }

        /** The same read with its positions multiplied by {@code scale} and held to 1: from timesteps to a share of the crop. */
        public Result scaled(float scale) {
            if (from == null) {
                return this;
            }
            float[] a = new float[from.length];
            float[] b = new float[to.length];
            for (int i = 0; i < a.length; i++) {
                a[i] = Math.min(1f, from[i] * scale);
                b[i] = Math.min(1f, to[i] * scale);
            }
            return new Result(text, score, a, b);
        }
    }

    /**
     * Nats a listed word may lose to the greedy word on the same timesteps: the image may make it up
     * to e^4, about 55 times, less likely. Set before any measurement and kept.
     */
    static final double MARGIN = 4.0;

    private Ctc() {
    }

    public static Result greedy(Tensor tensor, Charset charset) {
        return greedy(tensor, charset, false);
    }

    public static Result greedy(Tensor tensor, Charset charset, boolean corrected) {
        return greedy(tensor, charset, corrected, null);
    }

    /** The Polish runner-up rules when {@code corrected}; a caller that decodes many lines builds the table once. */
    public static Result greedy(Tensor tensor, Charset charset, boolean corrected, Vocabulary words) {
        return greedy(tensor, charset, corrected ? RunnerUps.polish(charset) : null, words);
    }

    /** With a vocabulary, unlisted words are searched again on their timesteps, see {@link CtcWords}. */
    public static Result greedy(Tensor tensor, Charset charset, RunnerUps rules, Vocabulary words) {
        if (tensor == null || tensor.f == null) {
            throw new OcrException("CTC expects a float tensor");
        }
        int classes = charset.size();
        int axis = -1;
        for (int i = tensor.dims.length - 1; i >= 0; i--) {
            if (tensor.dims[i] == classes) {
                axis = i;
                break;
            }
        }
        if (axis < 0) {
            throw new OcrException("CTC classes " + classes + " not in shape " + Arrays.toString(tensor.dims));
        }
        int before = 1;
        for (int i = 0; i < axis; i++) {
            before *= tensor.dims[i];
        }
        int after = 1;
        for (int i = axis + 1; i < tensor.dims.length; i++) {
            after *= tensor.dims[i];
        }
        // Rank 2 is [time, classes]. A leading batch is present only when another axis sits between it and the classes.
        int batch = tensor.dims.length >= 3 && axis > 0 ? tensor.dims[0] : 1;
        int timeOuter = before / Math.max(batch, 1);
        int time = timeOuter * after;
        int n = Math.max(time, 0) * classes;
        float[] packed;
        if (after == 1) {
            // [time, classes] or [batch, time, classes]: the first item is already in that order,
            // and the decoder only reads it. The usual [1, T, C] head copies nothing here.
            packed = tensor.f.length == n ? tensor.f : Arrays.copyOf(tensor.f, n);
        } else {
            packed = new float[n];
            for (int outer = 0; outer < timeOuter; outer++) {
                for (int c = 0; c < classes; c++) {
                    int base = (outer * classes + c) * after;
                    for (int inner = 0; inner < after; inner++) {
                        packed[(outer * after + inner) * classes + c] = tensor.f[base + inner];
                    }
                }
            }
        }
        return greedy(packed, time, classes, charset, rules, words);
    }

    public static Result greedy(float[] data, int time, int classes, Charset charset) {
        return greedy(data, time, classes, charset, false);
    }

    public static Result greedy(float[] data, int time, int classes, Charset charset, boolean corrected) {
        return greedy(data, time, classes, charset, corrected, null);
    }

    public static Result greedy(float[] data, int time, int classes, Charset charset, boolean corrected,
                                Vocabulary words) {
        return greedy(data, time, classes, charset, corrected ? RunnerUps.polish(charset) : null, words);
    }

    public static Result greedy(float[] data, int time, int classes, Charset charset, RunnerUps rules,
                                Vocabulary words) {
        if (time <= 0 || classes <= 0) {
            return new Result("", 0f);
        }
        float[] probs = probabilities(data, time, classes) ? data : softmax(data, time, classes);
        // The winner of every timestep, and the class that came second with its probability.
        int[] winner = new int[time];
        int[] second = new int[time];
        float[] secondBest = new float[time];
        for (int t = 0; t < time; t++) {
            int base = t * classes;
            int arg = 0;
            float best = probs[base];
            int next = -1;
            float nextBest = -1f;
            for (int c = 1; c < classes; c++) {
                float v = probs[base + c];
                if (v > best) {
                    nextBest = best;
                    next = arg;
                    best = v;
                    arg = c;
                } else if (v > nextBest) {
                    nextBest = v;
                    next = c;
                }
            }
            winner[t] = arg;
            second[t] = next;
            secondBest[t] = nextBest;
        }
        StringBuilder text = new StringBuilder();
        double score = 0;
        int kept = 0;
        int previous = -1;
        int[] path = new int[time];
        for (int t = 0; t < time; ) {
            int arg = winner[t];
            int end = t + 1;
            while (end < time && winner[end] == arg) {
                end++;
            }
            if (arg == 0) {
                // Blanks emit nothing, except the one right after a colon that the rules read as a space.
                for (int f = t; f < end; f++) {
                    int emitted = 0;
                    if (rules != null && text.length() > 0 && text.charAt(text.length() - 1) == ':') {
                        emitted = Math.max(0, rules.spaceAfterColon(second[f], secondBest[f]));
                    }
                    if (emitted != 0 && emitted != previous) {
                        text.append(charset.at(emitted));
                        score += probs[f * classes];
                        kept++;
                    }
                    previous = emitted;
                    path[f] = emitted;
                }
            } else {
                int emitted = arg;
                if (rules != null) {
                    boolean digitBefore = afterDigit(text);
                    for (int f = t; f < end; f++) {
                        int swapped = rules.swap(arg, second[f], secondBest[f], digitBefore);
                        if (swapped >= 0) {
                            emitted = swapped;
                            break;
                        }
                    }
                }
                if (emitted != previous) {
                    text.append(charset.at(emitted));
                    score += probs[t * classes + arg];
                    kept++;
                }
                previous = emitted;
                Arrays.fill(path, t, end, emitted);
            }
            t = end;
        }
        String decoded = words == null ? text.toString()
                : CtcWords.decode(probs, time, classes, charset, path, words, MARGIN);
        float[] from = new float[decoded.length()];
        float[] to = new float[decoded.length()];
        boolean placed = place(path, time, charset, from, to);
        return new Result(decoded, kept == 0 ? 0f : (float) (score / kept), placed ? from : null, placed ? to : null);
    }

    /**
     * Where each character of the decoded text stands, from the timesteps of {@code path} it came
     * out of. The word search changes letters and never their number, so the characters still
     * follow the path one for one. False when they do not add up, and nothing is known then.
     */
    private static boolean place(int[] path, int time, Charset charset, float[] from, float[] to) {
        int at = 0;
        int previous = 0;
        int first = 0;
        for (int t = 0; t <= time; t++) {
            int label = t < time ? path[t] : 0;
            if (label == previous) {
                continue;
            }
            if (previous != 0) {
                int length = charset.at(previous).length();
                if (at + length > from.length) {
                    return false;
                }
                for (int i = 0; i < length; i++) {
                    from[at + i] = Math.max(0, first - 1) / (float) time;
                    to[at + i] = t / (float) time;
                }
                at += length;
            }
            previous = label;
            first = t;
        }
        return at == from.length;
    }

    /** A decimal comma only follows a digit; a period after a word ends a sentence. */
    private static boolean afterDigit(StringBuilder text) {
        return text.length() > 0 && Character.isDigit(text.charAt(text.length() - 1));
    }

    /** True when the first few timesteps already look like a probability distribution. */
    public static boolean probabilities(float[] data, int time, int classes) {
        int probe = Math.min(time, 4);
        if (probe <= 0) {
            return true;
        }
        for (int t = 0; t < probe; t++) {
            float sum = 0f;
            int base = t * classes;
            for (int c = 0; c < classes; c++) {
                float v = data[base + c];
                if (v < -0.01f || v > 1.01f) {
                    return false;
                }
                sum += v;
            }
            if (sum < 0.98f || sum > 1.02f) {
                return false;
            }
        }
        return true;
    }

    public static float[] softmax(float[] data, int time, int classes) {
        float[] out = new float[time * classes];
        for (int t = 0; t < time; t++) {
            int base = t * classes;
            float max = Float.NEGATIVE_INFINITY;
            for (int c = 0; c < classes; c++) {
                max = Math.max(max, data[base + c]);
            }
            double sum = 0;
            for (int c = 0; c < classes; c++) {
                double e = Math.exp(data[base + c] - max);
                out[base + c] = (float) e;
                sum += e;
            }
            float inv = (float) (1.0 / sum);
            for (int c = 0; c < classes; c++) {
                out[base + c] *= inv;
            }
        }
        return out;
    }

    /** One row. Used by the 2-class / 4-class orientation head, which may emit logits or probabilities. */
    public static float[] softmaxRow(float[] row) {
        if (probabilities(row, 1, row.length)) {
            return row;
        }
        return softmax(row, 1, row.length);
    }
}
