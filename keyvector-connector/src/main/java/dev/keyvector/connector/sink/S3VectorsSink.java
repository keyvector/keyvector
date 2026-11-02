package dev.keyvector.connector.sink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.DeleteVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.GetIndexRequest;
import software.amazon.awssdk.services.s3vectors.model.GetOutputVector;
import software.amazon.awssdk.services.s3vectors.model.GetVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.PutInputVector;
import software.amazon.awssdk.services.s3vectors.model.PutVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.QueryOutputVector;
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsRequest;
import software.amazon.awssdk.services.s3vectors.model.VectorData;

/**
 * Amazon S3 Vectors. Requests are split at the service's limits: 500 vectors per put or
 * delete, 100 per get. Put overwrites by key and delete ignores missing keys, so both are
 * idempotent as {@link VectorSink} requires.
 */
public final class S3VectorsSink implements VectorSink {

    static final int MAX_PUT = 500;
    static final int MAX_DELETE = 500;
    static final int MAX_GET = 100;
    static final int MAX_TOP_K = 100;

    private final String bucket;
    private final String index;
    private final S3VectorsClient client;

    public S3VectorsSink(String bucket, String index, String region) {
        this.bucket = bucket;
        this.index = index;
        this.client = S3VectorsClient.builder().region(Region.of(region)).build();
    }

    @Override
    public void put(List<VectorDoc> docs) {
        for (List<VectorDoc> part : partition(docs, MAX_PUT)) {
            List<PutInputVector> vectors = new ArrayList<>(part.size());
            for (VectorDoc d : part) {
                vectors.add(PutInputVector.builder()
                    .key(d.key())
                    .data(VectorData.builder().float32(boxed(d.vector())).build())
                    .metadata(Documents.of(d.metadata() == null ? Map.of() : d.metadata()))
                    .build());
            }
            client.putVectors(PutVectorsRequest.builder().vectorBucketName(bucket).indexName(index).vectors(vectors).build());
        }
    }

    @Override
    public void delete(List<String> keys) {
        for (List<String> part : partition(keys, MAX_DELETE)) {
            client.deleteVectors(DeleteVectorsRequest.builder().vectorBucketName(bucket).indexName(index).keys(part).build());
        }
    }

    @Override
    public Map<String, float[]> vectors(List<String> keys) {
        Map<String, float[]> out = new HashMap<>();
        for (List<String> part : partition(keys, MAX_GET)) {
            for (GetOutputVector v : client.getVectors(GetVectorsRequest.builder()
                    .vectorBucketName(bucket).indexName(index).keys(part).returnData(true).returnMetadata(false).build()).vectors()) {
                if (v.data() != null && v.data().float32() != null) {
                    out.put(v.key(), unboxed(v.data().float32()));
                }
            }
        }
        return out;
    }

    @Override
    public List<Hit> query(float[] vector, int topK, Map<String, Object> filter) {
        QueryVectorsRequest.Builder b = QueryVectorsRequest.builder()
            .vectorBucketName(bucket)
            .indexName(index)
            .topK(Math.max(1, Math.min(topK, MAX_TOP_K)))
            .queryVector(VectorData.builder().float32(boxed(vector)).build())
            .returnDistance(true)
            .returnMetadata(true);
        if (filter != null && !filter.isEmpty()) {
            b.filter(Documents.of(filter));
        }
        List<Hit> out = new ArrayList<>();
        for (QueryOutputVector v : client.queryVectors(b.build()).vectors()) {
            out.add(new Hit(v.key(), v.distance(), Documents.toMap(v.metadata())));
        }
        return out;
    }

    @Override
    public int dimensions() {
        return client.getIndex(GetIndexRequest.builder().vectorBucketName(bucket).indexName(index).build()).index().dimension();
    }

    @Override
    public String describe() {
        return "s3vectors " + bucket + "/" + index;
    }

    @Override
    public void close() {
        client.close();
    }

    static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            out.add(items.subList(i, Math.min(items.size(), i + size)));
        }
        return out;
    }

    private static List<Float> boxed(float[] v) {
        List<Float> out = new ArrayList<>(v.length);
        for (float f : v) {
            out.add(f);
        }
        return out;
    }

    private static float[] unboxed(List<Float> v) {
        float[] out = new float[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.get(i);
        }
        return out;
    }
}
