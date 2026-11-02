package dev.keyvector.connector;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.keyvector.connector.model.Change;
import dev.keyvector.connector.model.StreamChanges;
import software.amazon.awssdk.services.keyspacesstreams.model.KeyspacesCell;
import software.amazon.awssdk.services.keyspacesstreams.model.KeyspacesCellValue;
import software.amazon.awssdk.services.keyspacesstreams.model.KeyspacesRow;
import software.amazon.awssdk.services.keyspacesstreams.model.OriginType;
import software.amazon.awssdk.services.keyspacesstreams.model.Record;

class StreamChangesTest {

    private static KeyspacesCell text(String s) {
        return KeyspacesCell.builder().value(KeyspacesCellValue.builder().textT(s).build()).build();
    }

    @Test
    void readsKeysAndImagesAndAddsKeyColumnsToTheRow() {
        Record r = Record.builder()
            .origin(OriginType.USER)
            .partitionKeys(Map.of("user_id", KeyspacesCellValue.builder().intT("7").build()))
            .clusteringKeys(Map.of("doc", KeyspacesCellValue.builder().textT("d1").build()))
            .oldImage(KeyspacesRow.builder().valueCells(Map.of("body", text("old"))).build())
            .newImage(KeyspacesRow.builder().valueCells(Map.of("body", text("new"))).build())
            .sequenceNumber("49")
            .build();
        Change c = StreamChanges.from(r, List.of());
        assertEquals(Map.of("user_id", 7L, "doc", "d1"), c.key());
        assertEquals("old", c.oldRow().get("body"));
        assertEquals("new", c.newRow().get("body"));
        assertEquals("d1", c.newRow().get("doc"));
        assertFalse(c.deleted());
        assertEquals("49", c.sequence());
    }

    @Test
    void aTtlExpiryIsADelete() {
        Record r = Record.builder()
            .origin(OriginType.TTL)
            .partitionKeys(Map.of("id", KeyspacesCellValue.builder().textT("a").build()))
            .oldImage(KeyspacesRow.builder().valueCells(Map.of("body", text("x"))).build())
            .build();
        assertTrue(StreamChanges.from(r, List.of()).deleted());
    }

    @Test
    void fallsBackToConfiguredKeyColumns() {
        Record r = Record.builder()
            .origin(OriginType.USER)
            .newImage(KeyspacesRow.builder().valueCells(Map.of("title", text("Alien"), "body", text("x"))).build())
            .build();
        assertEquals(Map.of("title", "Alien"), StreamChanges.from(r, List.of("title")).key());
    }
}
