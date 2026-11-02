package dev.keyvector.connector.model;

import java.util.Map;

/** What to do to the vector index for one row, after a batch's changes are collapsed. */
public record Action(Kind kind, String key, String text, Map<String, Object> metadata, boolean reuseVector) {

    public enum Kind {
        /** Embed {@code text} (or reuse the stored vector) and write it with {@code metadata}. */
        UPSERT,
        /** Remove the vector. Deleting one that is not there is not an error. */
        DELETE
    }

    public static Action upsert(String key, String text, Map<String, Object> metadata, boolean reuseVector) {
        return new Action(Kind.UPSERT, key, text, metadata, reuseVector);
    }

    public static Action delete(String key) {
        return new Action(Kind.DELETE, key, null, null, false);
    }
}
