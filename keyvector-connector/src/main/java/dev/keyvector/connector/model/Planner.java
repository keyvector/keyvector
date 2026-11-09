package dev.keyvector.connector.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a batch of row changes into the fewest index writes that leave the index right.
 *
 * <p>Changes to the same row are collapsed: only the row's state before the batch and after
 * it matter. Then, per row:
 * <ul>
 *   <li>gone (deleted or expired) - delete the vector;</li>
 *   <li>its text renders empty - delete the vector, or leave it, per {@code on-empty-text};</li>
 *   <li>text unchanged and metadata unchanged - nothing, which is what keeps an update to an
 *       unrelated column from paying for an embedding;</li>
 *   <li>text unchanged, metadata changed - rewrite with the stored vector;</li>
 *   <li>otherwise - embed and write.</li>
 * </ul>
 * Pure: no AWS, no clock, so every rule above is a unit test.
 */
public final class Planner {

    private static final Logger logger = LoggerFactory.getLogger(Planner.class);

    public enum EmptyText { DELETE, SKIP }

    private final TextTemplate text;
    private final MetadataMapper metadata;
    private final EmptyText onEmptyText;
    private final int keyReserve;

    public Planner(TextTemplate text, MetadataMapper metadata, EmptyText onEmptyText) {
        this(text, metadata, onEmptyText, 0);
    }

    /** @param keyReserve characters to keep free in each row key, for a chunk suffix */
    public Planner(TextTemplate text, MetadataMapper metadata, EmptyText onEmptyText, int keyReserve) {
        this.text = Objects.requireNonNull(text);
        this.metadata = Objects.requireNonNull(metadata);
        this.onEmptyText = Objects.requireNonNull(onEmptyText);
        this.keyReserve = keyReserve;
    }

    public TextTemplate text() {
        return text;
    }

    public MetadataMapper metadata() {
        return metadata;
    }

    public record Plan(List<Action> actions, int unchanged, int rejected) {
    }

    public Plan plan(List<Change> changes) {
        Map<String, Change> first = new LinkedHashMap<>();
        Map<String, Change> last = new LinkedHashMap<>();
        int rejected = 0;
        for (Change c : changes) {
            String key;
            try {
                key = RowKey.encode(c.key(), keyReserve);
            } catch (IllegalArgumentException e) {
                // Retrying cannot make this row's key valid, so holding the shard for it would
                // stall every other row behind it.
                logger.error("Skipping a row whose key cannot be stored (sequence {}): {}", c.sequence(), e.getMessage());
                rejected++;
                continue;
            }
            first.putIfAbsent(key, c);
            last.put(key, c);
        }

        List<Action> actions = new ArrayList<>(last.size());
        int unchanged = 0;
        for (Map.Entry<String, Change> e : last.entrySet()) {
            String key = e.getKey();
            Change end = e.getValue();
            Map<String, Object> before = first.get(key).oldRow();

            if (end.deleted()) {
                actions.add(Action.delete(key));
                continue;
            }
            String newText = text.render(end.newRow());
            if (newText.isEmpty()) {
                if (onEmptyText == EmptyText.DELETE) {
                    actions.add(Action.delete(key));
                } else {
                    unchanged++;
                }
                continue;
            }
            Map<String, Object> newMeta = metadata.map(end.newRow(), newText);
            if (before == null) {
                actions.add(Action.upsert(key, newText, newMeta, false));
                continue;
            }
            String oldText = text.render(before);
            boolean textChanged = !oldText.equals(newText);
            boolean metaChanged = !metadata.map(before, oldText).equals(newMeta);
            if (!textChanged && !metaChanged) {
                unchanged++;
            } else {
                actions.add(Action.upsert(key, newText, newMeta, !textChanged));
            }
        }
        return new Plan(actions, unchanged, rejected);
    }
}
