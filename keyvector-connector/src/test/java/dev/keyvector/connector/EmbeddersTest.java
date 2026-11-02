package dev.keyvector.connector;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.keyvector.connector.embed.OpenAiEmbedder;
import dev.keyvector.connector.embed.TeiEmbedder;

/** The HTTP embedders against a fake server: batching, order, prefixes, retries, dimensions. */
class EmbeddersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();

    private String start(String path, int failFirst, java.util.function.Function<JsonNode, String> reply) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, ex -> {
            JsonNode body = MAPPER.readTree(ex.getRequestBody());
            synchronized (requests) {
                requests.add(body);
            }
            if (calls.incrementAndGet() <= failFirst) {
                send(ex, 503, "busy");
            } else {
                send(ex, 200, reply.apply(body));
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void send(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /** A vector whose first value encodes the text's length, so order can be checked. */
    private static String vectorFor(String text, int dims) {
        StringBuilder sb = new StringBuilder("[").append(text.length());
        for (int i = 1; i < dims; i++) {
            sb.append(",0");
        }
        return sb.append("]").toString();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void teiBatchesKeepsOrderAppliesPrefixAndRetries() throws Exception {
        String url = start("/embed", 1, body -> {
            List<String> vs = new ArrayList<>();
            body.get("inputs").forEach(t -> vs.add(vectorFor(t.asText(), 3)));
            return "[" + String.join(",", vs) + "]";
        });
        try (TeiEmbedder e = new TeiEmbedder(url, 3, 2, 2, true, "passage: ", "query: ")) {
            List<String> texts = List.of("a", "bb", "ccc", "dddd", "eeeee");
            List<float[]> out = e.embedDocuments(texts);
            assertEquals(5, out.size());
            for (int i = 0; i < texts.size(); i++) {
                assertEquals("passage: ".length() + texts.get(i).length(), (int) out.get(i)[0], "order at " + i);
            }
            assertEquals("query: hi".length(), (int) e.embedQuery("hi")[0]);
        }
        // 3 batches of at most 2, one 503 retried, one query.
        assertEquals(5, requests.size());
        requests.forEach(r -> assertTrue(r.get("inputs").size() <= 2));
    }

    @Test
    void wrongDimensionsFailLoudly() throws Exception {
        String url = start("/embed", 0, body -> "[" + vectorFor("x", 4) + "]");
        try (TeiEmbedder e = new TeiEmbedder(url, 3, 8, 1, true, "", "")) {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> e.embedDocuments(List.of("x")));
            assertTrue(ex.getMessage().contains("4 dimensions"));
        }
    }

    @Test
    void openAiReordersByIndexAndSendsDimensions() throws Exception {
        String url = start("/v1/embeddings", 0, body -> {
            List<String> items = new ArrayList<>();
            JsonNode input = body.get("input");
            // Answer in reverse to prove results are placed by index, not by position.
            for (int i = input.size() - 1; i >= 0; i--) {
                items.add("{\"index\":" + i + ",\"embedding\":" + vectorFor(input.get(i).asText(), 2) + "}");
            }
            return "{\"data\":[" + String.join(",", items) + "]}";
        });
        try (OpenAiEmbedder e = new OpenAiEmbedder(url + "/v1", "text-embedding-3-small", 2, true, "", 10, 1, "", "")) {
            List<float[]> out = e.embedDocuments(List.of("a", "bbb"));
            assertEquals(1, (int) out.get(0)[0]);
            assertEquals(3, (int) out.get(1)[0]);
        }
        assertEquals(2, requests.get(0).get("dimensions").asInt());
        assertEquals("text-embedding-3-small", requests.get(0).get("model").asText());
    }

    @Test
    void aMissingApiKeyIsAStartupErrorNotANullHeader() {
        assertThrows(IllegalArgumentException.class,
            () -> new OpenAiEmbedder("http://x", "m", 2, false, "KEYVECTOR_SURELY_UNSET_VAR", 1, 1, "", ""));
    }
}
