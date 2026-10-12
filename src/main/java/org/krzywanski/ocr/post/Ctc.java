package org.krzywanski.ocr.post;

import org.krzywanski.ocr.OcrException;
import org.krzywanski.ocr.runtime.Tensor;

import java.util.Arrays;

/** Greedy CTC. Blank is index 0. Repeats are collapsed and blanks are dropped. */
public final class Ctc {
    public record Result(String text, float score) {
    }

    private Ctc() {
    }

    public static Result greedy(Tensor tensor, Charset charset) {
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
        return greedy(packed, time, classes, charset);
    }

    public static Result greedy(float[] data, int time, int classes, Charset charset) {
        if (time <= 0 || classes <= 0) {
            return new Result("", 0f);
        }
        float[] probs = probabilities(data, time, classes) ? data : softmax(data, time, classes);
        StringBuilder text = new StringBuilder();
        double score = 0;
        int kept = 0;
        int previous = -1;
        for (int t = 0; t < time; t++) {
            int arg = 0;
            float best = probs[t * classes];
            for (int c = 1; c < classes; c++) {
                float v = probs[t * classes + c];
                if (v > best) {
                    best = v;
                    arg = c;
                }
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
