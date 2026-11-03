package dev.keyvector.connector.embed.local;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Keyvector's in-process embeddings must equal what the reference implementation produces for
 * the same model. A tokenization or pooling mistake throws no error; it only makes search
 * quietly worse, so this compares against vectors produced by Hugging Face's
 * text-embeddings-inference (src/test/resources/reference) for two models with different
 * pooling: MiniLM (mean) and bge-small (CLS). The texts include accents, emoji, Chinese, a
 * one-character text, and one long enough to be truncated.
 *
 * <p>Downloads the models (about 90 and 130 MB, cached), so it runs only with
 * KEYVECTOR_MODEL_TESTS=1.
 */
@EnabledIfEnvironmentVariable(named = "KEYVECTOR_MODEL_TESTS", matches = "1")
class LocalEmbedderReferenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final double MIN_COSINE = 0.9999;

    @ParameterizedTest
    @CsvSource({"minilm", "bge-small"})
    @SuppressWarnings("unchecked")
    void matchesTheReferenceImplementation(String name) throws Exception {
        List<String> texts;
        Map<String, Object> ref;
        try (InputStream t = getClass().getResourceAsStream("/reference/texts.json");
             InputStream r = getClass().getResourceAsStream("/reference/" + name + ".json")) {
            texts = MAPPER.readValue(t, List.class);
            ref = MAPPER.readValue(r, Map.class);
        }
        List<List<Number>> expected = (List<List<Number>>) ref.get("vectors");
        ModelFiles files = ModelFiles.hub((String) ref.get("model"), (String) ref.get("revision"), ModelFiles.defaultCacheRoot());
        try (LocalEmbedder e = new LocalEmbedder(files, 384, 4, new LocalEmbedder.Options(0, 0, "", false), "", "")) {
            List<float[]> got = e.embedDocuments(texts);
            for (int i = 0; i < texts.size(); i++) {
                double c = cosine(got.get(i), expected.get(i));
                System.out.printf("%s text %d: cosine %.6f%n", name, i, c);
                assertTrue(c >= MIN_COSINE, name + " text " + i + " cosine " + c);
            }
            // The query path must agree with the document path for the same text.
            assertTrue(cosine(e.embedQuery(texts.get(0)), expected.get(0)) >= MIN_COSINE);
        }
    }

    static double cosine(float[] a, List<Number> b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            double y = b.get(i).doubleValue();
            dot += a[i] * y;
            na += (double) a[i] * a[i];
            nb += y * y;
        }
        return dot / Math.sqrt(na * nb);
    }
}
