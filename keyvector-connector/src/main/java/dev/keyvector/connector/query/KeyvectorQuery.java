package dev.keyvector.connector.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dev.keyvector.connector.backfill.KeyspacesRows;
import dev.keyvector.connector.embed.Embedder;
import dev.keyvector.connector.model.RowKey;
import dev.keyvector.connector.sink.Hit;
import dev.keyvector.connector.sink.VectorSink;

/**
 * Semantic search over a Keyspaces table: embed the question, find the nearest vectors, then
 * read those rows from Keyspaces - so a caller gets current rows, not a stale copy of them.
 * A hit whose row is gone (deleted since the index was written) is dropped from the results.
 */
public final class KeyvectorQuery {

    private final Embedder embedder;
    private final VectorSink sink;
    private final KeyspacesRows rows;

    /** @param rows null to return keys and metadata without reading Keyspaces */
    public KeyvectorQuery(Embedder embedder, VectorSink sink, KeyspacesRows rows) {
        this.embedder = embedder;
        this.sink = sink;
        this.rows = rows;
    }

    public record Result(String key, Float distance, Map<String, Object> metadata, Map<String, Object> row) {
    }

    public List<Result> search(String text, int topK, Map<String, Object> filter) throws Exception {
        float[] v = embedder.embedQuery(text);
        List<Hit> hits = sink.query(v, topK, filter);
        if (rows == null) {
            List<Result> out = new ArrayList<>(hits.size());
            hits.forEach(h -> out.add(new Result(h.key(), h.distance(), h.metadata(), null)));
            return out;
        }
        List<CompletableFuture<Result>> pending = new ArrayList<>(hits.size());
        for (Hit h : hits) {
            pending.add(CompletableFuture.supplyAsync(() -> {
                Map<String, Object> row = rows.fetch(RowKey.decode(h.key()));
                return row == null ? null : new Result(h.key(), h.distance(), h.metadata(), row);
            }));
        }
        List<Result> out = new ArrayList<>(hits.size());
        for (CompletableFuture<Result> f : pending) {
            Result r = f.join();
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }
}
