package dev.keyvector.core.processor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.keyvector.core.connector.ITargetMapper;
import software.amazon.keyspaces.streamsadapter.adapter.KeyspacesStreamsClientRecord;

/**
 * The upstream sample swallowed a failed batch and checkpointed past it on the next success.
 * These pin the replacement: retry until applied, park in the dead-letter queue when told to,
 * and never report success for a batch that was not applied.
 */
class KeyspacesRecordProcessorTest {

    private static final DeliveryPolicy FAST = new DeliveryPolicy(3, 1, 2, null);

    private static ITargetMapper failing(int times, AtomicInteger calls) {
        return new ITargetMapper() {
            @Override
            public void initialize() {
            }

            @Override
            public List<KeyspacesStreamsClientRecord> filterRecords(List<KeyspacesStreamsClientRecord> records) {
                return records;
            }

            @Override
            public void handleRecords(List<KeyspacesStreamsClientRecord> records) throws Exception {
                if (calls.incrementAndGet() <= times) {
                    throw new Exception("target down");
                }
            }

            @Override
            public List<String> deadLetterKeys(List<KeyspacesStreamsClientRecord> records) {
                return List.of("id=1");
            }
        };
    }

    private static List<KeyspacesStreamsClientRecord> batch() {
        return List.of(mock(KeyspacesStreamsClientRecord.class, RETURNS_DEEP_STUBS));
    }

    @Test
    void retriesUntilApplied() {
        AtomicInteger calls = new AtomicInteger();
        KeyspacesRecordProcessor p = new KeyspacesRecordProcessor(failing(5, calls), FAST, null);
        assertTrue(p.apply(batch()));
        assertEquals(6, calls.get(), "without a dead-letter queue it keeps going past max-attempts");
    }

    @Test
    void parksInTheDeadLetterQueueAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        DeadLetterQueue dlq = mock(DeadLetterQueue.class);
        KeyspacesRecordProcessor p = new KeyspacesRecordProcessor(failing(100, calls), FAST, dlq);
        assertTrue(p.apply(batch()));
        assertEquals(3, calls.get());
        verify(dlq).send(any(), any(), any(), eq(List.of("id=1")), any());
    }

    @Test
    void aDeadLetterQueueThatFailsMeansKeepRetrying() {
        AtomicInteger calls = new AtomicInteger();
        DeadLetterQueue dlq = mock(DeadLetterQueue.class);
        doThrow(new RuntimeException("sqs down")).doNothing().when(dlq).send(any(), any(), any(), any(), any());
        KeyspacesRecordProcessor p = new KeyspacesRecordProcessor(failing(100, calls), FAST, dlq);
        assertTrue(p.apply(batch()));
        assertEquals(4, calls.get());
        verify(dlq, times(2)).send(any(), any(), any(), any(), any());
    }

    @Test
    void stoppingMidRetryReportsNotApplied() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        KeyspacesRecordProcessor p = new KeyspacesRecordProcessor(failing(Integer.MAX_VALUE, calls),
            new DeliveryPolicy(1000, 5, 5, null), null);
        Thread t = new Thread(() -> assertFalse(p.apply(batch())));
        t.start();
        Thread.sleep(30);
        p.leaseLost(null);
        t.join(2000);
        assertFalse(t.isAlive());
    }

    @Test
    void deadLetterBodyNamesRowsNotContents() {
        String body = DeadLetterQueue.body("shard-1", "10", "20", List.of("id=1", "id=2"), new Exception("boom"));
        assertTrue(body.contains("\"kind\":\"keyvector.dead-letter.v1\""));
        assertTrue(body.contains("\"keys\":[\"id=1\",\"id=2\"]"));
        assertTrue(body.contains("\"error\":\"boom\""));
    }

    @Test
    void backoffIsBoundedAndGrows() {
        DeliveryPolicy p = new DeliveryPolicy(10, 100, 1000, "");
        assertFalse(p.hasDeadLetterQueue());
        for (int attempt = 1; attempt < 40; attempt++) {
            long d = p.delayAfter(attempt);
            assertTrue(d >= 0 && d <= 1000, "attempt " + attempt + " -> " + d);
        }
        assertTrue(p.delayAfter(5) >= 500);
        assertThrows(IllegalArgumentException.class, () -> new DeliveryPolicy(0, 1, 1, null));
    }
}
