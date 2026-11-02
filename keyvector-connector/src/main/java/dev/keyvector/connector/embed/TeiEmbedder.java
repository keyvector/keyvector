package dev.keyvector.connector.embed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Hugging Face text-embeddings-inference (TEI), the server for self-hosted
 * Sentence-Transformers models: {@code POST /embed} with {@code {"inputs": [...]}}.
 * TEI's own default for the most inputs per request is 32, which is why that is
 * {@code embedder.batch-size}'s default.
 */
public final class TeiEmbedder extends BatchingEmbedder {

    private final String url;
    private final boolean normalize;
    private final HttpJson http = new HttpJson(Duration.ofSeconds(60));

    public TeiEmbedder(String baseUrl, int dimensions, int batchSize, int concurrency, boolean normalize,
                       String documentPrefix, String queryPrefix) {
        super(batchSize, concurrency, dimensions, documentPrefix, queryPrefix);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("embedder.url is required for the tei embedder, e.g. http://localhost:8080");
        }
        this.url = baseUrl.replaceAll("/+$", "") + "/embed";
        this.normalize = normalize;
    }

    @Override
    protected List<float[]> request(List<String> texts, boolean query) throws Exception {
        ObjectNode body = HttpJson.MAPPER.createObjectNode();
        texts.forEach(body.putArray("inputs")::add);
        body.put("truncate", true);
        body.put("normalize", normalize);
        JsonNode res = Retry.call("TEI embed", 6, Retry::retryableHttp, () -> http.post(url, body, Map.of()));
        if (!res.isArray()) {
            throw new IllegalStateException("TEI returned " + res.getNodeType() + ", expected an array of vectors");
        }
        List<float[]> out = new ArrayList<>(res.size());
        for (JsonNode v : res) {
            out.add(HttpJson.floats(v));
        }
        return out;
    }

    @Override
    public String describe() {
        return "tei " + url;
    }
}
