package dev.keyvector.connector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.keyvector.connector.embed.Embedder;
import dev.keyvector.connector.model.Action;
import dev.keyvector.connector.model.Change;
import dev.keyvector.connector.model.Chunker;
import dev.keyvector.connector.model.Planner;
import dev.keyvector.connector.model.RowKey;
import dev.keyvector.connector.sink.VectorDoc;
import dev.keyvector.connector.sink.VectorSink;

/**
 * Applies row changes to the vector index. Shared by the stream, the backfill and the
 * redrive, so all three follow exactly the same rules.
 *
 * <p>Order inside a batch: embed first, then write, then delete. Nothing is written until
 * every embedding in the batch succeeded, so a failed batch leaves the index untouched for
 * the retry. Keys are unique after planning, so deletes and puts never touch the same row.
 *
 * <p>With a {@link Chunker}, a row is stored as one vector per chunk, keyed
 * {@code <row>#0, <row>#1, ...}, and chunk 0 records how many there are ({@value #CHUNKS}).
 * That count is what lets a shorter text, or a deleted row, remove every chunk it had: it is
 * read before anything is written. Stale chunks are deleted before the new ones are put, and
 * chunk 0 is deleted last and written last, so a batch that fails halfway leaves a count that
 * still covers every chunk left behind, and the retry finishes the job.
 */
public final class SyncEngine {

    private static final Logger logger = LoggerFactory.getLogger(SyncEngine.class);

    /** Metadata keys Keyvector adds to each chunk. */
    public static final String CHUNK = "kv_chunk";
    public static final String CHUNKS = "kv_chunks";

    private final Planner planner;
    private final Embedder embedder;
    private final VectorSink sink;
    private final Chunker chunker;

    public SyncEngine(Planner planner, Embedder embedder, VectorSink sink) {
        this(planner, embedder, sink, null);
    }

    /** @param chunker null to store one vector per row */
    public SyncEngine(Planner planner, Embedder embedder, VectorSink sink, Chunker chunker) {
        this.planner = planner;
        this.embedder = embedder;
        this.sink = sink;
        this.chunker = chunker;
    }

    /** The vector key whose presence means a row is indexed: the row key, or its chunk 0. */
    public String indexKey(String rowKey) {
        return chunker == null ? rowKey : RowKey.chunkKey(rowKey, 0);
    }

    public record Result(int embedded, int reused, int deleted, int unchanged, int rejected) {
        public Result plus(Result o) {
            return new Result(embedded + o.embedded, reused + o.reused, deleted + o.deleted,
                unchanged + o.unchanged, rejected + o.rejected);
        }

        public static Result empty() {
            return new Result(0, 0, 0, 0, 0);
        }
    }

    public Result apply(List<Change> changes) throws Exception {
        if (changes.isEmpty()) {
            return Result.empty();
        }
        Planner.Plan plan = planner.plan(changes);
        if (chunker != null) {
            return applyChunked(changes.size(), plan);
        }

        List<Action> toEmbed = new ArrayList<>();
        List<Action> toReuse = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();
        for (Action a : plan.actions()) {
            if (a.kind() == Action.Kind.DELETE) {
                toDelete.add(a.key());
            } else if (a.reuseVector()) {
                toReuse.add(a);
            } else {
                toEmbed.add(a);
            }
        }

        List<VectorDoc> docs = new ArrayList<>(toEmbed.size() + toReuse.size());
        int reused = 0;
        if (!toReuse.isEmpty()) {
            List<String> keys = new ArrayList<>(toReuse.size());
            toReuse.forEach(a -> keys.add(a.key()));
            Map<String, float[]> stored = sink.vectors(keys);
            for (Action a : toReuse) {
                float[] v = stored.get(a.key());
                if (v != null) {
                    docs.add(new VectorDoc(a.key(), v, a.metadata()));
                    reused++;
                } else {
                    // Only the metadata changed, but the row was never indexed (it predates the
                    // connector and no backfill ran): embed it now rather than leave it out.
                    toEmbed.add(a);
                }
            }
        }
        if (!toEmbed.isEmpty()) {
            List<String> texts = new ArrayList<>(toEmbed.size());
            toEmbed.forEach(a -> texts.add(a.text()));
            List<float[]> vectors = embedder.embedDocuments(texts);
            for (int i = 0; i < toEmbed.size(); i++) {
                Action a = toEmbed.get(i);
                docs.add(new VectorDoc(a.key(), vectors.get(i), a.metadata()));
            }
        }
        if (!docs.isEmpty()) {
            sink.put(docs);
        }
        if (!toDelete.isEmpty()) {
            sink.delete(toDelete);
        }
        Result r = new Result(toEmbed.size(), reused, toDelete.size(), plan.unchanged(), plan.rejected());
        logger.info("Applied {} changes: {} embedded, {} reused, {} deleted, {} unchanged, {} rejected",
            changes.size(), r.embedded(), r.reused(), r.deleted(), r.unchanged(), r.rejected());
        return r;
    }

