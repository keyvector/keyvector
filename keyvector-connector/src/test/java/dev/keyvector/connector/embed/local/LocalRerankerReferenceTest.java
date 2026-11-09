package dev.keyvector.connector.embed.local;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The in-process reranker must score exactly as the reference implementation does: a pair
 * tokenized the wrong way (no token types, the wrong truncation) gives plausible but wrong
 * scores and no error. The reference is Hugging Face's text-embeddings-inference, which serves
 * the same model's raw scores (src/test/resources/reference/rerank-minilm.json), for three
 * queries over texts that include accents, emoji, Chinese and one long enough to be truncated.
 *
 * <p>Downloads the model (about 90 MB, cached), so it runs only with KEYVECTOR_MODEL_TESTS=1.
 */
@EnabledIfEnvironmentVariable(named = "KEYVECTOR_MODEL_TESTS", matches = "1")
class LocalRerankerReferenceTest {

    static final double MAX_ERROR = 0.01;

    @Test
    @SuppressWarnings("unchecked")
    void matchesTheReferenceImplementation() throws Exception {
        Map<String, Object> ref;
        try (InputStream r = getClass().getResourceAsStream("/reference/rerank-minilm.json")) {
            ref = new ObjectMapper().readValue(r, Map.class);
        }
        List<String> texts = (List<String>) ref.get("texts");
        ModelFiles files = ModelFiles.hub((String) ref.get("model"), (String) ref.get("revision"), ModelFiles.defaultCacheRoot());
        try (LocalReranker reranker = new LocalReranker(files, 0, 0)) {
            for (Map<String, Object> q : (List<Map<String, Object>>) ref.get("queries")) {
                String query = (String) q.get("query");
                List<Number> expected = (List<Number>) q.get("scores");
                float[] got = reranker.score(query, texts);
                double worst = 0;
                for (int i = 0; i < texts.size(); i++) {
                    worst = Math.max(worst, Math.abs(got[i] - expected.get(i).doubleValue()));
                }
                System.out.printf("rerank \"%s\": largest difference %.6f%n", query, worst);
                assertTrue(worst <= MAX_ERROR, query + ": largest difference " + worst);
            }
        }
    }
}
