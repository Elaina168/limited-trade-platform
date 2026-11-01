package com.elaina.limitedtrade.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.elaina.limitedtrade.agent.TradeAgentTools;

public class RuleKnowledgeBase {
    private final SimpleVectorStore store;
    private final RagProperties properties;
    private final List<RuleChunk> chunks;
    private final Map<String, RuleChunk> byId;
    private final String fingerprint;

    public RuleKnowledgeBase(BgeEmbeddingModel model, RagProperties properties, ObjectMapper mapper) throws IOException {
        this.properties = properties;
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath:knowledge/*.md");
        Arrays.sort(resources, Comparator.comparing(resource -> resource.getFilename()));
        if (resources.length == 0) throw new IllegalStateException("未找到公开规则Markdown资料");
        var loaded = new ArrayList<RuleChunk>();
        for (var resource : resources) loaded.addAll(RuleDocuments.split("knowledge/" + resource.getFilename(), resource, properties.chunkChars()));
        chunks = List.copyOf(loaded);
        byId = chunks.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(RuleChunk::id, chunk -> chunk));
        fingerprint = digest("rules-v1:" + properties.chunkChars() + ":" + model.fingerprint() + ":" + mapper.writeValueAsString(chunks));
        store = SimpleVectorStore.builder(model).build();
        var index = properties.indexFile().toAbsolutePath();
        if (Files.exists(index) && !properties.rebuildIndex()) {
            var saved = mapper.readValue(index.toFile(), SavedIndex.class);
            if (!fingerprint.equals(saved.fingerprint()) || saved.vectors() == null || !saved.vectors().isObject()
                    || saved.vectors().size() != chunks.size()) {
                throw new IllegalStateException("规则索引与模型、资料或分块配置不匹配，请显式重建索引");
            }
            // 磁盘索引是输入边界，不能只用数量判断条目是否仍属于当前资料。
            for (var chunk : chunks) {
                var vector = saved.vectors().path(chunk.id());
                if (!chunk.id().equals(vector.path("id").asText())
                        || !(chunk.title() + "\n" + chunk.text()).equals(vector.path("text").asText())
                        || !vector.path("embedding").isArray() || vector.path("embedding").size() != model.dimensions()) {
                    throw new IllegalStateException("规则索引内容不完整，请显式重建索引");
                }
            }
            store.load(new ByteArrayResource(mapper.writeValueAsBytes(saved.vectors())));
        } else {
            var documents = chunks.stream().map(chunk -> Document.builder().id(chunk.id())
                    .text(chunk.title() + "\n" + chunk.text()).metadata(Map.of("documentId", chunk.documentId())).build()).toList();
            store.add(documents);
            Files.createDirectories(index.getParent());
            var temporary = Files.createTempFile(index.getParent(), "rules-", ".json");
            try {
                store.save(temporary.toFile());
                var vectors = mapper.readTree(temporary.toFile());
                if (vectors == null || !vectors.isObject() || vectors.size() != chunks.size()) {
                    throw new IOException("规则向量索引保存不完整");
                }
                mapper.writeValue(temporary.toFile(), new SavedIndex(fingerprint, model.fingerprint(), properties.chunkChars(), vectors));
                Files.move(temporary, index, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    public int chunkCount() { return chunks.size(); }
    public String fingerprint() { return fingerprint; }

    @Tool(name = "search_trade_rules", description = "按自然语言检索公开的平台交易规则，返回原文片段、标题、行号和相似度。仅这些片段可作为规则回答依据；NO_EVIDENCE表示没有可靠命中，必须说明无法确定，不能编造政策。具体订单事实仍需查询本人订单工具。", resultConverter = TradeAgentTools.JsonResultConverter.class)
    public SearchResult search(@ToolParam(description = "明确的规则问题或检索主题，1到400字符，不传用户ID或订单私密数据") String query) {
        if (query == null || query.isBlank() || query.length() > 400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则检索问题不能为空且最多400字符");
        }
        // 查询添加BGE推荐指令；入库文档保持原文，不添加查询指令。
        var hits = store.similaritySearch(SearchRequest.builder().query(BgeEmbeddingModel.QUERY_PREFIX + query)
                .topK(properties.topK()).similarityThreshold(properties.similarityThreshold()).build());
        var result = hits.stream().map(hit -> {
            var original = byId.get(hit.getId());
            return new RuleChunk(original.id(), original.documentId(), original.title(), original.path(),
                    original.startLine(), original.endLine(), original.text(), hit.getScore());
        }).toList();
        return new SearchResult(result.isEmpty() ? "NO_EVIDENCE" : "MATCHED", result);
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public record SearchResult(String status, List<RuleChunk> chunks) { }
    public record SavedIndex(String fingerprint, String model, int chunkChars, JsonNode vectors) { }
}
