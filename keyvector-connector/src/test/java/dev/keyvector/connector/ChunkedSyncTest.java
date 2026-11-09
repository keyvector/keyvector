package dev.keyvector.connector;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import dev.keyvector.connector.model.Change;
import dev.keyvector.connector.model.Chunker;
import dev.keyvector.connector.model.MetadataMapper;
import dev.keyvector.connector.model.Planner;
import dev.keyvector.connector.model.TextTemplate;
import dev.keyvector.connector.sink.VectorDoc;

class ChunkedSyncTest {

    private final SyncEngineTest.FakeEmbedder embedder = new SyncEngineTest.FakeEmbedder();
    private final SyncEngineTest.FakeSink sink = new SyncEngineTest.FakeSink();
    private final Chunker chunker = new Chunker(10, 0, 100);
    private final SyncEngine engine = new SyncEngine(
        new Planner(TextTemplate.of(List.of("body"), null, 100_000), new MetadataMapper(List.of("tag"), "text"),
            Planner.EmptyText.DELETE, chunker.keySuffixLength()),
        embedder, sink, chunker);

    private static String words(int n) {
        List<String> w = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            w.add("w" + i);
        }
        return String.join(" ", w);
    }

    private static Map<String, Object> row(String body, String tag) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("body", body);
        m.put("tag", tag);
        return m;
    }

    private static Change upsert(long id, Map<String, Object> before, Map<String, Object> after) {
        return new Change(Map.of("id", id), before, after, null);
    }

    private TreeSet<String> keys() {
        return new TreeSet<>(sink.index.keySet());
    }

    @Test
    void aLongRowBecomesOneVectorPerChunkAndChunkZeroCountsThem() throws Exception {
        engine.apply(List.of(upsert(1, null, row(words(25), "a"))));
        assertEquals(new TreeSet<>(List.of("id=1#0", "id=1#1", "id=1#2")), keys());
        VectorDoc first = sink.index.get("id=1#0");
        assertEquals(3, first.metadata().get(SyncEngine.CHUNKS));
        assertEquals(0, first.metadata().get(SyncEngine.CHUNK));
        assertEquals("a", sink.index.get("id=1#2").metadata().get("tag"), "every chunk carries the row's metadata");
        assertEquals(words(25).substring(0, words(25).indexOf(" w10")), first.metadata().get("text"),
            "store-text-as holds the chunk's text, not the whole row's");
    }

    @Test
    void chunkZeroIsWrittenLast() throws Exception {
        engine.apply(List.of(upsert(1, null, row(words(25), "a")), upsert(2, null, row(words(15), "a"))));
        List<String> order = sink.putOrder;
        int lastNonZero = -1, firstZero = Integer.MAX_VALUE;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).endsWith("#0")) {
                firstZero = Math.min(firstZero, i);
            } else {
                lastNonZero = i;
            }
        }
        assertTrue(lastNonZero < firstZero, "chunk 0 must follow every other chunk: " + order);
    }

    @Test
    void aShorterTextRemovesTheChunksItNoLongerHas() throws Exception {
        engine.apply(List.of(upsert(1, null, row(words(35), "a"))));
        assertEquals(4, keys().size());
        engine.apply(List.of(upsert(1, row(words(35), "a"), row(words(12), "a"))));
        assertEquals(new TreeSet<>(List.of("id=1#0", "id=1#1")), keys());
        assertEquals(2, sink.index.get("id=1#0").metadata().get(SyncEngine.CHUNKS));
    }

    @Test
    void aShorterTextIsCleanedUpEvenWithoutTheOldImage() throws Exception {
        engine.apply(List.of(upsert(1, null, row(words(35), "a"))));
        // A backfill or a NEW_IMAGE stream: no old row, so the count stored on chunk 0 is all there is.
        engine.apply(List.of(upsert(1, null, row(words(5), "a"))));
        assertEquals(new TreeSet<>(List.of("id=1#0")), keys());
    }

    @Test
    void deletingARowRemovesEveryChunk() throws Exception {
        engine.apply(List.of(upsert(1, null, row(words(35), "a")), upsert(2, null, row(words(5), "a"))));
        SyncEngine.Result r = engine.apply(List.of(new Change(Map.of("id", 1L), row(words(35), "a"), null, "9")));
        assertEquals(1, r.deleted());
        assertEquals(new TreeSet<>(List.of("id=2#0")), keys());
    }

    @Test
    void aMetadataChangeReusesEveryChunksVector() throws Exception {
        engine.apply(List.of(upsert(1, null, row(words(25), "a"))));
        int embeddedBefore = embedder.seen.size();
        SyncEngine.Result r = engine.apply(List.of(upsert(1, row(words(25), "a"), row(words(25), "b"))));
        assertEquals(1, r.reused());
        assertEquals(embeddedBefore, embedder.seen.size(), "a tag change must not re-embed");
        assertEquals("b", sink.index.get("id=1#2").metadata().get("tag"));
    }

    @Test
    void turningChunkingOnReplacesTheOldUnchunkedVector() throws Exception {
        sink.index.put("id=1", new VectorDoc("id=1", new float[] {1, 1}, Map.of()));
        engine.apply(List.of(upsert(1, null, row(words(5), "a"))));
        assertEquals(new TreeSet<>(List.of("id=1#0")), keys());
    }

    @Test
    void aBatchThatFailsHalfwayIsFinishedByTheRetry() throws Exception {
        engine.apply(List.of(upsert(1, null, row(words(35), "a"))));
        List<Change> shrink = List.of(upsert(1, row(words(35), "a"), row(words(12), "a")));
        sink.failPuts = true;
        assertThrows(IllegalStateException.class, () -> engine.apply(shrink));
        sink.failPuts = false;
        engine.apply(shrink);
        assertEquals(new TreeSet<>(List.of("id=1#0", "id=1#1")), keys());
        engine.apply(shrink);
        assertEquals(new TreeSet<>(List.of("id=1#0", "id=1#1")), keys(), "replaying a batch changes nothing");
    }

    @Test
    void backfillTreatsChunkZeroAsIndexed() {
        assertEquals("id=1#0", engine.indexKey("id=1"));
        assertEquals("id=1", new SyncEngine(null, null, null).indexKey("id=1"));
    }
}
