package dev.keyvector.connector.backfill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.keyvector.connector.SyncEngine;
import dev.keyvector.connector.model.Change;
import dev.keyvector.connector.model.RowKey;

/**
 * Brings named rows back in line with Keyspaces: re-reads each row's current state and writes
 * it, or deletes the vector if the row is gone. Used for dead-lettered rows and for fixing a
 * row by hand. Reading current state, rather than replaying an old change, is what makes this
 * safe to run at any time.
 */
public final class Resync {

    private final KeyspacesRows rows;
    private final SyncEngine engine;

    public Resync(KeyspacesRows rows, SyncEngine engine) {
        this.rows = rows;
        this.engine = engine;
    }

    public SyncEngine.Result keys(List<String> keys) throws Exception {
        List<Change> changes = new ArrayList<>(keys.size());
        for (String key : keys) {
            Map<String, String> decoded = RowKey.decode(key);
            Map<String, Object> row = rows.fetch(decoded);
            Map<String, Object> keyColumns = new LinkedHashMap<>();
            if (row != null) {
                keyColumns = rows.keyOf(row);
            } else {
                // Gone: the planner only needs a key that encodes back to the same string.
                Map<String, Object> asText = keyColumns;
                decoded.forEach(asText::put);
            }
            changes.add(new Change(keyColumns, null, row, null));
        }
        return engine.apply(changes);
    }
}
