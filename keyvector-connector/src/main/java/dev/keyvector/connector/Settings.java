package dev.keyvector.connector;

import java.util.List;

import com.typesafe.config.Config;

import dev.keyvector.connector.model.Chunker;
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
    String modelPath,
    String modelRevision,
    String onnxFile,
    int maxTokens,
    int threads,
    String sinkType,
    String vectorBucket,
    String vectorIndex,
    int chunkWords,
    int chunkOverlapWords,
    int maxChunks,
    String rerankModel,
    String rerankModelPath,
    String rerankRevision,
    int rerankCandidates,
    int rerankMaxTokens) {

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
            getConfigValue(c, "keyvector.embedder.model-path", "", false),
            getConfigValue(c, "keyvector.embedder.model-revision", "main", false),
            getConfigValue(c, "keyvector.embedder.onnx-file", "", false),
            getConfigValue(c, "keyvector.embedder.max-tokens", 0, false),
            getConfigValue(c, "keyvector.embedder.threads", 0, false),
            getConfigValue(c, "keyvector.sink.type", "s3vectors", false).trim().toLowerCase(),
            getConfigValue(c, "keyvector.sink.vector-bucket", "", true),
            getConfigValue(c, "keyvector.sink.vector-index", "", true),
            getConfigValue(c, "keyvector.sync.chunk-words", 0, false),
            getConfigValue(c, "keyvector.sync.chunk-overlap-words", -1, false),
            getConfigValue(c, "keyvector.sync.max-chunks", 100, false),
            getConfigValue(c, "keyvector.rerank.model", "", false),
            getConfigValue(c, "keyvector.rerank.model-path", "", false),
            getConfigValue(c, "keyvector.rerank.model-revision", "main", false),
            getConfigValue(c, "keyvector.rerank.candidates", 50, false),
            getConfigValue(c, "keyvector.rerank.max-tokens", 0, false));
    }

    public Planner planner() {
        Chunker chunker = chunker();
        MetadataMapper metadata = new MetadataMapper(metadataColumns, storeTextAs);
        if (chunker != null) {
            for (String reserved : new String[] {SyncEngine.CHUNK, SyncEngine.CHUNKS}) {
                if (metadata.columns().contains(reserved) || reserved.equals(metadata.textKey())) {
                    throw new IllegalArgumentException(reserved + " is used by Keyvector for chunk numbers; rename the metadata key");
                }
            }
        }
        return new Planner(
            TextTemplate.of(textColumns, textTemplate, maxTextChars),
            metadata,
            onEmptyText,
            chunker == null ? 0 : chunker.keySuffixLength());
    }

    /** Null when chunking is off ({@code sync.chunk-words} is 0). */
    public Chunker chunker() {
        if (chunkWords <= 0) {
            return null;
        }
        int overlap = chunkOverlapWords >= 0 ? chunkOverlapWords : chunkWords / 5;
        return new Chunker(chunkWords, overlap, maxChunks);
    }

    public boolean reranks() {
        return !rerankModel.isBlank() || !rerankModelPath.isBlank();
    }
}
