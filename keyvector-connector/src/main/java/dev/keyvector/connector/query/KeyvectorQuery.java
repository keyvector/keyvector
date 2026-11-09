package dev.keyvector.connector.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dev.keyvector.connector.embed.Embedder;
import dev.keyvector.connector.model.Chunker;
import dev.keyvector.connector.model.RowKey;
import dev.keyvector.connector.model.TextTemplate;
import dev.keyvector.connector.sink.Hit;
import dev.keyvector.connector.sink.VectorSink;

/**
 * Semantic search over a table: embed the question, find the nearest vectors, then read those
 * rows from the table - so a caller gets current rows, not a stale copy of them. A hit whose row
 * is gone (deleted since the index was written) is dropped from the results.
 *
 * <p>With chunking, several chunks of one row can match; each row appears once, at its best
 * chunk, and more vectors are fetched so that {@code topK} distinct rows remain. With a
 * {@link Reranker}, the best {@code candidates} rows are re-scored by reading the query and each
 * row's text (or its matching chunk, rebuilt from the row) together, and the top {@code topK} by
 * that score are returned.
 */
public final class KeyvectorQuery {

    /** Reads one row by its primary key; null when the row is gone. */
    @FunctionalInterface
    public interface RowSource {
        Map<String, Object> fetch(Map<String, String> key) throws Exception;
    }

    /** Most vectors one query asks the index for. */
    static final int MAX_FETCH = 100;

    private final Embedder embedder;
    private final VectorSink sink;
    private final RowSource rows;
    private final TextTemplate text;
    private final Chunker chunker;
    private final String textKey;
    private final Reranker reranker;
    private final int candidates;

    /** Plain vector search, with no chunking or reranking. */
    public KeyvectorQuery(Embedder embedder, VectorSink sink, RowSource rows) {
        this(embedder, sink, rows, null, null, null, null, 0);
    }

    /**
     * @param rows      null to return keys and metadata without reading the table
     * @param text      how a row becomes text; needed to rerank
     * @param chunker   null when the index holds one vector per row
     * @param textKey   metadata key holding the embedded text, or null; lets reranking work without rows
     * @param reranker  null to rank by vector distance only
     */
    public KeyvectorQuery(Embedder embedder, VectorSink sink, RowSource rows, TextTemplate text, Chunker chunker,
                          String textKey, Reranker reranker, int candidates) {
        this.embedder = embedder;
        this.sink = sink;
        this.rows = rows;
        this.text = text;
        this.chunker = chunker;
        this.textKey = textKey;
        this.reranker = reranker;
        this.candidates = candidates;
        if (reranker != null && text == null && textKey == null) {
            throw new IllegalArgumentException("Reranking needs the row text: a text template or a stored-text key");
        }
    }

    /**
     * @param key    the row's key (without a chunk number)
     * @param chunk  the best-matching chunk, or null when the index is not chunked
     * @param score  the reranker's score, or null without a reranker
     */
    public record Result(String key, Integer chunk, Float distance, Float score, Map<String, Object> metadata,
                         Map<String, Object> row) {
    }

    public List<Result> search(String query, int topK, Map<String, Object> filter) throws Exception {
        int want = reranker != null ? Math.max(topK, candidates) : topK;
        int fetch = Math.min(MAX_FETCH, chunker != null ? want * 3 : want);
        float[] v = embedder.embedQuery(query);

        // Best hit per row, in distance order.
        Map<String, Hit> best = new LinkedHashMap<>();
        for (Hit h : sink.query(v, fetch, filter)) {
            best.putIfAbsent(RowKey.rowOf(h.key()), h);
            if (best.size() == want) {
                break;
            }
        }
        List<Result> found = readRows(best);
        if (reranker == null || found.isEmpty()) {
            return found.size() > topK ? found.subList(0, topK) : found;
        }
        List<String> texts = new ArrayList<>(found.size());
        for (Result r : found) {
            texts.add(textOf(r));
        }
        float[] scores = reranker.score(query, texts);
        List<Result> scored = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            Result r = found.get(i);
            scored.add(new Result(r.key(), r.chunk(), r.distance(), scores[i], r.metadata(), r.row()));
        }
        scored.sort(Comparator.comparing(Result::score).reversed());
        return scored.size() > topK ? scored.subList(0, topK) : scored;
    }

    private List<Result> readRows(Map<String, Hit> best) {
        List<CompletableFuture<Result>> pending = new ArrayList<>(best.size());
        for (Map.Entry<String, Hit> e : best.entrySet()) {
            String rowKey = e.getKey();
            Hit h = e.getValue();
            int c = RowKey.chunkOf(h.key());
            Integer chunk = c < 0 ? null : c;
            if (rows == null) {
                pending.add(CompletableFuture.completedFuture(new Result(rowKey, chunk, h.distance(), null, h.metadata(), null)));
                continue;
            }
            pending.add(CompletableFuture.supplyAsync(() -> {
                try {
                    Map<String, Object> row = rows.fetch(RowKey.decode(rowKey));
                    return row == null ? null : new Result(rowKey, chunk, h.distance(), null, h.metadata(), row);
                } catch (Exception ex) {
                    throw new IllegalStateException("Reading row " + rowKey + " failed: " + ex.getMessage(), ex);
                }
            }));
        }
        List<Result> out = new ArrayList<>(pending.size());
        for (CompletableFuture<Result> f : pending) {
            Result r = f.join();
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** The text the reranker reads: the row's current text, or the chunk that matched. */
    private String textOf(Result r) {
        if (r.row() != null && text != null) {
            String full = text.render(r.row());
            if (chunker != null && r.chunk() != null) {
                List<String> chunks = chunker.split(full);
                if (r.chunk() < chunks.size()) {
                    return chunks.get(r.chunk());
                }
            }
            return full;
        }
        Object stored = textKey == null || r.metadata() == null ? null : r.metadata().get(textKey);
        if (stored == null) {
            throw new IllegalStateException("Cannot rerank " + r.key() + ": no row was read and no text is stored with the vector");
        }
        return stored.toString();
    }
}
