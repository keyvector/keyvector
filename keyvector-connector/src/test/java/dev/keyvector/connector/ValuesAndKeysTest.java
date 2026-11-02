package dev.keyvector.connector;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.keyvector.connector.model.RowKey;
import dev.keyvector.connector.model.Values;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.keyspacesstreams.model.KeyspacesCell;
import software.amazon.awssdk.services.keyspacesstreams.model.KeyspacesCellValue;

/**
 * A row arrives from the stream as strings and from the backfill as typed objects. If the two
 * normalize differently, one row gets two vector keys and the index silently holds both.
 */
class ValuesAndKeysTest {

    @Test
    void streamAndDriverAgreeOnEveryKeyType() {
        UUID id = UUID.randomUUID();
        Instant ts = Instant.parse("2026-11-02T10:15:30.123Z");
        Object[][] pairs = {
            {KeyspacesCellValue.builder().intT("42").build(), 42},
            {KeyspacesCellValue.builder().smallintT("7").build(), (short) 7},
            {KeyspacesCellValue.builder().tinyintT("3").build(), (byte) 3},
            {KeyspacesCellValue.builder().bigintT("9000000000").build(), 9_000_000_000L},
            {KeyspacesCellValue.builder().uuidT(id.toString()).build(), id},
            {KeyspacesCellValue.builder().timeuuidT(id.toString()).build(), id},
            {KeyspacesCellValue.builder().textT("hello").build(), "hello"},
            {KeyspacesCellValue.builder().decimalT("1.50").build(), new BigDecimal("1.50")},
            {KeyspacesCellValue.builder().floatT("0.1").build(), 0.1f},
            {KeyspacesCellValue.builder().doubleT("2.5").build(), 2.5d},
            {KeyspacesCellValue.builder().boolT(true).build(), true},
            {KeyspacesCellValue.builder().dateT("2026-11-02").build(), LocalDate.of(2026, 11, 2)},
            {KeyspacesCellValue.builder().timestampT("2026-11-02T10:15:30.123Z").build(), ts},
            {KeyspacesCellValue.builder().timestampT(Long.toString(ts.toEpochMilli())).build(), ts},
            {KeyspacesCellValue.builder().timestampT("2026-11-02 10:15:30.123Z").build(), ts},
            {KeyspacesCellValue.builder().blobT(SdkBytes.fromByteArray(new byte[] {1, 2, 255 - 256})).build(),
                ByteBuffer.wrap(new byte[] {1, 2, -1})},
        };
        for (Object[] p : pairs) {
            Object fromStream = Values.fromStream((KeyspacesCellValue) p[0]);
            Object fromDriver = Values.fromDriver(p[1]);
            assertEquals(Values.keyText(fromStream), Values.keyText(fromDriver), "key text for " + p[0]);
        }
    }

    @Test
    void collectionsNormalizeToLists() {
        KeyspacesCellValue list = KeyspacesCellValue.builder().listT(
            KeyspacesCell.builder().value(KeyspacesCellValue.builder().textT("a").build()).build(),
            KeyspacesCell.builder().value(KeyspacesCellValue.builder().textT("b").build()).build()).build();
        assertEquals(List.of("a", "b"), Values.fromStream(list));
        assertEquals(List.of("a", "b"), Values.fromDriver(new java.util.LinkedHashSet<>(List.of("a", "b"))));
    }

    @Test
    void cqlWireDateIsDaysAroundTwoToTheThirtyOne() {
        assertEquals(LocalDate.of(1970, 1, 1), Values.fromStream(KeyspacesCellValue.builder().dateT(Long.toString(1L << 31)).build()));
    }

    @Test
    void keyIsOrderIndependentAndRoundTrips() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("user_id", 42L);
        a.put("doc", "a&b=c d%");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("doc", "a&b=c d%");
        b.put("user_id", 42L);
        String key = RowKey.encode(a);
        assertEquals(key, RowKey.encode(b));
        assertEquals("doc=a%26b%3Dc%20d%25&user_id=42", key);
        assertEquals(Map.of("doc", "a&b=c d%", "user_id", "42"), RowKey.decode(key));
    }

    @Test
    void keyRefusesWhatCannotBeStored() {
        assertThrows(IllegalArgumentException.class, () -> RowKey.encode(Map.of()));
        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("id", null);
        assertThrows(IllegalArgumentException.class, () -> RowKey.encode(nullValue));
        assertThrows(RowKey.KeyTooLongException.class, () -> RowKey.encode(Map.of("id", "x".repeat(1100))));
        assertThrows(IllegalArgumentException.class, () -> RowKey.decode("not-a-key"));
    }
}
