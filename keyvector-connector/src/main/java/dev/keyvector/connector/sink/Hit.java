package dev.keyvector.connector.sink;

import java.util.Map;

public record Hit(String key, Float distance, Map<String, Object> metadata) {
}
