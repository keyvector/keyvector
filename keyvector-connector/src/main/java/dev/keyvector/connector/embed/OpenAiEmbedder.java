package dev.keyvector.connector.embed;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Any OpenAI-compatible {@code /embeddings} endpoint: OpenAI itself, and self-hosted servers
 * that speak the same API (vLLM, Infinity, Ollama, TEI's {@code /v1}). The key is read from
 * the environment variable named by {@code embedder.api-key-env}, so it can come from Secrets
 * Manager through ECS and never sits in the config file.
 */
public final class OpenAiEmbedder extends BatchingEmbedder {

    private final String url;
    private final String model;
    private final int dimensions;
    private final boolean sendDimensions;
    private final String apiKey;
    private final HttpJson http = new HttpJson(Duration.ofSeconds(60));

    public OpenAiEmbedder(String baseUrl, String model, int dimensions, boolean sendDimensions, String apiKeyEnv,
                          int batchSize, int concurrency, String documentPrefix, String queryPrefix) {
        super(batchSize, concurrency, dimensions, documentPrefix, queryPrefix);
        String base = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.replaceAll("/+$", "");
        this.url = base + "/embeddings";
        this.model = model;
        this.dimensions = dimensions;
        this.sendDimensions = sendDimensions;
        this.apiKey = apiKeyEnv == null || apiKeyEnv.isBlank() ? null : System.getenv(apiKeyEnv);
        if (apiKeyEnv != null && !apiKeyEnv.isBlank() && apiKey == null) {
            throw new IllegalArgumentException("embedder.api-key-env names " + apiKeyEnv + ", which is not set");
        }
    }

    @Override
    protected List<float[]> request(List<String> texts, boolean query) throws Exception {
        ObjectNode body = HttpJson.MAPPER.createObjectNode();
        body.put("model", model);
        texts.forEach(body.putArray("input")::add);
        if (sendDimensions) {
            body.put("dimensions", dimensions);
        }
        Map<String, String> headers = new HashMap<>();
        if (apiKey != null) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        JsonNode res = Retry.call("OpenAI-compatible embed", 6, Retry::retryableHttp, () -> http.post(url, body, headers));
        JsonNode data = res.get("data");
        if (data == null || !data.isArray() || data.size() != texts.size()) {
            throw new IllegalStateException("Embedding response did not carry one item per input");
        }
        float[][] out = new float[texts.size()][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= out.length) {
                throw new IllegalStateException("Embedding response item has no valid index");
            }
            out[index] = HttpJson.floats(item.get("embedding"));
        }
        return List.of(out);
    }

    @Override
    public String describe() {
        return "openai-compatible " + model + " at " + url;
    }
}
