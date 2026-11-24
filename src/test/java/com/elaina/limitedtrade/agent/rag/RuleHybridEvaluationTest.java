package com.elaina.limitedtrade.agent.rag;

import com.elaina.limitedtrade.agent.EvaluationIdentity;
import com.elaina.limitedtrade.agent.rag.RagProperties.RetrievalMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "agent.evaluation.hybrid", matches = "true")
class RuleHybridEvaluationTest {

    @Test void evaluateThreeFixedRoutesOnOneHeldOutSuite() throws Exception {
        var json = new ObjectMapper();
        byte[] data = EvaluationIdentity.resource("rag-hybrid-holdout-v1.json");
        var suite = json.readTree(data);
        assertThat(suite).hasSize(12);
        var modes = RetrievalMode.values();
        var output = EvaluationIdentity.report("hybrid-v1-");
        var index = output.resolveSibling(output.getFileName() + ".index.json");
        var paths = Path.of(System.getProperty("agent.evaluation.bge-directory", "tools/models/bge-small-zh-v1.5"));
        Map<RetrievalMode, List<Map<String, Object>>> rows = new EnumMap<>(RetrievalMode.class);
        Map<RetrievalMode, String> fingerprints = new EnumMap<>(RetrievalMode.class);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("complete", false);
        report.put("dataSha256", EvaluationIdentity.digest(data));
        report.put("codeSha256", Map.of("ranker", EvaluationIdentity.source("src/main/java/com/elaina/limitedtrade/agent/rag/RuleRanking.java"),
                "knowledge", EvaluationIdentity.source("src/main/java/com/elaina/limitedtrade/agent/rag/RuleKnowledgeBase.java")));
        report.put("topK", 3);
        report.put("vectorThreshold", 0.55);
        report.put("rows", rows);
        report.put("fingerprints", fingerprints);
        try (var model = new BgeEmbeddingModel(paths)) {
            report.put("embeddingFingerprint", model.fingerprint());
            var engines = new EnumMap<RetrievalMode, RuleKnowledgeBase>(RetrievalMode.class);
            for (var mode : modes) {
                var settings = new RagProperties(true, paths, index, 320, 3, 0.55, false, mode, false, false);
                var knowledge = new RuleKnowledgeBase(mode == RetrievalMode.KEYWORD ? null : model, settings, json);
                engines.put(mode, knowledge);
                fingerprints.put(mode, knowledge.fingerprint());
                rows.put(mode, new ArrayList<>());
                for (int i = 0; i < 3; i++) knowledge.search("待支付订单怎么取消？");
            }
            for (int step = 0; step < suite.size(); step++) {
                var task = suite.get(step);
                for (int offset = 0; offset < modes.length; offset++) {
                    var mode = modes[(step + offset) % modes.length];
                    long started = System.nanoTime();
                    var result = engines.get(mode).search(task.required("query").asText());
                    long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
                    var expected = task.required("evidence");
                    Map<String, Integer> evidenceRanks = new LinkedHashMap<>();
                    expected.fields().forEachRemaining(entry -> {
                        int rank = 0;
                        for (int i = 0; i < result.chunks().size(); i++) {
                            var chunk = result.chunks().get(i);
                            if (chunk.documentId().equals(entry.getKey()) && chunk.text().contains(entry.getValue().asText())) {
                                rank = i + 1;
                                break;
                            }
                        }
                        evidenceRanks.put(entry.getKey(), rank);
                    });
                    var documents = result.chunks().stream().map(RuleChunk::documentId).distinct().toList();
                    rows.get(mode).add(Map.of("task", task, "chunks", result.chunks(), "documents", documents,
                            "status", result.status(), "ranks", evidenceRanks, "elapsedMicros", micros));
                }
            }
            assertThat(rows.values()).allSatisfy(mode -> assertThat(mode).hasSize(12));
            report.put("complete", true);
        } finally {
            Files.deleteIfExists(index);
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            System.out.println("Hybrid evaluation: " + output);
        }
    }
}
