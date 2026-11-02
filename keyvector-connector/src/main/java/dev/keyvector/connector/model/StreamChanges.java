package dev.keyvector.connector.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.keyspacesstreams.model.KeyspacesCell;
import software.amazon.awssdk.services.keyspacesstreams.model.KeyspacesCellValue;
import software.amazon.awssdk.services.keyspacesstreams.model.KeyspacesRow;
import software.amazon.awssdk.services.keyspacesstreams.model.Record;

/** Reads a Keyspaces CDC record into a {@link Change}. */
public final class StreamChanges {

    private StreamChanges() {
    }

    /**
     * @param keyColumns used only when the record carries no partition or clustering keys of
     *                   its own; then the key is read from the row images
     */
    public static Change from(Record record, List<String> keyColumns) {
        Map<String, Object> key = new LinkedHashMap<>();
        putAll(key, record.hasPartitionKeys() ? record.partitionKeys() : null);
        putAll(key, record.hasClusteringKeys() ? record.clusteringKeys() : null);

        Map<String, Object> oldRow = row(record.oldImage(), key);
        Map<String, Object> newRow = row(record.newImage(), key);

        if (key.isEmpty() && keyColumns != null) {
            Map<String, Object> source = newRow != null ? newRow : oldRow;
            if (source != null) {
                for (String c : keyColumns) {
                    key.put(c, source.get(c));
                }
            }
        }
        return new Change(key, oldRow, newRow, record.sequenceNumber());
    }

    private static void putAll(Map<String, Object> out, Map<String, KeyspacesCellValue> cells) {
        if (cells != null) {
            cells.forEach((name, v) -> out.put(name, Values.fromStream(v)));
        }
    }

    /**
     * A row image with the primary key columns added, so that a template or a metadata column
     * can name a key column whether or not the image repeats it.
     */
    private static Map<String, Object> row(KeyspacesRow image, Map<String, Object> key) {
        if (image == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(key);
        cells(out, image.hasStaticCells() ? image.staticCells() : null);
        cells(out, image.hasValueCells() ? image.valueCells() : null);
        return out;
    }

    private static void cells(Map<String, Object> out, Map<String, KeyspacesCell> cells) {
        if (cells != null) {
            cells.forEach((name, cell) -> out.put(name, Values.fromStream(cell)));
        }
    }
}
