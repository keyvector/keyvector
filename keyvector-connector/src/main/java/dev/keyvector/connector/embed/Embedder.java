package dev.keyvector.connector.embed;

import java.util.List;

/**
 * Turns text into vectors. Implementations batch and retry internally; a thrown exception
 * means the batch could not be embedded and the caller should retry it later.
 */
public interface Embedder extends AutoCloseable {

    /** One vector per text, in the same order. */
    List<float[]> embedDocuments(List<String> texts) throws Exception;

    float[] embedQuery(String text) throws Exception;

    /** For logs and {@code keyvector check}: which model, where. Never a credential. */
    String describe();

    @Override
    default void close() {
    }
}
