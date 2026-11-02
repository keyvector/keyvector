package dev.keyvector.core.processor;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Parks a batch that could not be applied. The message names the rows, not their contents:
 * the redrive re-reads each row's current state from Keyspaces, which is both smaller than
 * replaying the change and correct even if the row changed again since.
 */
public class DeadLetterQueue {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** SQS allows 256 KiB per message; stay well under it. */
    static final int MAX_KEYS_PER_MESSAGE = 500;

    private final String queueUrl;
    private final SqsClient sqs;

    public DeadLetterQueue(String queueUrl, String region) {
        this.queueUrl = queueUrl;
        this.sqs = SqsClient.builder().region(Region.of(region)).build();
    }

    public void send(String shardId, String firstSequence, String lastSequence, List<String> keys, Exception cause) {
        int total = keys.size();
        int parts = Math.max(1, (total + MAX_KEYS_PER_MESSAGE - 1) / MAX_KEYS_PER_MESSAGE);
        for (int part = 0; part < parts; part++) {
            List<String> slice = keys.subList(Math.min(total, part * MAX_KEYS_PER_MESSAGE), Math.min(total, (part + 1) * MAX_KEYS_PER_MESSAGE));
            sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body(shardId, firstSequence, lastSequence, slice, cause))
                .build());
        }
    }

    static String body(String shardId, String firstSequence, String lastSequence, List<String> keys, Exception cause) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("kind", "keyvector.dead-letter.v1");
        node.put("shardId", shardId);
        node.put("firstSequenceNumber", firstSequence);
        node.put("lastSequenceNumber", lastSequence);
        node.put("error", cause == null ? null : String.valueOf(cause.getMessage()));
        ArrayNode arr = node.putArray("keys");
        keys.forEach(arr::add);
        return node.toString();
    }
}
