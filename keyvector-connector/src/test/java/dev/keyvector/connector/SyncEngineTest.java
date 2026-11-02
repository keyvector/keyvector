package dev.keyvector.connector;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.keyvector.connector.embed.Embedder;
import dev.keyvector.connector.model.Change;
import dev.keyvector.connector.model.MetadataMapper;
import dev.keyvector.connector.model.Planner;
import dev.keyvector.connector.model.TextTemplate;
import dev.keyvector.connector.sink.Hit;
import dev.keyvector.connector.sink.VectorDoc;
import dev.keyvector.connector.sink.VectorSink;

class SyncEngineTest {

    static final class FakeEmbedder implements Embedder {
        final List<String> seen = new ArrayList<>();
        boolean fail;

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            if (fail) {
                throw new IllegalStateException("model down");
            }
            seen.addAll(texts);
            List<float[]> out = new ArrayList<>();
            texts.forEach(t -> out.add(new float[] {t.length(), 1}));
            return out;
        }

        @Override
        public float[] embedQuery(String text) {
            return new float[] {text.length(), 1};
        }

        @Override
        public String describe() {
            return "fake";
        }
    }

    static final class FakeSink implements VectorSink {
        final Map<String, VectorDoc> index = new HashMap<>();
        int puts;

        @Override
        public void put(List<VectorDoc> docs) {
            puts++;
            docs.forEach(d -> index.put(d.key(), d));
        }

        @Override
        public void delete(List<String> keys) {
            keys.forEach(index::remove);
        }

        @Override
        public Map<String, float[]> vectors(List<String> keys) {
            Map<String, float[]> out = new HashMap<>();
            keys.forEach(k -> {
                if (index.containsKey(k)) {
                    out.put(k, index.get(k).vector());
                }
            });
            return out;
        }

        @Override
        public List<Hit> query(float[] v, int topK, Map<String, Object> filter) {
            return List.of();
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public String describe() {
            return "fake";
        }
    }

    private final FakeEmbedder embedder = new FakeEmbedder();
    private final FakeSink sink = new FakeSink();
    private final SyncEngine engine = new SyncEngine(new Planner(TextTemplate.of(List.of("body"), null, 1000),
        new MetadataMapper(List.of("tag"), null), Planner.EmptyText.DELETE), embedder, sink);

    private static Map<String, Object> row(String body, String tag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("body", body);
        m.put("tag", tag);
        return m;
    }

    @Test
    void writesReusesAndDeletes() throws Exception {
        engine.apply(List.of(new Change(Map.of("id", 1L), null, row("hello", "a"), "1"),
            new Change(Map.of("id", 2L), null, row("bye", "a"), "2")));
        assertEquals(2, sink.index.size());
        assertEquals(List.of("hello", "bye"), embedder.seen);

        SyncEngine.Result r = engine.apply(List.of(
            new Change(Map.of("id", 1L), row("hello", "a"), row("hello", "b"), "3"),
            new Change(Map.of("id", 2L), row("bye", "a"), null, "4")));
        assertEquals(1, r.reused());
        assertEquals(1, r.deleted());
        assertEquals(List.of("hello", "bye"), embedder.seen, "a tag change must not re-embed");
        assertEquals("b", sink.index.get("id=1").metadata().get("tag"));
        assertFalse(sink.index.containsKey("id=2"));
    }

    @Test
    void aMetadataChangeOnAnUnindexedRowEmbedsIt() throws Exception {
        SyncEngine.Result r = engine.apply(List.of(new Change(Map.of("id", 9L), row("old", "a"), row("old", "b"), "1")));
        assertEquals(0, r.reused());
        assertEquals(1, r.embedded());
        assertTrue(sink.index.containsKey("id=9"));
    }

    @Test
    void aFailedEmbeddingWritesNothing() {
        embedder.fail = true;
        assertThrows(IllegalStateException.class,
            () -> engine.apply(List.of(new Change(Map.of("id", 1L), null, row("x", "a"), "1"))));
        assertEquals(0, sink.puts);
    }

    @Test
    void applyingTheSameBatchTwiceIsTheSameAsOnce() throws Exception {
        List<Change> batch = List.of(new Change(Map.of("id", 1L), null, row("x", "a"), "1"),
            new Change(Map.of("id", 2L), row("y", "a"), null, "2"));
        engine.apply(batch);
        Map<String, VectorDoc> once = new HashMap<>(sink.index);
        engine.apply(batch);
        assertEquals(once.keySet(), sink.index.keySet());
    }
}
