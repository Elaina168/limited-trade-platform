package com.elaina.limitedtrade.agent.rag;

import com.elaina.limitedtrade.agent.EvaluationIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "agent.evaluation.retrieval-experiments", matches = "true")
class RuleRetrievalExperimentsEvaluationTest {

    @Test void compareSameCandidatePoolAndPairedQueries() throws Exception {
        var json = new ObjectMapper();
        var output = EvaluationIdentity.report("retrieval-experiments-v1-");
        var index = output.resolveSibling(output.getFileName() + ".index.json");
        var modelPath = Path.of(System.getProperty("agent.evaluation.bge-directory", "tools/models/bge-small-zh-v1.5"));
        var fixedBytes = EvaluationIdentity.resource("rag-hybrid-holdout-v1.json");
        var rewriteBytes = EvaluationIdentity.resource("rag-rewrite-holdout-v1.json");
        var fixedCases = json.readTree(fixedBytes);
        var rewriteCases = json.readTree(rewriteBytes);
        assertThat(fixedCases).hasSize(12);
        assertThat(rewriteCases).hasSize(8);
        var report = new LinkedHashMap<String, Object>();
        var rerankRows = new ArrayList<Map<String, Object>>();
        var rewriteRows = new ArrayList<Map<String, Object>>();
        report.put("complete", false);
        report.put("rerank", rerankRows);
        report.put("rewrite", rewriteRows);
        report.put("extraModelCalls", 0);
        report.put("datasets", Map.of("rerank", EvaluationIdentity.digest(fixedBytes),
                "rewrite", EvaluationIdentity.digest(rewriteBytes)));
        report.put("sources", Map.of("rerank", EvaluationIdentity.source("src/main/java/com/elaina/limitedtrade/agent/rag/RuleReranker.java"),
                "rewrite", EvaluationIdentity.source("src/main/java/com/elaina/limitedtrade/agent/rag/RuleQueryRewrite.java")));
        report.put("configuration", Map.of("mode", "VECTOR", "candidateDepth", 5, "topK", 3, "threshold", 0.55));
        try (var model = new BgeEmbeddingModel(modelPath)) {
            var settings = new RagProperties(true, modelPath, index, 320, 3, 0.55, false,
                    RagProperties.RetrievalMode.VECTOR, false, false);
            var vector = new RuleKnowledgeBase(model, settings, json);
            var experimentalSettings = new RagProperties(true, modelPath, index, 320, 3, 0.55, false,
                    RagProperties.RetrievalMode.VECTOR, true, true);
            var experimental = new RuleKnowledgeBase(model, experimentalSettings, json);
            report.put("dimensions", model.dimensions());
            report.put("indexBytes", Files.size(index));
            report.put("chunkCount", vector.chunkCount());
            report.put("corpusFingerprint", vector.fingerprint());
            report.put("modelFingerprint", model.fingerprint());
            for (int i = 0; i < 4; i++) {
                vector.search("取消订单会退库存吗？");
                RuleReranker.rank("取消订单会退库存吗？", vector.candidates("取消订单会退库存吗？", 5), 3);
            }
            for (var item : fixedCases) {
                String query = item.required("query").asText();
                long started = System.nanoTime();
                var pool = vector.candidates(query, 5);
                long recallMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
                started = System.nanoTime();
                var ranked = RuleReranker.rank(query, pool, 3);
                long rerankMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
                var baseline = pool.stream().limit(3).toList();
                assertThat(experimental.search(query).chunks()).containsExactlyElementsOf(ranked);
                rerankRows.add(Map.of("item", item, "candidates", pool, "base", evaluate(item, baseline),
                        "reranked", evaluate(item, ranked), "recallMicros", recallMicros, "rerankMicros", rerankMicros));
            }
            for (var item : rewriteCases) {
                String query = item.required("query").asText();
                long started = System.nanoTime();
                String expanded = RuleQueryRewrite.resolve(query, item.required("question").asText(),
                        item.required("previous").asText());
                long rewriteMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
                var expected = item.required("term");
                String expectedQuery = expected.isNull() ? query : expected.asText() + " " + query;
                assertThat(expanded).as(item.required("id").asText()).isEqualTo(expectedQuery);
                started = System.nanoTime();
                var baseline = vector.search(query);
                long baseMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
                started = System.nanoTime();
                var result = vector.search(expanded);
                long afterMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
                rewriteRows.add(Map.of("item", item, "expanded", expanded, "base", evaluate(item, baseline.chunks()),
                        "rewritten", evaluate(item, result.chunks()), "rewriteMicros", rewriteMicros,
                        "baseMicros", baseMicros, "afterMicros", afterMicros));
            }
            report.put("complete", true);
        } finally {
            Files.deleteIfExists(index);
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            System.out.println("Retrieval experiments: " + output);
        }
    }

    private Map<String, Object> evaluate(JsonNode item, List<RuleChunk> chunks) {
        var matched = new LinkedHashMap<String, Integer>();
        item.required("evidence").fields().forEachRemaining(expected -> {
            int rank = 0;
            for (var chunk : chunks) {
                rank++;
                if (chunk.documentId().equals(expected.getKey()) && chunk.text().contains(expected.getValue().asText())) {
                    matched.put(expected.getKey(), rank);
                    return;
                }
            }
            matched.put(expected.getKey(), 0);
        });
        return Map.of("chunks", chunks, "ranks", matched, "noEvidence", chunks.isEmpty());
    }
}
