package dev.keyvector.connector.embed;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Splits a batch into requests of {@code batchSize}, sends up to {@code concurrency} at once,
 * applies the document and query prefixes some models need (E5 wants {@code "passage: "} and
 * {@code "query: "}), and checks every vector has the dimensions the index expects.
 */
abstract class BatchingEmbedder implements Embedder {

    private final int batchSize;
    private final int dimensions;
    private final String documentPrefix;
    private final String queryPrefix;
    private final ExecutorService pool;

    BatchingEmbedder(int batchSize, int concurrency, int dimensions, String documentPrefix, String queryPrefix) {
        if (batchSize < 1 || concurrency < 1) {
            throw new IllegalArgumentException("embedder.batch-size and embedder.concurrency must be positive");
        }
        if (dimensions < 1 || dimensions > 4096) {
            throw new IllegalArgumentException("embedder.dimensions must be between 1 and 4096 (S3 Vectors' range)");
        }
        this.batchSize = batchSize;
        this.dimensions = dimensions;
        this.documentPrefix = documentPrefix == null ? "" : documentPrefix;
        this.queryPrefix = queryPrefix == null ? "" : queryPrefix;
        this.pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "keyvector-embed");
            t.setDaemon(true);
            return t;
        });
    }

    /** One request: at most {@code batchSize} texts, prefixes already applied. */
    protected abstract List<float[]> request(List<String> texts, boolean query) throws Exception;

    @Override
    public List<float[]> embedDocuments(List<String> texts) throws Exception {
        List<Future<List<float[]>>> parts = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> slice = new ArrayList<>();
            for (String t : texts.subList(i, Math.min(texts.size(), i + batchSize))) {
                slice.add(documentPrefix + t);
            }
            parts.add(pool.submit(() -> request(slice, false)));
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (Future<List<float[]>> f : parts) {
            try {
                out.addAll(f.get());
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
            }
        }
        if (out.size() != texts.size()) {
            throw new IllegalStateException("Asked for " + texts.size() + " embeddings and got " + out.size());
        }
        out.forEach(this::checkDimensions);
        return out;
    }

    @Override
    public float[] embedQuery(String text) throws Exception {
        float[] v = request(List.of(queryPrefix + text), true).get(0);
        checkDimensions(v);
        return v;
    }

    private void checkDimensions(float[] v) {
        if (v.length != dimensions) {
            throw new DimensionMismatchException(v.length, dimensions, describe());
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }

    /** Not retryable: the model and the index disagree, and only configuration can fix that. */
    public static final class DimensionMismatchException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        DimensionMismatchException(int got, int expected, String model) {
            super(model + " returned " + got + " dimensions but embedder.dimensions is " + expected
                + "; they must match each other and the vector index");
        }
    }
}
