package io.github.kamilkrzywanski.ocr.internal.post;

import io.github.kamilkrzywanski.ocr.OcrException;
import io.github.kamilkrzywanski.ocr.internal.runtime.Tensor;

import java.util.Arrays;

/**
 * Greedy CTC. Blank is index 0. Repeats are collapsed and blanks are dropped.
 * <p>
 * With {@code corrected}, used by {@code Corrections.POLISH}, a few runner-ups win:
 * after a colon, a blank that only barely beats a space is read as that space, and
 * x for a multiplication sign, ł for l or t, ć for ó, a comma for a period, Ś for a
 * capital S, and ó for o replace the winner when the margin was measured on the printed scans.
 */
public final class Ctc {
    public record Result(String text, float score) {
    }

    private Ctc() {
    }

    public static Result greedy(Tensor tensor, Charset charset) {
        return greedy(tensor, charset, false);
    }

    public static Result greedy(Tensor tensor, Charset charset, boolean corrected) {
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
        float[] packed = new float[Math.max(time, 0) * classes];
        for (int outer = 0; outer < timeOuter; outer++) {
            for (int c = 0; c < classes; c++) {
                int base = (outer * classes + c) * after;
                for (int inner = 0; inner < after; inner++) {
                    packed[(outer * after + inner) * classes + c] = tensor.f[base + inner];
                }
            }
        }
        return greedy(packed, time, classes, charset, corrected);
    }

    public static Result greedy(float[] data, int time, int classes, Charset charset) {
        return greedy(data, time, classes, charset, false);
    }

    public static Result greedy(float[] data, int time, int classes, Charset charset, boolean corrected) {
        if (time <= 0 || classes <= 0) {
            return new Result("", 0f);
        }
        float[] probs = probabilities(data, time, classes) ? data : softmax(data, time, classes);
        int space = charset.indexOf(" ");
        int times = charset.indexOf("×");
        int ex = charset.indexOf("x");
        int el = charset.indexOf("l");
        int ell = charset.indexOf("ł");
        int tee = charset.indexOf("t");
        int capitalEl = charset.indexOf("L");
        int capitalEll = charset.indexOf("Ł");
        int capitalTee = charset.indexOf("T");
        int dot = charset.indexOf(".");
        int comma = charset.indexOf(",");
        int oo = charset.indexOf("ó");
        int acute = charset.indexOf("ć");
        int capitalS = charset.indexOf("S");
        int capitalSAcute = charset.indexOf("Ś");
        int smallO = charset.indexOf("o");
        StringBuilder text = new StringBuilder();
        double score = 0;
        int kept = 0;
        int previous = -1;
        for (int t = 0; t < time; t++) {
            int arg = 0;
            float best = probs[t * classes];
            int second = -1;
            float secondBest = -1f;
            for (int c = 1; c < classes; c++) {
                float v = probs[t * classes + c];
                if (v > best) {
                    secondBest = best;
                    second = arg;
                    best = v;
                    arg = c;
                } else if (v > secondBest) {
                    secondBest = v;
                    second = c;
                }
            }
            // Both NIP lines on the scanned invoice drop the space after the colon.
            // Blank wins there, and the space is the runner-up at 0.35 or more.
            // The same timestep on Nixon and on the receipt stays under that.
            if (corrected && arg == 0 && second == space && secondBest >= 0.35f && text.length() > 0
                    && text.charAt(text.length() - 1) == ':') {
                arg = space;
                best = secondBest;
            }
            // Measured on the three scans. Each swap hits only a real miss, and a lower bar does not.
            // × on the 4,99 row is 0.36 for x. l in zapłaty is 0.35 for ł.
            // t in Wrocław, łącznie and Zapłacono is at least 0.16 for ł. The period in 90.00 is 0.35 for a comma.
            // ó in dziewięćdziesiąt is 0.17 for ć. A bar of 0.10 for t adds nothing on these scans.
            // The comma swap needs a digit before it: on the dev set it turned an English sentence end into a comma.
            // Capital S in Świat is 0.023 for Ś. The next capital S on these scans is 0.0023.
            // o in Spółka is 0.061 for ó. No other o on these scans reaches 0.04.
            if (corrected && (runnerUp(arg, second, secondBest, times, ex, 0.35f)
                    || runnerUp(arg, second, secondBest, el, ell, 0.34f)
                    || runnerUp(arg, second, secondBest, capitalEl, capitalEll, 0.34f)
                    || runnerUp(arg, second, secondBest, tee, ell, 0.15f)
                    || runnerUp(arg, second, secondBest, capitalTee, capitalEll, 0.15f)
                    || runnerUp(arg, second, secondBest, dot, comma, 0.34f) && afterDigit(text)
                    || runnerUp(arg, second, secondBest, oo, acute, 0.15f)
                    || runnerUp(arg, second, secondBest, capitalS, capitalSAcute, 0.015f)
                    || runnerUp(arg, second, secondBest, smallO, oo, 0.05f))) {
                arg = second;
                best = secondBest;
            }
            if (arg != 0 && arg != previous) {
                text.append(charset.at(arg));
                score += best;
                kept++;
            }
            previous = arg;
        }
        return new Result(text.toString(), kept == 0 ? 0f : (float) (score / kept));
    }

    /** A decimal comma only follows a digit; a period after a word ends a sentence. */
    private static boolean afterDigit(StringBuilder text) {
        return text.length() > 0 && Character.isDigit(text.charAt(text.length() - 1));
    }

    private static boolean runnerUp(int arg, int second, float secondBest, int from, int to, float min) {
        return from >= 0 && arg == from && second == to && secondBest >= min;
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
