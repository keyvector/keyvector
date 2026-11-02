package dev.keyvector.connector.backfill;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.keyvector.connector.SyncEngine;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Drains the dead-letter queue: every parked row is resynced from Keyspaces, and a message is
 * deleted only after its rows were applied. A message that fails again stays on the queue.
 */
public final class Redrive {

    private static final Logger logger = LoggerFactory.getLogger(Redrive.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Resync resync;
    private final SqsClient sqs;
    private final String queueUrl;

    public Redrive(Resync resync, String queueUrl, String region) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalArgumentException("No dead-letter queue: pass --queue-url or set processor.dead-letter-queue-url");
        }
        this.resync = resync;
        this.queueUrl = queueUrl;
        this.sqs = SqsClient.builder().region(Region.of(region)).build();
    }

    public SyncEngine.Result drain() {
        SyncEngine.Result total = SyncEngine.Result.empty();
        int emptyPolls = 0;
        int failed = 0;
        while (emptyPolls < 2) {
            List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(5).build()).messages();
            if (messages.isEmpty()) {
                emptyPolls++;
                continue;
            }
            emptyPolls = 0;
            for (Message m : messages) {
                try {
                    total = total.plus(resync.keys(keys(m.body())));
                    sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(m.receiptHandle()).build());
                } catch (Exception e) {
                    failed++;
                    logger.error("Could not redrive message {}; it stays on the queue: {}", m.messageId(), e.toString());
                }
            }
        }
        if (failed > 0) {
            logger.warn("{} messages could not be redriven and remain on the queue", failed);
        }
        return total;
    }

    static List<String> keys(String body) throws Exception {
        JsonNode node = MAPPER.readTree(body);
        if (!"keyvector.dead-letter.v1".equals(node.path("kind").asText())) {
            throw new IllegalArgumentException("Not a Keyvector dead-letter message");
        }
        List<String> out = new ArrayList<>();
        node.path("keys").forEach(k -> out.add(k.asText()));
        return out;
    }
}
