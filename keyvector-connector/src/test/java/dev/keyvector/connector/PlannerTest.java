package dev.keyvector.connector;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.keyvector.connector.model.Action;
import dev.keyvector.connector.model.Change;
import dev.keyvector.connector.model.MetadataMapper;
import dev.keyvector.connector.model.Planner;
import dev.keyvector.connector.model.TextTemplate;

class PlannerTest {

    private final Planner planner = new Planner(
        TextTemplate.of(List.of("title", "overview"), null, 8000),
        new MetadataMapper(List.of("genre", "released"), null),
        Planner.EmptyText.DELETE);

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Change change(long id, Map<String, Object> before, Map<String, Object> after) {
        return new Change(Map.of("id", id), before, after, "seq");
    }

    @Test
    void insertEmbeds() {
        Planner.Plan p = planner.plan(List.of(change(1, null, row("id", 1L, "title", "Alien", "overview", "In space", "genre", "sf"))));
        assertEquals(1, p.actions().size());
        Action a = p.actions().get(0);
        assertEquals(Action.Kind.UPSERT, a.kind());
        assertEquals("id=1", a.key());
        assertEquals("Alien\n\nIn space", a.text());
        assertFalse(a.reuseVector());
        assertEquals(Map.of("genre", "sf"), a.metadata());
    }

    @Test
    void anUnrelatedColumnChangeCostsNothing() {
        Map<String, Object> before = row("id", 1L, "title", "Alien", "overview", "In space", "genre", "sf", "views", 1L);
        Map<String, Object> after = row("id", 1L, "title", "Alien", "overview", "In space", "genre", "sf", "views", 2L);
        Planner.Plan p = planner.plan(List.of(change(1, before, after)));
        assertTrue(p.actions().isEmpty());
        assertEquals(1, p.unchanged());
    }

    @Test
    void aMetadataChangeReusesTheStoredVector() {
        Map<String, Object> before = row("id", 1L, "title", "Alien", "genre", "sf");
        Map<String, Object> after = row("id", 1L, "title", "Alien", "genre", "horror");
        Action a = planner.plan(List.of(change(1, before, after))).actions().get(0);
        assertTrue(a.reuseVector());
        assertEquals(Map.of("genre", "horror"), a.metadata());
    }

    @Test
    void aTextChangeReEmbeds() {
        Action a = planner.plan(List.of(change(1, row("title", "Alien"), row("title", "Aliens")))).actions().get(0);
        assertFalse(a.reuseVector());
        assertEquals("Aliens", a.text());
    }

    @Test
    void deleteAndExpiryDeleteTheVector() {
        Action a = planner.plan(List.of(change(1, row("title", "Alien"), null))).actions().get(0);
        assertEquals(Action.Kind.DELETE, a.kind());
        assertEquals("id=1", a.key());
    }

    @Test
    void emptyTextDeletesOrSkipsAsConfigured() {
        Change emptied = change(1, row("title", "Alien"), row("title", "  ", "overview", null));
        assertEquals(Action.Kind.DELETE, planner.plan(List.of(emptied)).actions().get(0).kind());
        Planner skipping = new Planner(TextTemplate.of(List.of("title"), null, 100), new MetadataMapper(List.of(), null), Planner.EmptyText.SKIP);
        Planner.Plan p = skipping.plan(List.of(emptied));
        assertTrue(p.actions().isEmpty());
        assertEquals(1, p.unchanged());
    }

    @Test
    void changesToOneRowCollapseToTheFinalState() {
        Planner.Plan p = planner.plan(List.of(
            change(1, null, row("title", "A")),
            change(2, null, row("title", "B")),
            change(1, row("title", "A"), row("title", "A2")),
            change(2, row("title", "B"), null)));
        assertEquals(2, p.actions().size());
        Action one = p.actions().get(0);
        assertEquals("id=1", one.key());
        assertEquals("A2", one.text());
        // Inserted earlier in the same batch, so there is no stored vector to reuse yet.
        assertFalse(one.reuseVector());
        assertEquals(Action.Kind.DELETE, p.actions().get(1).kind());
    }

    @Test
    void updateThenRevertInOneBatchIsNoWrite() {
        Planner.Plan p = planner.plan(List.of(
            change(1, row("title", "A"), row("title", "B")),
            change(1, row("title", "B"), row("title", "A"))));
        assertTrue(p.actions().isEmpty());
    }

    @Test
    void anUnstorableKeyIsRejectedNotRetriedForever() {
        Planner.Plan p = planner.plan(List.of(
            new Change(Map.of("id", "x".repeat(2000)), null, row("title", "A"), "s1"),
            change(2, null, row("title", "B"))));
        assertEquals(1, p.rejected());
        assertEquals(1, p.actions().size());
    }

    @Test
    void templateRendersAndAnAllEmptyTemplateIsNoText() {
        TextTemplate t = TextTemplate.of(null, "{title}: {overview}", 100);
        assertEquals("Alien: In space", t.render(row("title", "Alien", "overview", "In space")));
        assertEquals("", t.render(row("other", "x")));
        assertEquals("Alien:", t.render(row("title", "Alien")));
        assertThrows(IllegalArgumentException.class, () -> TextTemplate.of(List.of("a"), "{b}", 10));
        assertThrows(IllegalArgumentException.class, () -> TextTemplate.of(null, "no placeholders", 10));
        assertEquals("abc", TextTemplate.of(List.of("t"), null, 3).render(row("t", "abcdef")));
    }

    @Test
    void metadataIsFilterableShapes() {
        Instant ts = Instant.parse("2026-01-01T00:00:00Z");
        MetadataMapper m = new MetadataMapper(List.of("released", "tags", "poster", "missing"), "text");
        Map<String, Object> out = m.map(row("released", ts, "tags", List.of("a", "b"), "poster", new byte[] {1}), "hello");
        assertEquals(ts.toEpochMilli(), out.get("released"));
        assertEquals(List.of("a", "b"), out.get("tags"));
        assertFalse(out.containsKey("poster"));
        assertFalse(out.containsKey("missing"));
        assertEquals("hello", out.get("text"));
        assertThrows(IllegalArgumentException.class, () -> new MetadataMapper(List.of("text"), "text"));
    }
}
