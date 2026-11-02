package dev.keyvector.connector.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which columns travel with a vector, as values S3 Vectors can store and filter on:
 * strings, numbers, booleans and lists of strings.
 *
 * <p>A timestamp becomes epoch milliseconds, because S3 Vectors' range operators
 * ({@code $gt}, {@code $lt}) work on numbers only - a timestamp stored as text could be
 * matched but never filtered by date. A blob is left out. A null column is left out rather
 * than stored as null.
 */
public final class MetadataMapper {

    private final List<String> columns;
    private final String textKey;

    public MetadataMapper(List<String> columns, String textKey) {
        this.columns = columns == null ? List.of() : List.copyOf(columns);
        this.textKey = textKey == null || textKey.isBlank() ? null : textKey.trim();
        if (this.textKey != null && this.columns.contains(this.textKey)) {
            throw new IllegalArgumentException("sync.store-text-as (" + textKey + ") clashes with a metadata column");
        }
    }

    public Map<String, Object> map(Map<String, Object> row, String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (row != null) {
            for (String c : columns) {
                Object v = toMetadata(row.get(c));
                if (v != null) {
                    out.put(c, v);
                }
            }
        }
        if (textKey != null && text != null && !text.isEmpty()) {
            out.put(textKey, text);
        }
        return out;
    }

    static Object toMetadata(Object v) {
        if (v == null || v instanceof byte[]) {
            return null;
        }
        if (v instanceof String || v instanceof Boolean || v instanceof Long || v instanceof Double
            || v instanceof BigDecimal || v instanceof BigInteger) {
            return v;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof Instant) {
            return ((Instant) v).toEpochMilli();
        }
        if (v instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object e : (List<?>) v) {
                if (e != null) {
                    out.add(Values.displayText(e));
                }
            }
            return out;
        }
        String s = Values.displayText(v);
        return s.isEmpty() ? null : s;
    }
}
