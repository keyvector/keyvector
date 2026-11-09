package dev.keyvector.connector.embed;

import java.nio.file.Path;

import dev.keyvector.connector.Settings;
import dev.keyvector.connector.embed.local.LocalEmbedder;
import dev.keyvector.connector.embed.local.ModelFiles;

public final class Embedders {

    private Embedders() {
    }

    /**
     * Titan Text Embeddings V2's on-demand quota on a new account: 60 requests a minute. Accounts
     * with more history get more; raise it with embedder.requests-per-minute.
     */
    static final int TITAN_DEFAULT_RPM = 60;

    public static Embedder create(Settings s) {
        BatchingEmbedder e = build(s);
        int rpm = s.requestsPerMinute();
        if (rpm <= 0 && "bedrock".equals(s.embedderType()) && s.embedderModel().contains("titan-embed")) {
            rpm = TITAN_DEFAULT_RPM;
        }
        return e.withRateLimit(rpm);
    }

    private static BatchingEmbedder build(Settings s) {
        switch (s.embedderType()) {
            case "local":
                return local(s);
            case "tei":
                return new TeiEmbedder(s.embedderUrl(), s.dimensions(), s.batchSize(), s.concurrency(), s.normalize(),
                    s.documentPrefix(), s.queryPrefix());
            case "openai":
                // Only OpenAI's own text-embedding-3 models accept a dimensions parameter.
                return new OpenAiEmbedder(s.embedderUrl(), s.embedderModel(), s.dimensions(),
                    s.embedderModel().startsWith("text-embedding-3"), s.apiKeyEnv(),
                    s.batchSize(), s.concurrency(), s.documentPrefix(), s.queryPrefix());
            case "bedrock":
                return BedrockEmbedder.create(s.embedderModel(), s.dimensions(), s.normalize(), s.region(),
                    s.batchSize(), s.concurrency(), s.documentPrefix(), s.queryPrefix());
            default:
                throw new IllegalArgumentException("embedder.type must be local, tei, bedrock or openai; got " + s.embedderType());
        }
    }

    /** The configured reranker, or null when {@code rerank.model} is not set. */
    public static dev.keyvector.connector.query.Reranker reranker(Settings s) {
        if (!s.reranks()) {
            return null;
        }
        ModelFiles files = s.rerankModelPath() != null && !s.rerankModelPath().isBlank()
            ? ModelFiles.local(Path.of(s.rerankModelPath()))
            : ModelFiles.hub(s.rerankModel(), s.rerankRevision(), ModelFiles.defaultCacheRoot());
        try {
            return new dev.keyvector.connector.embed.local.LocalReranker(files, s.rerankMaxTokens(), s.threads());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not load the reranker " + files.describe() + ": " + e.getMessage(), e);
        }
    }

    private static BatchingEmbedder local(Settings s) {
        ModelFiles files;
        if (s.modelPath() != null && !s.modelPath().isBlank()) {
            files = ModelFiles.local(Path.of(s.modelPath()));
        } else {
            if (s.embedderModel() == null || !s.embedderModel().contains("/") || s.embedderModel().startsWith("amazon.")) {
                throw new IllegalArgumentException("embedder.type = local needs embedder.model set to a Hugging Face repo id,"
                    + " e.g. sentence-transformers/all-MiniLM-L6-v2, or embedder.model-path set to a directory");
            }
            files = ModelFiles.hub(s.embedderModel(), s.modelRevision(), ModelFiles.defaultCacheRoot());
        }
        try {
            return new LocalEmbedder(files, s.dimensions(), s.batchSize(),
                new LocalEmbedder.Options(s.maxTokens(), s.threads(), s.onnxFile(), false),
                s.documentPrefix(), s.queryPrefix());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not load the local model " + files.describe() + ": " + e.getMessage(), e);
        }
    }
}
