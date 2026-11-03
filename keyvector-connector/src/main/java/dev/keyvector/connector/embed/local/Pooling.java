package dev.keyvector.connector.embed.local;

import java.util.Locale;
import java.util.Map;

/**
 * Turns a transformer's per-token outputs into one sentence vector, the way
 * sentence-transformers does, as described by the model's {@code 1_Pooling/config.json}.
 *
 * <p>Pure arithmetic with no ONNX types, so every mode is unit-tested. A wrong pooling mode
 * doesn't fail: it produces plausible vectors that rank worse, which is why the mode is read
 * from the model's own configuration and never guessed.
 */
public final class Pooling {

    public enum Mode { MEAN, CLS, MAX, MEAN_SQRT_LEN }

    private final Mode mode;
    private final boolean normalize;

    public Pooling(Mode mode, boolean normalize) {
        this.mode = mode;
        this.normalize = normalize;
    }

    public Mode mode() {
        return mode;
    }

    public boolean normalize() {
        return normalize;
    }

    /** Reads {@code 1_Pooling/config.json}'s flags. Exactly one must be set. */
    public static Mode modeFrom(Map<String, Object> poolingConfig) {
        Mode found = null;
        for (Map.Entry<String, Mode> e : Map.of(
                "pooling_mode_mean_tokens", Mode.MEAN,
                "pooling_mode_cls_token", Mode.CLS,
                "pooling_mode_max_tokens", Mode.MAX,
                "pooling_mode_mean_sqrt_len_tokens", Mode.MEAN_SQRT_LEN).entrySet()) {
            if (Boolean.TRUE.equals(poolingConfig.get(e.getKey()))) {
                if (found != null) {
                    throw new IllegalArgumentException("Pooling config sets more than one mode; Keyvector supports one");
                }
                found = e.getValue();
            }
        }
        for (String unsupported : new String[] {"pooling_mode_weightedmean_tokens", "pooling_mode_lasttoken"}) {
            if (Boolean.TRUE.equals(poolingConfig.get(unsupported))) {
                throw new IllegalArgumentException("Pooling mode " + unsupported.substring("pooling_mode_".length()).toUpperCase(Locale.ROOT)
                    + " is not supported yet");
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Pooling config names no pooling mode");
        }
        return found;
    }

    /**
     * @param tokens one row of the model output: [sequence][hidden]
     * @param mask   the attention mask for that row: 1 for real tokens, 0 for padding
     */
    public float[] pool(float[][] tokens, long[] mask) {
        int hidden = tokens[0].length;
        float[] out = new float[hidden];
        switch (mode) {
            case CLS:
                System.arraycopy(tokens[0], 0, out, 0, hidden);
                break;
            case MAX: {
                java.util.Arrays.fill(out, Float.NEGATIVE_INFINITY);
                for (int t = 0; t < tokens.length; t++) {
                    if (mask[t] == 0) {
                        continue;
                    }
                    for (int h = 0; h < hidden; h++) {
                        out[h] = Math.max(out[h], tokens[t][h]);
                    }
                }
                break;
            }
            case MEAN:
            case MEAN_SQRT_LEN: {
                double[] sum = new double[hidden];
                int count = 0;
                for (int t = 0; t < tokens.length; t++) {
                    if (mask[t] == 0) {
                        continue;
                    }
                    count++;
                    for (int h = 0; h < hidden; h++) {
                        sum[h] += tokens[t][h];
                    }
                }
                double divisor = Math.max(1, mode == Mode.MEAN ? count : Math.sqrt(count));
                for (int h = 0; h < hidden; h++) {
                    out[h] = (float) (sum[h] / divisor);
                }
                break;
            }
            default:
                throw new IllegalStateException(String.valueOf(mode));
        }
        return normalize ? l2Normalize(out) : out;
    }

    static float[] l2Normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        if (norm < 1e-12) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }
}
