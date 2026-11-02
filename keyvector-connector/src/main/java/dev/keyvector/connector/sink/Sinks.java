package dev.keyvector.connector.sink;

import dev.keyvector.connector.Settings;

public final class Sinks {

    private Sinks() {
    }

    public static VectorSink create(Settings s) {
        if (!"s3vectors".equals(s.sinkType())) {
            throw new IllegalArgumentException("sink.type must be s3vectors (OpenSearch and DynamoDB are on the roadmap); got " + s.sinkType());
        }
        return new S3VectorsSink(s.vectorBucket(), s.vectorIndex(), s.region());
    }
}
