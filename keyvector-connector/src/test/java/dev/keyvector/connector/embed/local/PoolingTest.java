package dev.keyvector.connector.embed.local;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PoolingTest {

    private static final float[][] TOKENS = {
        {1, 2}, {3, 4}, {100, -100}, // the third token is padding
    };
    private static final long[] MASK = {1, 1, 0};

    @Test
    void meanIgnoresPadding() {
        assertArrayEquals(new float[] {2, 3}, new Pooling(Pooling.Mode.MEAN, false).pool(TOKENS, MASK), 1e-6f);
    }

    @Test
    void clsTakesTheFirstToken() {
        assertArrayEquals(new float[] {1, 2}, new Pooling(Pooling.Mode.CLS, false).pool(TOKENS, MASK), 1e-6f);
    }

    @Test
    void maxIgnoresPadding() {
        assertArrayEquals(new float[] {3, 4}, new Pooling(Pooling.Mode.MAX, false).pool(TOKENS, MASK), 1e-6f);
    }

    @Test
    void meanSqrtLenDividesBySquareRootOfTokenCount() {
        float d = (float) Math.sqrt(2);
        assertArrayEquals(new float[] {4 / d, 6 / d}, new Pooling(Pooling.Mode.MEAN_SQRT_LEN, false).pool(TOKENS, MASK), 1e-5f);
    }

    @Test
    void normalizationGivesUnitLength() {
        float[] v = new Pooling(Pooling.Mode.MEAN, true).pool(TOKENS, MASK);
        assertEquals(1.0, Math.sqrt(v[0] * v[0] + v[1] * v[1]), 1e-6);
        assertArrayEquals(new float[] {0, 0}, Pooling.l2Normalize(new float[] {0, 0}));
    }

    @Test
    void modeIsReadFromTheModelsConfigAndNeverGuessed() {
        assertEquals(Pooling.Mode.MEAN, Pooling.modeFrom(Map.of("pooling_mode_mean_tokens", true, "pooling_mode_cls_token", false)));
        assertEquals(Pooling.Mode.CLS, Pooling.modeFrom(Map.of("pooling_mode_cls_token", true)));
        assertThrows(IllegalArgumentException.class, () -> Pooling.modeFrom(Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> Pooling.modeFrom(Map.of("pooling_mode_mean_tokens", true, "pooling_mode_cls_token", true)));
        assertThrows(IllegalArgumentException.class, () -> Pooling.modeFrom(Map.of("pooling_mode_lasttoken", true)));
    }
}
