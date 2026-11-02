package dev.keyvector.connector.model;

import java.util.Map;

/**
 * One change to one row, from either source.
 *
 * @param key      the primary key columns, normalized by {@link Values}
 * @param oldRow   the row before the change, or null when unknown (an insert, a backfill,
 *                 or a stream that records new images only)
 * @param newRow   the row after the change; null when the row is gone (delete or TTL)
 * @param sequence the stream sequence number, or null for a backfill
 */
public record Change(Map<String, Object> key, Map<String, Object> oldRow, Map<String, Object> newRow, String sequence) {

    public boolean deleted() {
        return newRow == null;
    }
}
