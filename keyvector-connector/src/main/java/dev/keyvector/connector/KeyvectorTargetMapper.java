package dev.keyvector.connector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

import dev.keyvector.connector.embed.Embedder;
import dev.keyvector.connector.embed.Embedders;
import dev.keyvector.connector.model.Change;
import dev.keyvector.connector.model.RowKey;
import dev.keyvector.connector.model.StreamChanges;
import dev.keyvector.connector.sink.Sinks;
import dev.keyvector.connector.sink.VectorSink;
import dev.keyvector.core.connector.AbstractTargetMapper;
import dev.keyvector.core.helpers.StreamLocator;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.keyspacesstreams.KeyspacesStreamsClient;
import software.amazon.awssdk.services.keyspacesstreams.model.StreamViewType;
import software.amazon.keyspaces.streamsadapter.adapter.KeyspacesStreamsClientRecord;

/**
 * The stream side: Keyspaces CDC records in, vector index writes out. Configure it with
 * {@code keyvector.stream.connector.target-mapper = "dev.keyvector.connector.KeyvectorTargetMapper"}.
 */
public class KeyvectorTargetMapper extends AbstractTargetMapper {

    private static final Logger logger = LoggerFactory.getLogger(KeyvectorTargetMapper.class);

    private final Settings settings;
    private volatile SyncEngine engine;

    public KeyvectorTargetMapper(Config config) {
        super(config);
        this.settings = Settings.from(config);
        // Fail at startup, not on the first record, if the text or metadata settings are wrong.
        settings.planner();
    }

    @Override
    public synchronized void initialize() {
        super.initialize();
        if (engine != null) {
            return;
        }
        checkStream();
        Embedder embedder = Embedders.create(settings);
        VectorSink sink = Sinks.create(settings);
        try {
            int indexDimensions = sink.dimensions();
            if (indexDimensions != settings.dimensions()) {
                throw new IllegalStateException(sink.describe() + " has " + indexDimensions
                    + " dimensions but embedder.dimensions is " + settings.dimensions());
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("Could not read the vector index's dimensions ({}); continuing", e.toString());
        }
        logger.info("Keyvector syncing {}.{} with {} into {}", settings.keyspace(), settings.table(), embedder.describe(), sink.describe());
        engine = new SyncEngine(settings.planner(), embedder, sink, settings.chunker());
    }

    private void checkStream() {
        try (KeyspacesStreamsClient client = KeyspacesStreamsClient.builder().region(Region.of(settings.region())).build()) {
            StreamLocator.find(client, settings.keyspace(), settings.table(), null).ifPresent(stream -> {
                StreamLocator.requireNewImages(stream.streamViewType());
                if (stream.streamViewType() == StreamViewType.NEW_IMAGE) {
                    logger.warn("The stream records new images only, so every update is re-embedded;"
                        + " NEW_AND_OLD_IMAGES lets Keyvector skip updates that leave the text unchanged");
                }
            });
        }
    }

    @Override
    public void handleRecords(List<KeyspacesStreamsClientRecord> records) throws Exception {
        if (records == null || records.isEmpty()) {
            return;
        }
        if (engine == null) {
            initialize();
        }
        List<Change> changes = new ArrayList<>(records.size());
        for (KeyspacesStreamsClientRecord r : records) {
            changes.add(StreamChanges.from(r.getRecord(), settings.keyColumns()));
        }
        engine.apply(changes);
    }

    @Override
    public List<String> deadLetterKeys(List<KeyspacesStreamsClientRecord> records) {
        Set<String> keys = new LinkedHashSet<>();
        for (KeyspacesStreamsClientRecord r : records) {
            try {
                keys.add(RowKey.encode(StreamChanges.from(r.getRecord(), settings.keyColumns()).key()));
            } catch (Exception e) {
                logger.warn("Could not name a dead-lettered row: {}", e.toString());
            }
        }
        return new ArrayList<>(keys);
    }
}
