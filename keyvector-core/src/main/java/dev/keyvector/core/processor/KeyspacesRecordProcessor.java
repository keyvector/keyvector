package dev.keyvector.core.processor;

import software.amazon.keyspaces.streamsadapter.adapter.KeyspacesStreamsClientRecord;
import software.amazon.keyspaces.streamsadapter.model.KeyspacesStreamsProcessRecordsInput;
import software.amazon.keyspaces.streamsadapter.processor.KeyspacesStreamsShardRecordProcessor;
import software.amazon.kinesis.lifecycle.events.InitializationInput;
import software.amazon.kinesis.lifecycle.events.LeaseLostInput;
import software.amazon.kinesis.lifecycle.events.ShardEndedInput;
import software.amazon.kinesis.lifecycle.events.ShutdownRequestedInput;
import software.amazon.kinesis.processor.RecordProcessorCheckpointer;
import dev.keyvector.core.connector.ITargetMapper;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies each batch at least once, in order, and checkpoints only after it was applied
 * or parked in the dead-letter queue. See {@link DeliveryPolicy} for why.
 */
public class KeyspacesRecordProcessor implements KeyspacesStreamsShardRecordProcessor {
    private static final Logger logger = LoggerFactory.getLogger(KeyspacesRecordProcessor.class);

    private String shardId;
    private final ITargetMapper targetMapper;
    private final DeliveryPolicy policy;
    private final DeadLetterQueue deadLetterQueue;
    private volatile boolean stopping = false;

    public KeyspacesRecordProcessor(ITargetMapper targetMapper) {
        this(targetMapper, new DeliveryPolicy(10, 500, 30000, null), null);
    }

    public KeyspacesRecordProcessor(ITargetMapper targetMapper, DeliveryPolicy policy, DeadLetterQueue deadLetterQueue) {
        this.targetMapper = targetMapper;
        this.policy = policy;
        this.deadLetterQueue = deadLetterQueue;
    }

    @Override
    public void initialize(InitializationInput initializationInput) {
        this.shardId = initializationInput.shardId();
        targetMapper.initialize();
        logger.info("Initialized record processor for shard {} with {}", shardId, targetMapper.getClass().getSimpleName());
    }

    @Override
    public void processRecords(KeyspacesStreamsProcessRecordsInput processRecordsInput) {
        List<KeyspacesStreamsClientRecord> records = processRecordsInput.records();
        if (records == null || records.isEmpty()) {
            return;
        }
        if (!apply(records)) {
            // Stopping mid-retry: do not checkpoint, so the next owner of this shard starts here.
            return;
        }
        checkpoint(processRecordsInput.checkpointer());
    }

    /** @return true once the batch is applied or parked; false if the processor is stopping. */
    boolean apply(List<KeyspacesStreamsClientRecord> records) {
        int attempt = 0;
        while (true) {
            if (stopping || Thread.currentThread().isInterrupted()) {
                logger.warn("Shard {}: stopping with an unapplied batch of {} records; it will be re-read", shardId, records.size());
                return false;
            }
            attempt++;
            try {
                targetMapper.handleRecords(targetMapper.filterRecords(records));
                if (attempt > 1) {
                    logger.info("Shard {}: batch applied on attempt {}", shardId, attempt);
                }
                return true;
            } catch (Exception e) {
                logger.error("Shard {}: attempt {} to apply {} records failed: {}", shardId, attempt, records.size(), e.toString(), e);
                if (attempt >= policy.maxAttempts() && deadLetterQueue != null) {
                    try {
                        deadLetterQueue.send(shardId, sequenceOf(records.get(0)), sequenceOf(records.get(records.size() - 1)),
                            targetMapper.deadLetterKeys(records), e);
                        logger.error("Shard {}: parked {} records in the dead-letter queue after {} attempts", shardId, records.size(), attempt);
                        return true;
                    } catch (Exception dlqError) {
                        logger.error("Shard {}: could not write to the dead-letter queue, will keep retrying: {}", shardId, dlqError.toString(), dlqError);
                    }
                } else if (attempt >= policy.maxAttempts()) {
                    logger.error("Shard {}: still failing after {} attempts and no dead-letter queue is configured; "
                        + "the shard will not advance until this batch is applied", shardId, attempt);
                }
                sleep(policy.delayAfter(attempt));
            }
        }
    }

    private static String sequenceOf(KeyspacesStreamsClientRecord record) {
        try {
            return record.getRecord().sequenceNumber();
        } catch (Exception e) {
            return null;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void checkpoint(RecordProcessorCheckpointer checkpointer) {
        try {
            checkpointer.checkpoint();
            logger.debug("Checkpoint successful for shard: {}", shardId);
        } catch (Exception e) {
            // Not fatal: the batch was applied and the target is idempotent, so a missed
            // checkpoint only means it may be applied again after a restart.
            logger.warn("Checkpoint failed for shard {}: {}", shardId, e.toString());
        }
    }

    @Override
    public void leaseLost(LeaseLostInput leaseLostInput) {
        stopping = true;
        logger.warn("Lease lost for shard: {}", shardId);
    }

    @Override
    public void shardEnded(ShardEndedInput shardEndedInput) {
        logger.info("Shard ended: {}", shardId);
        try {
            shardEndedInput.checkpointer().checkpoint();
        } catch (Exception e) {
            throw new RuntimeException("Error while final checkpointing for shard " + shardId, e);
        }
    }

    @Override
    public void shutdownRequested(ShutdownRequestedInput shutdownRequestedInput) {
        stopping = true;
        logger.info("Shutdown requested for shard {}", shardId);
        try {
            shutdownRequestedInput.checkpointer().checkpoint();
        } catch (Exception e) {
            logger.error("Error while checkpointing on shutdown for shard: {} - {}", shardId, e.getMessage(), e);
        }
    }
}
