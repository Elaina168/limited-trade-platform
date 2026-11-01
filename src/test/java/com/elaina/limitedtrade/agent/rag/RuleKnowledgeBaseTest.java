package com.elaina.limitedtrade.agent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.*;

class RuleKnowledgeBaseTest {
    Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void createDirectory() throws Exception {
        directory = Files.createTempDirectory(Path.of("tools"), "rag-index-test-");
    }

    @AfterEach void cleanDirectory() throws Exception {
        Files.deleteIfExists(directory.resolve("rules.json"));
        Files.delete(directory);
    }

    private RagProperties settings(int chars) {
        return new RagProperties(true, Path.of("tools/models/bge-small-zh-v1.5"), directory.resolve("rules.json"),
                chars, 3, 0.7, false);
    }

    @Test void actualIndexReloadPreservesResultsWithoutDuplicateChunks() throws Exception {
        try (var model = new BgeEmbeddingModel(settings(320).modelDirectory())) {
            var first = new RuleKnowledgeBase(model, settings(320), mapper);
            var result = first.search("未付款订单多久会过期，过期还能付款吗？");
            assertThat(result.chunks()).isNotEmpty();
            assertThat(result.chunks()).anySatisfy(chunk -> assertThat(chunk.documentId()).isIn("payment", "timeout"));
            var second = new RuleKnowledgeBase(model, settings(320), mapper);
            assertThat(second.chunkCount()).isEqualTo(first.chunkCount());
            assertThat(second.search("未付款订单多久会过期，过期还能付款吗？").chunks())
                    .extracting(RuleChunk::id).containsExactlyElementsOf(result.chunks().stream().map(RuleChunk::id).toList());
            assertThatThrownBy(() -> new RuleKnowledgeBase(model, settings(321), mapper))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("重建");
        }
    }

    @Test void emptyOrOversizedSearchIsRejected() throws Exception {
        try (var model = new BgeEmbeddingModel(settings(320).modelDirectory())) {
            var knowledge = new RuleKnowledgeBase(model, settings(320), mapper);
            assertThatThrownBy(() -> knowledge.search(" ")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThatThrownBy(() -> knowledge.search("问".repeat(401))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
    }

    @Test void corruptIndexIsRejectedAndExplicitRebuildReplacesIt() throws Exception {
        try (var model = new BgeEmbeddingModel(settings(320).modelDirectory())) {
            var original = new RuleKnowledgeBase(model, settings(320), mapper);
            var file = directory.resolve("rules.json");
            var json = mapper.readTree(file.toFile());
            var vectors = (com.fasterxml.jackson.databind.node.ObjectNode) json.path("vectors");
            String id = vectors.fieldNames().next();
            vectors.set("wrong-id", vectors.remove(id));
            mapper.writeValue(file.toFile(), json);
            assertThatThrownBy(() -> new RuleKnowledgeBase(model, settings(320), mapper))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("重建");
            var rebuild = new RagProperties(true, settings(320).modelDirectory(), file, 320, 3, 0.55, true);
            var replaced = new RuleKnowledgeBase(model, rebuild, mapper);
            assertThat(replaced.chunkCount()).isEqualTo(original.chunkCount());
            assertThat(new RuleKnowledgeBase(model, settings(320), mapper).chunkCount()).isEqualTo(original.chunkCount());
        }
    }
}
