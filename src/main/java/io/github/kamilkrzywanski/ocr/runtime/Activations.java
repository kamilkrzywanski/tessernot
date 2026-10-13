package io.github.kamilkrzywanski.ocr.runtime;

final class Activations {
    private Activations() {
    }

    static float apply(float x, String act, float alpha, float beta) {
        if (act == null) {
            return x;
        }
        return switch (act) {
            case "Relu" -> x > 0f ? x : 0f;
            case "Sigmoid" -> sigmoid(x);
            case "HardSigmoid" -> clamp(alpha * x + beta);
            case "HardSwish" -> x * clamp(alpha * x + beta);
            default -> x;
        };
    }

    static float sigmoid(float x) {
        if (x >= 0f) {
            float z = (float) Math.exp(-x);
            return 1f / (1f + z);
        }
        float z = (float) Math.exp(x);
        return z / (1f + z);
    }

    private static float clamp(float v) {
        if (v < 0f) {
            return 0f;
        }
        return Math.min(v, 1f);
    }
}
