package dev.keyvector.connector;

import java.util.List;

import com.typesafe.config.Config;

import dev.keyvector.connector.model.MetadataMapper;
import dev.keyvector.connector.model.Planner;
import dev.keyvector.connector.model.TextTemplate;

import static dev.keyvector.core.config.KeyspacesConfig.getConfigValue;

/**
 * Keyvector's own settings, under {@code keyvector.sync}, {@code keyvector.embedder} and
 * {@code keyvector.sink}. Every value can be overridden by an environment variable named after
 * its path without the {@code keyvector.} prefix: {@code keyvector.embedder.url} is
 * {@code EMBEDDER_URL}.
 */
public record Settings(
    String region,
    String keyspace,
    String table,
    List<String> textColumns,
    String textTemplate,
    int maxTextChars,
    List<String> metadataColumns,
    String storeTextAs,
    List<String> keyColumns,
    Planner.EmptyText onEmptyText,
    String embedderType,
    String embedderModel,
    int dimensions,
    String embedderUrl,
    String apiKeyEnv,
    int batchSize,
    int concurrency,
    int requestsPerMinute,
    boolean normalize,
    String documentPrefix,
    String queryPrefix,
    String sinkType,
    String vectorBucket,
    String vectorIndex) {

    public static Settings from(Config c) {
        String region = getConfigValue(c, "keyvector.stream.region", "", true);
        return new Settings(
            region,
            getConfigValue(c, "keyvector.stream.keyspace-name", "", true),
            getConfigValue(c, "keyvector.stream.table-name", "", true),
            getConfigValue(c, "keyvector.sync.text-columns", List.<String>of(), false),
            getConfigValue(c, "keyvector.sync.text-template", "", false),
            getConfigValue(c, "keyvector.sync.max-text-chars", 8000, false),
            getConfigValue(c, "keyvector.sync.metadata-columns", List.<String>of(), false),
            getConfigValue(c, "keyvector.sync.store-text-as", "", false),
            getConfigValue(c, "keyvector.sync.key-columns", List.<String>of(), false),
            Planner.EmptyText.valueOf(getConfigValue(c, "keyvector.sync.on-empty-text", "delete", false).trim().toUpperCase()),
            getConfigValue(c, "keyvector.embedder.type", "bedrock", false).trim().toLowerCase(),
            getConfigValue(c, "keyvector.embedder.model", "amazon.titan-embed-text-v2:0", false),
            getConfigValue(c, "keyvector.embedder.dimensions", 0, true),
            getConfigValue(c, "keyvector.embedder.url", "", false),
            getConfigValue(c, "keyvector.embedder.api-key-env", "", false),
            getConfigValue(c, "keyvector.embedder.batch-size", 32, false),
            getConfigValue(c, "keyvector.embedder.concurrency", 4, false),
            getConfigValue(c, "keyvector.embedder.requests-per-minute", 0, false),
            getConfigValue(c, "keyvector.embedder.normalize", true, false),
            getConfigValue(c, "keyvector.embedder.document-prefix", "", false),
            getConfigValue(c, "keyvector.embedder.query-prefix", "", false),
            getConfigValue(c, "keyvector.sink.type", "s3vectors", false).trim().toLowerCase(),
            getConfigValue(c, "keyvector.sink.vector-bucket", "", true),
            getConfigValue(c, "keyvector.sink.vector-index", "", true));
    }

    public Planner planner() {
        return new Planner(
            TextTemplate.of(textColumns, textTemplate, maxTextChars),
            new MetadataMapper(metadataColumns, storeTextAs),
            onEmptyText);
    }
}
