package dev.keyvector.connector.embed;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;

/**
 * Embedding models on Amazon Bedrock. Titan Text Embeddings take one text per call, so a
 * batch becomes parallel calls; Cohere Embed takes up to 96 texts per call.
 */
public final class BedrockEmbedder extends BatchingEmbedder {

    private final String model;
    private final int dimensions;
    private final boolean normalize;
    private final boolean cohere;
    private final BedrockRuntimeClient client;

    private BedrockEmbedder(String model, int dimensions, boolean normalize, String region,
                            int batchSize, int concurrency, String documentPrefix, String queryPrefix) {
        super(batchSize, concurrency, dimensions, documentPrefix, queryPrefix);
        this.model = model;
        this.dimensions = dimensions;
        this.normalize = normalize;
        this.cohere = model.contains("cohere.");
        this.client = BedrockRuntimeClient.builder().region(Region.of(region)).build();
    }

    public static BedrockEmbedder create(String model, int dimensions, boolean normalize, String region,
                                         int batchSize, int concurrency, String documentPrefix, String queryPrefix) {
        boolean cohere = model.contains("cohere.");
        if (!cohere && !model.contains("titan-embed")) {
            throw new IllegalArgumentException("Bedrock embedder supports Titan Text Embeddings and Cohere Embed; got " + model);
        }
        // Titan answers one text per request; Cohere at most 96.
        int effectiveBatch = cohere ? Math.min(batchSize, 96) : 1;
        return new BedrockEmbedder(model, dimensions, normalize, region, effectiveBatch, concurrency, documentPrefix, queryPrefix);
    }

    @Override
    protected List<float[]> request(List<String> texts, boolean query) throws Exception {
        ObjectNode body = HttpJson.MAPPER.createObjectNode();
        if (cohere) {
            texts.forEach(body.putArray("texts")::add);
            body.put("input_type", query ? "search_query" : "search_document");
            body.put("truncate", "END");
        } else {
            body.put("inputText", texts.get(0));
            body.put("dimensions", dimensions);
            body.put("normalize", normalize);
        }
        InvokeModelRequest req = InvokeModelRequest.builder()
            .modelId(model)
            .contentType("application/json")
            .accept("application/json")
            .body(SdkBytes.fromString(body.toString(), StandardCharsets.UTF_8))
            .build();
        JsonNode res = Retry.call("Bedrock " + model, 8, BedrockEmbedder::retryable,
            () -> HttpJson.MAPPER.readTree(client.invokeModel(req).body().asUtf8String()));
        List<float[]> out = new ArrayList<>(texts.size());
        if (cohere) {
            JsonNode embeddings = res.get("embeddings");
            if (embeddings != null && embeddings.isObject()) {
                embeddings = embeddings.get("float");
            }
            if (embeddings == null || !embeddings.isArray()) {
                throw new IllegalStateException("Cohere response has no embeddings");
            }
            for (JsonNode v : embeddings) {
                out.add(HttpJson.floats(v));
            }
        } else {
            out.add(HttpJson.floats(res.get("embedding")));
        }
        return out;
    }

    /**
     * Throttling, server errors and network failures are worth another attempt. The first
     * version asked {@code SdkException.retryable()} and grepped the message for
     * "ThrottlingException": the SDK reports a throttle it has already retried as not
     * retryable, and the message says "Too many requests", so a throttle failed the batch
     * at once. Titan Text Embeddings V2 allows 60 requests a minute and the quota cannot be
     * raised, so this is the normal case, not an edge.
     */
    static boolean retryable(Exception e) {
        if (e instanceof AwsServiceException) {
            AwsServiceException a = (AwsServiceException) e;
            return a.isThrottlingException() || a.statusCode() == 429 || a.statusCode() >= 500;
        }
        return e instanceof SdkClientException;
    }

    @Override
    public String describe() {
        return "bedrock " + model;
    }

    @Override
    public void close() {
        super.close();
        client.close();
    }
}
