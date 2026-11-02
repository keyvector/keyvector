package dev.keyvector.core.processor;

import software.amazon.kinesis.processor.ShardRecordProcessor;
import software.amazon.kinesis.processor.ShardRecordProcessorFactory;
import dev.keyvector.core.connector.ITargetMapper;

public class KeyspacesRecordProcessorFactory implements ShardRecordProcessorFactory {

    private final ITargetMapper targetMapper;
    private final DeliveryPolicy policy;
    private final DeadLetterQueue deadLetterQueue;

    public KeyspacesRecordProcessorFactory(ITargetMapper targetMapper) {
        this(targetMapper, new DeliveryPolicy(10, 500, 30000, null), null);
    }

    public KeyspacesRecordProcessorFactory(ITargetMapper targetMapper, DeliveryPolicy policy, DeadLetterQueue deadLetterQueue) {
        this.targetMapper = targetMapper;
        this.policy = policy;
        this.deadLetterQueue = deadLetterQueue;
    }

    @Override
    public ShardRecordProcessor shardRecordProcessor() {
        return new KeyspacesRecordProcessor(targetMapper, policy, deadLetterQueue);
    }
}
