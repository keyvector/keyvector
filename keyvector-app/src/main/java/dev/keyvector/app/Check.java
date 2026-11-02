package dev.keyvector.app;

import com.datastax.oss.driver.api.core.CqlSession;

import dev.keyvector.connector.Settings;
import dev.keyvector.connector.backfill.KeyspacesRows;
import dev.keyvector.connector.embed.Embedder;
import dev.keyvector.connector.embed.Embedders;
import dev.keyvector.connector.sink.Sinks;
import dev.keyvector.connector.sink.VectorSink;
import dev.keyvector.core.config.KeyspacesConfig;
import dev.keyvector.core.helpers.StreamLocator;

/**
 * {@code keyvector check}: every way the configuration can be wrong, found before anything
 * runs. Reads only - embeds one sentence, reads the index and the table's schema.
 */
final class Check {

    private Check() {
    }

    static boolean run(KeyspacesConfig config, Settings s) {
        boolean ok = true;
        ok &= step("text and metadata settings", () -> {
            s.planner();
            return s.textColumns().isEmpty() ? "template " + s.textTemplate() : "columns " + s.textColumns();
        });
        ok &= step("CDC stream", () -> {
            var stream = StreamLocator.find(config.getOrCreateKeyspacesStreamsClient(), s.keyspace(), s.table(), config.getStreamName())
                .orElseThrow(() -> new IllegalStateException("no enabled stream on " + s.keyspace() + "." + s.table()
                    + "; enable it with ALTER TABLE ... WITH cdc = true"));
            StreamLocator.requireNewImages(stream.streamViewType());
            return stream.streamArn() + " (" + stream.streamViewType() + ")";
        });
        ok &= step("table", () -> {
            try (CqlSession session = config.getOrCreateCqlSession()) {
                return "primary key " + new KeyspacesRows(session, s.keyspace(), s.table()).primaryKeyColumns();
            }
        });
        ok &= step("embedder", () -> {
            try (Embedder e = Embedders.create(s)) {
                int n = e.embedDocuments(java.util.List.of("Keyvector configuration check")).get(0).length;
                return e.describe() + ", " + n + " dimensions";
            }
        });
        ok &= step("vector index", () -> {
            try (VectorSink sink = Sinks.create(s)) {
                int d = sink.dimensions();
                if (d != s.dimensions()) {
                    throw new IllegalStateException(sink.describe() + " has " + d + " dimensions, embedder.dimensions is " + s.dimensions());
                }
                return sink.describe() + ", " + d + " dimensions";
            }
        });
        System.out.println(ok ? "\nAll checks passed." : "\nSome checks failed.");
        return ok;
    }

    interface Step {
        String run() throws Exception;
    }

    private static boolean step(String name, Step step) {
        try {
            System.out.println("ok    " + name + ": " + step.run());
            return true;
        } catch (Exception e) {
            System.out.println("FAIL  " + name + ": " + e.getMessage());
            return false;
        }
    }
}
