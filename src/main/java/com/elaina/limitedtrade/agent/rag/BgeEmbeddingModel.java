package com.elaina.limitedtrade.agent.rag;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

public class BgeEmbeddingModel extends AbstractEmbeddingModel implements AutoCloseable {
    public static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";
    private static final String MODEL_SHA = "69a0b846f4f116b5e6aabf9546ea6754d02264f3211a13a1bd69b31b8040749a";
    private final HuggingFaceTokenizer tokenizer;
    private final OrtSession session;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final String fingerprint;

    public BgeEmbeddingModel(Path directory) throws IOException, OrtException {
        Path model = directory.resolve("model.onnx");
        Path tokenizerFile = directory.resolve("tokenizer.json");
        if (!MODEL_SHA.equals(hash(model))) throw new IOException("BGE模型摘要不匹配，请重新准备模型文件");
        fingerprint = MODEL_SHA + ":" + hash(tokenizerFile) + ":cls-l2:" + QUERY_PREFIX;
        // 只影响当前Java进程，原生分词器缓存保持在项目的忽略目录。
        System.setProperty("DJL_CACHE_DIR", directory.toAbsolutePath().getParent().resolve("djl-cache").toString());
        tokenizer = HuggingFaceTokenizer.builder().optTokenizerPath(tokenizerFile)
                .optMaxLength(512).optTruncation(true).optPadding(false).build();
        try (var options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            options.setInterOpNumThreads(1);
            session = environment.createSession(model.toString(), options);
        } catch (OrtException error) {
            tokenizer.close();
            throw error;
        }
    }

    public String fingerprint() { return fingerprint; }
    @Override public int dimensions() { return 512; }
    @Override public float[] embed(Document document) { return embed(document.getText()); }

    @Override public EmbeddingResponse call(EmbeddingRequest request) {
        var vectors = new ArrayList<Embedding>();
        for (String text : request.getInstructions()) {
            var encoded = tokenizer.encode(text);
            try (var ids = OnnxTensor.createTensor(environment, new long[][] {encoded.getIds()});
                 var mask = OnnxTensor.createTensor(environment, new long[][] {encoded.getAttentionMask()});
                 var types = OnnxTensor.createTensor(environment, new long[][] {encoded.getTypeIds()})) {
                var inputs = new LinkedHashMap<String, OnnxTensor>();
                inputs.put("input_ids", ids);
                inputs.put("attention_mask", mask);
                if (session.getInputNames().contains("token_type_ids")) inputs.put("token_type_ids", types);
                try (var output = session.run(inputs)) {
                    // BGE取首个CLS token，不使用Spring AI1.1.x Transformers的平均池化。
                    float[] vector = ((float[][][]) output.get("last_hidden_state").orElseThrow().getValue())[0][0].clone();
                    double norm = 0;
                    for (float value : vector) norm += value * value;
                    norm = Math.sqrt(norm);
                    for (int i = 0; i < vector.length; i++) vector[i] /= (float) norm;
                    vectors.add(new Embedding(vector, vectors.size()));
                }
            } catch (OrtException error) {
                throw new IllegalStateException("本地BGE推理失败", error);
            }
        }
        return new EmbeddingResponse(vectors);
    }

    static String hash(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int count; (count = stream.read(buffer)) != -1;) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    @Override public void close() throws OrtException {
        try { session.close(); }
        finally { tokenizer.close(); }
    }
}
