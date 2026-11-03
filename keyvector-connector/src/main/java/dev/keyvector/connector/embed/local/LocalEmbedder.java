package dev.keyvector.connector.embed.local;

import java.nio.file.Path;
import java.util.ArrayList;
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
import dev.keyvector.connector.embed.BatchingEmbedder;

/**
 * Runs a Sentence-Transformers model inside Keyvector, with no model server: Hugging Face's
 * tokenizer prepares the text, ONNX Runtime runs the model, and {@link Pooling} turns the
 * output into one vector, exactly as the model's own configuration says.
 *
 * <p>Everything that varies between models is read from the model's files, never assumed:
 * the pooling mode ({@code 1_Pooling/config.json}), whether to normalize
 * ({@code modules.json}), the token limit ({@code sentence_bert_config.json}), and which
 * inputs the network takes (the ONNX graph itself).
 */
public final class LocalEmbedder extends BatchingEmbedder {

    private static final Logger logger = LoggerFactory.getLogger(LocalEmbedder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String[] ONNX_CANDIDATES = {"onnx/model.onnx", "model.onnx"};

    private final String description;
    private final HuggingFaceTokenizer tokenizer;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final Set<String> inputs;
    private final Pooling pooling;

    public record Options(int maxTokens, int threads, String onnxFile, boolean normalizeOverride) {
    }

    public LocalEmbedder(ModelFiles files, int dimensions, int batchSize, Options options,
                         String documentPrefix, String queryPrefix) throws Exception {
        super(batchSize, 1, dimensions, documentPrefix, queryPrefix);

        Path onnx = null;
        if (options.onnxFile() != null && !options.onnxFile().isBlank()) {
            onnx = files.required(options.onnxFile());
        } else {
            for (String candidate : ONNX_CANDIDATES) {
                onnx = files.optional(candidate);
                if (onnx != null) {
                    break;
                }
            }
            if (onnx == null) {
                throw new IllegalArgumentException(files.describe() + " has no ONNX export (looked for onnx/model.onnx and model.onnx);"
                    + " export one with `optimum-cli export onnx` or set embedder.onnx-file");
            }
        }

        Pooling.Mode mode = Pooling.Mode.MEAN;
        Path poolingConfig = files.optional("1_Pooling/config.json");
        if (poolingConfig != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = MAPPER.readValue(poolingConfig.toFile(), Map.class);
            mode = Pooling.modeFrom(cfg);
            Object dim = cfg.get("word_embedding_dimension");
            if (dim instanceof Number && ((Number) dim).intValue() != dimensions) {
                throw new IllegalArgumentException(files.describe() + " produces " + dim + " dimensions but embedder.dimensions is " + dimensions);
            }
        } else {
            logger.warn("{} has no 1_Pooling/config.json; using mean pooling", files.describe());
        }
        boolean normalize = options.normalizeOverride() || hasNormalizeModule(files);
        this.pooling = new Pooling(mode, normalize);

        int maxTokens = options.maxTokens() > 0 ? options.maxTokens() : maxSeqLength(files);

        Map<String, String> tokenizerOptions = new HashMap<>();
        tokenizerOptions.put("maxLength", Integer.toString(maxTokens));
        tokenizerOptions.put("truncation", "true");
        tokenizerOptions.put("padding", "false");
        this.tokenizer = HuggingFaceTokenizer.newInstance(files.required("tokenizer.json"), tokenizerOptions);

        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (options.threads() > 0) {
            so.setIntraOpNumThreads(options.threads());
        }
        this.session = env.createSession(onnx.toString(), so);
        this.inputs = session.getInputNames();
        if (!inputs.contains("input_ids") || !inputs.contains("attention_mask")) {
            throw new IllegalArgumentException("The ONNX model takes " + inputs + "; expected input_ids and attention_mask");
        }
        this.description = "local " + files.describe() + " (" + mode.name().toLowerCase() + " pooling"
            + (normalize ? ", normalized" : "") + ", " + maxTokens + " tokens)";
        logger.info("Loaded {}", description);
    }

    /**
     * Downloads every file the embedder reads, so an image can carry the model and start with
     * no network access. Returns the ONNX file used.
     */
    public static Path prefetch(ModelFiles files, String onnxFile) throws Exception {
        for (String f : new String[] {"tokenizer.json", "1_Pooling/config.json", "modules.json", "sentence_bert_config.json", "config.json"}) {
            files.optional(f);
        }
        if (onnxFile != null && !onnxFile.isBlank()) {
            return files.required(onnxFile);
        }
        for (String candidate : ONNX_CANDIDATES) {
            Path p = files.optional(candidate);
            if (p != null) {
                return p;
            }
        }
        throw new IllegalArgumentException(files.describe() + " has no ONNX export");
    }

    private static boolean hasNormalizeModule(ModelFiles files) throws Exception {
        Path modules = files.optional("modules.json");
        if (modules == null) {
            return false;
        }
        for (JsonNode m : MAPPER.readTree(modules.toFile())) {
            if (m.path("type").asText().endsWith(".Normalize")) {
                return true;
            }
        }
        return false;
    }

    private static int maxSeqLength(ModelFiles files) throws Exception {
        Path cfg = files.optional("sentence_bert_config.json");
        if (cfg != null) {
            int n = MAPPER.readTree(cfg.toFile()).path("max_seq_length").asInt(0);
            if (n > 0) {
                return n;
            }
        }
        return 512;
    }

    @Override
    protected List<float[]> request(List<String> texts, boolean query) throws Exception {
        int n = texts.size();
        Encoding[] enc = new Encoding[n];
        int maxLen = 1;
        for (int i = 0; i < n; i++) {
            enc[i] = tokenizer.encode(texts.get(i));
            maxLen = Math.max(maxLen, enc[i].getIds().length);
        }
        long[][] ids = new long[n][maxLen];
        long[][] mask = new long[n][maxLen];
        long[][] types = new long[n][maxLen];
        for (int i = 0; i < n; i++) {
            long[] id = enc[i].getIds();
            long[] am = enc[i].getAttentionMask();
            long[] tt = enc[i].getTypeIds();
            System.arraycopy(id, 0, ids[i], 0, id.length);
            System.arraycopy(am, 0, mask[i], 0, am.length);
            System.arraycopy(tt, 0, types[i], 0, tt.length);
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
                List<float[]> out = new ArrayList<>(n);
                if (value instanceof float[][][]) {
                    float[][][] hidden = (float[][][]) value;
                    for (int i = 0; i < n; i++) {
                        out.add(pooling.pool(hidden[i], mask[i]));
                    }
                } else if (value instanceof float[][]) {
                    // Some exports pool inside the graph and return one vector per text.
                    for (float[] v : (float[][]) value) {
                        out.add(pooling.normalize() ? Pooling.l2Normalize(v) : v);
                    }
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
        super.close();
        try {
            session.close();
        } catch (Exception ignored) {
            // closing
        }
        tokenizer.close();
    }
}
