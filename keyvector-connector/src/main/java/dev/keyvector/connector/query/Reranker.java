package dev.keyvector.connector.query;

import java.util.List;

/** Scores how well each text answers a query; higher is better. */
public interface Reranker extends AutoCloseable {

    float[] score(String query, List<String> texts) throws Exception;

    String describe();

    @Override
    default void close() {
    }
}
