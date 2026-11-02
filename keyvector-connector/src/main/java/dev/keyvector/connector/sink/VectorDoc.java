package dev.keyvector.connector.sink;

import java.util.Map;

public record VectorDoc(String key, float[] vector, Map<String, Object> metadata) {
}
