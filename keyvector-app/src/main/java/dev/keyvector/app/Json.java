package dev.keyvector.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Rows and results as JSON for the command line. */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(plain(o));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Object plain(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Instant || v instanceof LocalDate || v instanceof UUID) {
            return v.toString();
        }
        if (v instanceof byte[]) {
            return "0x" + HexFormat.of().formatHex((byte[]) v);
        }
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<?, ?>) v).forEach((k, val) -> out.put(String.valueOf(k), plain(val)));
            return out;
        }
        if (v instanceof List) {
            return ((List<?>) v).stream().map(Json::plain).toList();
        }
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readMap(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("--filter is not a JSON object: " + e.getMessage());
        }
    }
}
