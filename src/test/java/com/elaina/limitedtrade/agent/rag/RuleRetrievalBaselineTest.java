package com.elaina.limitedtrade.agent.rag;

import com.elaina.limitedtrade.agent.EvaluationIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "agent.evaluation.retrieval", matches = "true")
class RuleRetrievalBaselineTest {
    record Configuration(String id, int topK, double threshold) { }
    private static final List<Configuration> CONFIGURATIONS = List.of(
            new Configuration("top3-055", 3, 0.55),
            new Configuration("top1-055", 1, 0.55),
            new Configuration("top3-060", 3, 0.60));

    @Test void reportsAllFixedProfilesAndSplits() throws Exception {
        var json = new ObjectMapper();
        var legacy = EvaluationIdentity.resource("rag-evaluation.json");
        var additions = EvaluationIdentity.resource("rag-evaluation-v2-additions.json");
        var suite = new ArrayList<JsonNode>();
        json.readTree(legacy).forEach(suite::add);
        json.readTree(additions).forEach(suite::add);
        assertThat(suite).hasSize(30);
        assertThat(suite).extracting(task -> task.required("id").asText()).doesNotHaveDuplicates();

        var output = EvaluationIdentity.report("retrieval-v2-");
        var runs = new ArrayList<Map<String, Object>>();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("version", 2);
        metadata.put("generatedAt", Instant.now().toString());
        metadata.put("datasets", Map.of("legacy", EvaluationIdentity.digest(legacy),
                "additions", EvaluationIdentity.digest(additions)));
        metadata.put("java", System.getProperty("java.version"));
        metadata.put("source", Map.of("retrieval", EvaluationIdentity.source(
                "src/main/java/com/elaina/limitedtrade/agent/rag/RuleKnowledgeBase.java"), "runner", EvaluationIdentity.source(
                "src/test/java/com/elaina/limitedtrade/agent/rag/RuleRetrievalBaselineTest.java")));
        metadata.put("runs", runs);
        metadata.put("complete", false);

        Path modelPath = Path.of(System.getProperty("agent.evaluation.bge-directory", "tools/models/bge-small-zh-v1.5"));
        try (var model = new BgeEmbeddingModel(modelPath)) {
            metadata.put("embeddingFingerprint", model.fingerprint());
            // 每次重新构建索引，仅在同一次评测的各配置中复用。
            Path index = output.resolveSibling(output.getFileName() + ".index.json");
            try {
                for (var configuration : CONFIGURATIONS) {
                    var settings = new RagProperties(true, modelPath, index, 320,
                            configuration.topK(), configuration.threshold(), false, RagProperties.RetrievalMode.VECTOR, false, false);
                    var knowledge = new RuleKnowledgeBase(model, settings, json);
                    var run = new LinkedHashMap<String, Object>();
                    var rows = new ArrayList<Map<String, Object>>();
                    run.put("configuration", configuration);
                    run.put("chunkChars", settings.chunkChars());
                    run.put("chunkCount", knowledge.chunkCount());
                    run.put("corpusFingerprint", knowledge.fingerprint());
                    run.put("cases", rows);
                    runs.add(run);
                    for (var task : suite) {
                        long started = System.nanoTime();
                        var result = knowledge.search(task.required("query").asText());
                        var ranked = result.chunks().stream().map(RuleChunk::documentId).distinct().toList();
                        var expected = task.required("expected");
                        int rank = expected.isNull() ? 0 : ranked.indexOf(expected.asText()) + 1;
                        rows.add(Map.of("task", task, "hits", result.chunks(), "documents", ranked, "rank", rank,
                                "noEvidence", result.chunks().isEmpty(),
                                "elapsedMicros", TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started)));
                    }
                    var summaries = new LinkedHashMap<String, Object>();
                    for (String split : List.of("calibration", "evaluation", "holdout")) {
                        var cases = rows.stream().filter(row -> ((JsonNode) row.get("task"))
                                .required("split").asText().equals(split)).toList();
                        var positive = cases.stream().filter(row -> !((JsonNode) row.get("task"))
                                .required("expected").isNull()).toList();
                        long negative = cases.size() - positive.size();
                        long hit1 = positive.stream().filter(row -> (int) row.get("rank") == 1).count();
                        long recalled = positive.stream().filter(row -> (int) row.get("rank") > 0).count();
                        long rejected = cases.stream().filter(row -> ((JsonNode) row.get("task"))
                                .required("expected").isNull() && (boolean) row.get("noEvidence")).count();
                        double mrr = positive.stream().mapToDouble(row -> {
                            int rank = (int) row.get("rank");
                            return rank > 0 ? 1.0 / rank : 0.0;
                        }).average().orElseThrow();
                        summaries.put(split, Map.of("positive", positive.size(), "negative", negative, "hitAt1", hit1,
                                "recallAtK", recalled, "mrr", mrr, "noEvidence", rejected));
                    }
                    run.put("splits", summaries);
                }
                metadata.put("complete", true);
            } finally {
                java.nio.file.Files.deleteIfExists(index);
            }
        } finally {
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), metadata);
            System.out.println("Retrieval baseline: " + output);
        }
        assertThat(runs).hasSize(3).allSatisfy(run -> assertThat((List<?>) run.get("cases")).hasSize(30));
    }
}
