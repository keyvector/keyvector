package dev.keyvector.connector.sink;

import java.util.List;
import java.util.Map;

/**
 * Where vectors live. Every write must be idempotent: the stream delivers at least once, so
 * the same put or delete can arrive twice and must leave the index as one would.
 */
public interface VectorSink extends AutoCloseable {

    void put(List<VectorDoc> docs) throws Exception;

    void delete(List<String> keys) throws Exception;

    /** The stored vectors for these keys; a key with no vector is absent from the result. */
    Map<String, float[]> vectors(List<String> keys) throws Exception;

    /** Nearest neighbours. {@code filter} is the store's own filter syntax, or null. */
    List<Hit> query(float[] vector, int topK, Map<String, Object> filter) throws Exception;

    /** The index's dimension, so a mismatch is caught before the first write rather than on it. */
    int dimensions() throws Exception;

    String describe();

    @Override
    default void close() {
    }
}
