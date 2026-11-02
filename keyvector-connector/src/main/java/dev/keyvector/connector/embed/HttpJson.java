package dev.keyvector.connector.embed;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A small JSON-over-HTTP client for the embedding servers. */
final class HttpJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final Duration timeout;

    HttpJson(Duration timeout) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.timeout = timeout;
    }

    JsonNode post(String url, Object body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        headers.forEach(b::header);
        HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            String text = res.body() == null ? "" : res.body();
            throw new Retry.HttpStatusException(res.statusCode(), text.length() > 500 ? text.substring(0, 500) : text);
        }
        return MAPPER.readTree(res.body());
    }

    static float[] floats(JsonNode array) {
        if (array == null || !array.isArray()) {
            throw new IllegalStateException("Expected an array of numbers in the embedding response");
        }
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) array.get(i).asDouble();
        }
        return out;
    }
}
