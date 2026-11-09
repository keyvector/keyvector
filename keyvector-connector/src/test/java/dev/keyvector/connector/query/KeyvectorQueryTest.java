package dev.keyvector.connector.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.keyvector.connector.embed.Embedder;
import dev.keyvector.connector.model.Chunker;
import dev.keyvector.connector.model.RowKey;
import dev.keyvector.connector.model.TextTemplate;
import dev.keyvector.connector.sink.Hit;
import dev.keyvector.connector.sink.VectorDoc;
import dev.keyvector.connector.sink.VectorSink;

class KeyvectorQueryTest {

    static final Embedder EMBEDDER = new Embedder() {
        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float[] embedQuery(String text) {
            return new float[] {1};
        }

        @Override
        public String describe() {
            return "fake";
        }
    };

    /** Returns its hits in the order given, as the index would by distance. */
    static final class HitsSink implements VectorSink {
        final List<Hit> hits = new ArrayList<>();
        int askedFor;

        @Override
        public List<Hit> query(float[] v, int topK, Map<String, Object> filter) {
            askedFor = topK;
            return hits.size() > topK ? hits.subList(0, topK) : hits;
        }

        @Override
        public void put(List<VectorDoc> docs) {
        }

        @Override
        public void delete(List<String> keys) {
        }

        @Override
        public Map<String, float[]> vectors(List<String> keys) {
            return Map.of();
        }

        @Override
        public Map<String, Map<String, Object>> metadata(List<String> keys) {
            return Map.of();
        }

        @Override
        public int dimensions() {
            return 1;
        }

        @Override
        public String describe() {
            return "hits";
        }
    }

    /** Scores a text by how many times it contains the query word. */
    static final class CountingReranker implements Reranker {
        final List<String> seen = new ArrayList<>();

        @Override
        public float[] score(String query, List<String> texts) {
            seen.addAll(texts);
            float[] out = new float[texts.size()];
            for (int i = 0; i < texts.size(); i++) {
                out[i] = texts.get(i).split(query, -1).length - 1;
            }
            return out;
        }

        @Override
        public String describe() {
            return "counting";
        }
    }

    private final HitsSink sink = new HitsSink();
    private final Map<String, Map<String, Object>> table = new HashMap<>();

    private void row(long id, String body) {
        table.put(RowKey.encode(Map.of("id", id)), new HashMap<>(Map.of("id", id, "body", body)));
    }

    private void hit(String key, float distance) {
        sink.hits.add(new Hit(key, distance, Map.of()));
    }

    private KeyvectorQuery.RowSource rows() {
        return key -> table.get(RowKey.encode(new HashMap<>(Map.of("id", Long.parseLong(key.get("id"))))));
    }

    @Test
    void eachRowAppearsOnceAtItsBestChunk() throws Exception {
        row(1, "a");
        row(2, "b");
        hit("id=1#3", 0.1f);
        hit("id=1#0", 0.2f);
        hit("id=2#1", 0.3f);
        KeyvectorQuery q = new KeyvectorQuery(EMBEDDER, sink, rows(), TextTemplate.of(List.of("body"), null, 1000),
            new Chunker(10, 0, 100), null, null, 0);
        List<KeyvectorQuery.Result> r = q.search("x", 2, null);
        assertEquals(List.of("id=1", "id=2"), r.stream().map(KeyvectorQuery.Result::key).toList());
        assertEquals(3, r.get(0).chunk());
        assertEquals(6, sink.askedFor, "chunked indexes fetch more vectors so enough distinct rows remain");
    }

    @Test
    void aDeletedRowIsDropped() throws Exception {
        row(2, "b");
        hit("id=1", 0.1f);
        hit("id=2", 0.2f);
        List<KeyvectorQuery.Result> r = new KeyvectorQuery(EMBEDDER, sink, rows()).search("x", 5, null);
        assertEquals(List.of("id=2"), r.stream().map(KeyvectorQuery.Result::key).toList());
    }

    @Test
    void theRerankerReordersTheCandidates() throws Exception {
        row(1, "cat");
        row(2, "dog dog dog");
        row(3, "dog");
        hit("id=1", 0.1f);
        hit("id=2", 0.2f);
        hit("id=3", 0.3f);
        CountingReranker reranker = new CountingReranker();
        KeyvectorQuery q = new KeyvectorQuery(EMBEDDER, sink, rows(), TextTemplate.of(List.of("body"), null, 1000),
            null, null, reranker, 50);
        List<KeyvectorQuery.Result> r = q.search("dog", 2, null);
        assertEquals(List.of("id=2", "id=3"), r.stream().map(KeyvectorQuery.Result::key).toList());
        assertEquals(3f, r.get(0).score());
        assertEquals(50, sink.askedFor, "the reranker sees the candidates, not just the top k");
    }

    @Test
    void theRerankerReadsTheMatchingChunkRebuiltFromTheRow() throws Exception {
        String body = "alpha beta gamma delta epsilon zeta eta theta iota kappa. lambda mu nu xi omicron pi rho sigma tau upsilon.";
        row(1, body);
        hit("id=1#1", 0.1f);
        CountingReranker reranker = new CountingReranker();
        Chunker chunker = new Chunker(10, 0, 100);
        new KeyvectorQuery(EMBEDDER, sink, rows(), TextTemplate.of(List.of("body"), null, 1000), chunker, null, reranker, 10)
            .search("mu", 1, null);
        assertEquals(List.of(chunker.split(body).get(1)), reranker.seen);
    }

    @Test
    void withoutRowsTheRerankerUsesTheStoredText() throws Exception {
        sink.hits.add(new Hit("id=1", 0.1f, Map.of("text", "one dog")));
        sink.hits.add(new Hit("id=2", 0.2f, Map.of("text", "dog dog")));
        List<KeyvectorQuery.Result> r = new KeyvectorQuery(EMBEDDER, sink, null, null, null, "text", new CountingReranker(), 10)
            .search("dog", 2, null);
        assertEquals("id=2", r.get(0).key());
    }

    @Test
    void rerankingNeedsSomeText() {
        assertThrows(IllegalArgumentException.class,
            () -> new KeyvectorQuery(EMBEDDER, sink, null, null, null, null, new CountingReranker(), 10));
    }
}
