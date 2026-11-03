package dev.keyvector.connector.embed;

import dev.keyvector.connector.Settings;

public final class Embedders {

    private Embedders() {
    }

    /** Titan Text Embeddings V2's on-demand quota: 60 requests a minute, not adjustable. */
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
                throw new IllegalArgumentException("embedder.type must be tei, bedrock or openai; got " + s.embedderType());
        }
    }
}
