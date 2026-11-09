package dev.keyvector.connector.model;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The vector key for a row: its primary key, written so that it can be read back.
 *
 * <p>Columns are sorted by name and written {@code name=value&name=value}, each part
 * percent-encoded, so a value containing {@code &} or {@code =} cannot be mistaken for a
 * separator. The same row always gets the same key, and a query hit can be turned back
 * into a {@code WHERE} clause without storing anything else.
 */
public final class RowKey {

    /** S3 Vectors' limit on a vector key. */
    public static final int MAX_LENGTH = 1024;

    private RowKey() {
    }

    /** Separates a row key from a chunk number. '#' never appears in an encoded row key: it is percent-encoded. */
    public static final char CHUNK_SEPARATOR = '#';

    public static String encode(Map<String, Object> keyColumns) {
        return encode(keyColumns, 0);
    }

    /**
     * @param reserve characters to leave free under {@link #MAX_LENGTH}, for a chunk suffix
     */
    public static String encode(Map<String, Object> keyColumns, int reserve) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException("A row key needs at least one primary key column");
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : new TreeMap<>(keyColumns).entrySet()) {
            if (e.getValue() == null) {
                throw new IllegalArgumentException("Primary key column " + e.getKey() + " is null");
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(enc(e.getKey())).append('=').append(enc(Values.keyText(e.getValue())));
        }
        if (sb.length() > MAX_LENGTH - reserve) {
            throw new KeyTooLongException(sb.length(), MAX_LENGTH - reserve);
        }
        return sb.toString();
    }

    /** The vector key for chunk {@code n} of a row. */
    public static String chunkKey(String rowKey, int n) {
        return rowKey + CHUNK_SEPARATOR + n;
    }

    /** The row a vector key belongs to: the key itself, or the part before a chunk suffix. */
    public static String rowOf(String vectorKey) {
        int i = vectorKey.lastIndexOf(CHUNK_SEPARATOR);
        return i < 0 ? vectorKey : vectorKey.substring(0, i);
    }

    /** The chunk number in a vector key, or -1 for an unchunked key. */
    public static int chunkOf(String vectorKey) {
        int i = vectorKey.lastIndexOf(CHUNK_SEPARATOR);
        if (i < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(vectorKey.substring(i + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Column name to the value's key text, in the encoded order. */
    public static Map<String, String> decode(String key) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : rowOf(key).split("&", -1)) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Not a Keyvector row key: " + key);
            }
            out.put(dec(part.substring(0, eq)), dec(part.substring(eq + 1)));
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public static final class KeyTooLongException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        KeyTooLongException(int length, int limit) {
            super("Row key is " + length + " characters; the limit is " + limit
                + (limit < MAX_LENGTH ? " (S3 Vectors allows " + MAX_LENGTH + ", less room for the chunk number)" : " (S3 Vectors' limit)"));
        }
    }
}
