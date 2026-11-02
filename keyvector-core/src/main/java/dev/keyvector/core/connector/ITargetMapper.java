package dev.keyvector.core.connector;

import java.util.List;

import software.amazon.keyspaces.streamsadapter.adapter.KeyspacesStreamsClientRecord;

public interface ITargetMapper {

    /**
     * Called once per shard processor before records arrive. One mapper instance is
     * shared by every shard, so implementations must be idempotent and thread-safe.
     */
    public void initialize();

    public List<KeyspacesStreamsClientRecord> filterRecords(List<KeyspacesStreamsClientRecord> records);

    /**
     * Applies a batch to the target. Throwing means "not applied": the processor retries
     * the same batch and does not checkpoint past it. Implementations must therefore be
     * idempotent - applying a batch twice must leave the target as applying it once.
     */
    public void handleRecords(List<KeyspacesStreamsClientRecord> records)  throws Exception;

    /**
     * What to write to the dead-letter queue when a batch is given up on: identifiers that
     * let an operator re-sync exactly those rows later. The default records nothing beyond
     * the sequence numbers the processor adds itself.
     */
    public default List<String> deadLetterKeys(List<KeyspacesStreamsClientRecord> records) {
        return List.of();
    }
}