    private Result applyChunked(int changeCount, Planner.Plan plan) throws Exception {
        List<String> rows = new ArrayList<>(plan.actions().size());
        plan.actions().forEach(a -> rows.add(a.key()));
        Map<String, Integer> oldCounts = storedChunkCounts(rows);
        String textKey = planner.metadata().textKey();

        List<Piece> toEmbed = new ArrayList<>();
        List<VectorDoc> docs = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();
        int embeddedRows = 0, reusedRows = 0, deletedRows = 0;

        for (Action a : plan.actions()) {
            String row = a.key();
            int old = oldCounts.getOrDefault(row, 0);
            int keep = 0;
            if (a.kind() == Action.Kind.UPSERT) {
                List<String> chunks = chunker.split(a.text());
                keep = chunks.size();
                List<Piece> pieces = new ArrayList<>(keep);
                for (int i = 0; i < keep; i++) {
                    Map<String, Object> meta = new LinkedHashMap<>(a.metadata());
                    if (textKey != null && meta.containsKey(textKey)) {
                        meta.put(textKey, chunks.get(i));
                    }
                    meta.put(CHUNK, i);
                    meta.put(CHUNKS, keep);
                    pieces.add(new Piece(RowKey.chunkKey(row, i), chunks.get(i), meta));
                }
                List<VectorDoc> stored = a.reuseVector() ? storedVectors(pieces) : null;
                if (stored != null) {
                    docs.addAll(stored);
                    reusedRows++;
                } else {
                    toEmbed.addAll(pieces);
                    embeddedRows++;
                }
            } else {
                deletedRows++;
            }
            // Descending, so chunk 0 (which holds the count) goes last; then the unchunked key,
            // in case the row was indexed before chunking was turned on.
            for (int i = old - 1; i >= keep; i--) {
                toDelete.add(RowKey.chunkKey(row, i));
            }
            toDelete.add(row);
        }

        if (!toEmbed.isEmpty()) {
            List<String> texts = new ArrayList<>(toEmbed.size());
            toEmbed.forEach(p -> texts.add(p.text()));
            List<float[]> vectors = embedder.embedDocuments(texts);
            for (int i = 0; i < toEmbed.size(); i++) {
                Piece p = toEmbed.get(i);
                docs.add(new VectorDoc(p.key(), vectors.get(i), p.metadata()));
            }
        }
        if (!toDelete.isEmpty()) {
            sink.delete(toDelete);
        }
        if (!docs.isEmpty()) {
            // Every chunk 0 last: its presence then means the row's other chunks are written,
            // which is what a resumed backfill and the next change rely on.
            List<VectorDoc> ordered = new ArrayList<>(docs.size());
            docs.stream().filter(d -> RowKey.chunkOf(d.key()) != 0).forEach(ordered::add);
            docs.stream().filter(d -> RowKey.chunkOf(d.key()) == 0).forEach(ordered::add);
            sink.put(ordered);
        }
        Result r = new Result(embeddedRows, reusedRows, deletedRows, plan.unchanged(), plan.rejected());
        logger.info("Applied {} changes: {} embedded ({} chunks), {} reused, {} deleted, {} unchanged, {} rejected",
            changeCount, r.embedded(), toEmbed.size(), r.reused(), r.deleted(), r.unchanged(), r.rejected());
        return r;
    }

    /** How many chunks each row has in the index now, from chunk 0's metadata; absent means none. */
    private Map<String, Integer> storedChunkCounts(List<String> rows) throws Exception {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<String> firsts = new ArrayList<>(rows.size());
        rows.forEach(r -> firsts.add(RowKey.chunkKey(r, 0)));
        Map<String, Integer> out = new HashMap<>();
        sink.metadata(firsts).forEach((key, meta) -> {
            Object n = meta.get(CHUNKS);
            out.put(RowKey.rowOf(key), n instanceof Number ? Math.max(1, ((Number) n).intValue()) : 1);
        });
        return out;
    }

    /** One chunk of a row, before it is embedded. */
    private record Piece(String key, String text, Map<String, Object> metadata) {
    }

    /** The stored vectors for every chunk, with new metadata; null if any chunk is missing. */
    private List<VectorDoc> storedVectors(List<Piece> pieces) throws Exception {
        List<String> keys = new ArrayList<>(pieces.size());
        pieces.forEach(p -> keys.add(p.key()));
        Map<String, float[]> stored = sink.vectors(keys);
        List<VectorDoc> out = new ArrayList<>(pieces.size());
        for (Piece p : pieces) {
            float[] v = stored.get(p.key());
            if (v == null) {
                return null;
            }
            out.add(new VectorDoc(p.key(), v, p.metadata()));
        }
        return out;
    }
}
