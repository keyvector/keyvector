package dev.keyvector.connector.embed.local;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import dev.keyvector.connector.query.Reranker;

/**
 * A cross-encoder reranker run inside Keyvector: the query and each candidate text are read
 * together by one model, which scores how well the text answers the query. That is slower than
 * comparing vectors, so it runs only on the few dozen candidates vector search returns, and it
 * is usually more precise.
 *
 * <p>Scores are the model's raw output (a logit), as Sentence Transformers' CrossEncoder and
 * Hugging Face's text-embeddings-inference return them with raw scores on: only their order
 * matters. The model must have one output label, as rerankers do.
 */
public final class LocalReranker implements Reranker {

    private static final Logger logger = LoggerFactory.getLogger(LocalReranker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BATCH = 16;

    private final String description;
    private final HuggingFaceTokenizer tokenizer;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final Set<String> inputs;

    public LocalReranker(ModelFiles files, int maxTokens, int threads) throws Exception {
        Path onnx = prefetch(files);
        int labels = numLabels(files);
        if (labels != 1) {
            throw new IllegalArgumentException(files.describe() + " has " + labels + " output labels; a reranker has 1");
        }
        int limit = maxTokens > 0 ? maxTokens : maxLength(files);
        Map<String, String> opts = new HashMap<>();
        opts.put("maxLength", Integer.toString(limit));
        opts.put("truncation", "true");
        opts.put("padding", "false");
        this.tokenizer = HuggingFaceTokenizer.newInstance(files.required("tokenizer.json"), opts);
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) {
            so.setIntraOpNumThreads(threads);
        }
        this.session = env.createSession(onnx.toString(), so);
        this.inputs = session.getInputNames();
        if (!inputs.contains("input_ids") || !inputs.contains("attention_mask")) {
            throw new IllegalArgumentException("The ONNX model takes " + inputs + "; expected input_ids and attention_mask");
        }
        this.description = "local reranker " + files.describe() + " (" + limit + " tokens)";
        logger.info("Loaded {}", description);
    }

    /** Downloads every file the reranker reads; returns the ONNX file. */
    public static Path prefetch(ModelFiles files) throws Exception {
        for (String f : new String[] {"tokenizer.json", "tokenizer_config.json", "config.json"}) {
            files.optional(f);
        }
        for (String candidate : LocalEmbedder.ONNX_CANDIDATES) {
            Path p = files.optional(candidate);
            if (p != null) {
                return p;
            }
        }
        throw new IllegalArgumentException(files.describe() + " has no ONNX export (looked for onnx/model.onnx and model.onnx)");
    }

    private static int numLabels(ModelFiles files) throws Exception {
        Path cfg = files.optional("config.json");
        if (cfg == null) {
            return 1;
        }
        JsonNode c = MAPPER.readTree(cfg.toFile());
        if (c.has("id2label")) {
            return c.get("id2label").size();
        }
        return c.path("num_labels").asInt(1);
    }

    private static int maxLength(ModelFiles files) throws Exception {
        Path tc = files.optional("tokenizer_config.json");
        if (tc != null) {
            long n = MAPPER.readTree(tc.toFile()).path("model_max_length").asLong(0);
            if (n > 0 && n <= 8192) {
                return (int) n;
            }
        }
        Path cfg = files.optional("config.json");
        if (cfg != null) {
            int n = MAPPER.readTree(cfg.toFile()).path("max_position_embeddings").asInt(0);
            if (n > 0) {
                return n;
            }
        }
        return 512;
    }

    @Override
    public float[] score(String query, List<String> texts) throws Exception {
        float[] out = new float[texts.size()];
        for (int start = 0; start < texts.size(); start += BATCH) {
            List<String> part = texts.subList(start, Math.min(texts.size(), start + BATCH));
            float[] s = scoreBatch(query, part);
            System.arraycopy(s, 0, out, start, s.length);
        }
        return out;
    }

    private float[] scoreBatch(String query, List<String> texts) throws Exception {
        int n = texts.size();
        Encoding[] enc = new Encoding[n];
        int maxLen = 1;
        for (int i = 0; i < n; i++) {
            enc[i] = tokenizer.encode(query, texts.get(i));
            maxLen = Math.max(maxLen, enc[i].getIds().length);
        }
        long[][] ids = new long[n][maxLen];
        long[][] mask = new long[n][maxLen];
        long[][] types = new long[n][maxLen];
        for (int i = 0; i < n; i++) {
            System.arraycopy(enc[i].getIds(), 0, ids[i], 0, enc[i].getIds().length);
            System.arraycopy(enc[i].getAttentionMask(), 0, mask[i], 0, enc[i].getAttentionMask().length);
            System.arraycopy(enc[i].getTypeIds(), 0, types[i], 0, enc[i].getTypeIds().length);
        }
        Map<String, OnnxTensor> feed = new HashMap<>();
        try {
            feed.put("input_ids", OnnxTensor.createTensor(env, ids));
            feed.put("attention_mask", OnnxTensor.createTensor(env, mask));
            if (inputs.contains("token_type_ids")) {
                feed.put("token_type_ids", OnnxTensor.createTensor(env, types));
            }
            try (OrtSession.Result result = session.run(feed)) {
                Object value = result.get(0).getValue();
                float[] out = new float[n];
                if (value instanceof float[][] logits) {
                    for (int i = 0; i < n; i++) {
                        out[i] = logits[i][0];
                    }
                } else if (value instanceof float[] flat) {
                    System.arraycopy(flat, 0, out, 0, n);
                } else {
                    throw new IllegalStateException("Unexpected ONNX output " + value.getClass().getSimpleName());
                }
                return out;
            }
        } finally {
            feed.values().forEach(OnnxTensor::close);
        }
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
            // closing
        }
        tokenizer.close();
    }
}
