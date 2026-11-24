package com.elaina.limitedtrade.agent.rag;

import com.elaina.limitedtrade.agent.EvaluationIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.*;

class RuleRetrievalEvaluationTest {
    @Test void reportFixedCalibrationAndHeldOutQueriesUsingRealVectors() throws Exception {
        var mapper = new ObjectMapper();
        var settings = new RagProperties(true, Path.of("tools/models/bge-small-zh-v1.5"),
                Path.of("tools/rag-evaluation/rules.json"), 320, 3, 0, false, RagProperties.RetrievalMode.VECTOR, false, false);
        var rows = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        try (var model = new BgeEmbeddingModel(settings.modelDirectory())) {
            // 旧报告验证旧语料的固定阈值，扩充后的语料用独立入口评测。
            var originalCorpus = Stream.of("cancellation", "fulfillment", "order-access", "payment", "timeout")
                    .map(name -> new ClassPathResource("knowledge/" + name + ".md")).toArray(Resource[]::new);
            var knowledge = new RuleKnowledgeBase(model, settings, mapper, originalCorpus);
            var cases = mapper.readTree(new ClassPathResource("rag-evaluation.json").getInputStream());
            for (var task : cases) {
                var hits = knowledge.search(task.path("query").asText());
                var row = new LinkedHashMap<String, Object>();
                row.put("task", task);
                row.put("hits", hits.chunks());
                rows.add(mapper.valueToTree(row));
            }
            Files.writeString(EvaluationIdentity.report("retrieval-legacy-raw-"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows));
            assertThat(knowledge.chunkCount()).isGreaterThan(10);
            assertThat(rows).hasSize(20);
            // 阈值仅由校准集正例最低首名得分和负例最高得分之间的中点决定，保留两位小数。
            double positiveMin = rows.stream().filter(row -> row.path("task").path("split").asText().equals("calibration"))
                    .filter(row -> !row.path("task").path("expected").isNull())
                    .mapToDouble(row -> row.path("hits").get(0).path("score").asDouble()).min().orElseThrow();
            double negativeMax = rows.stream().filter(row -> row.path("task").path("split").asText().equals("calibration"))
                    .filter(row -> row.path("task").path("expected").isNull())
                    .mapToDouble(row -> row.path("hits").get(0).path("score").asDouble()).max().orElseThrow();
            assertThat(positiveMin).isGreaterThan(negativeMax);
            double threshold = Math.round((positiveMin + negativeMax) / 2 * 100) / 100.0;
            assertThat(threshold).isEqualTo(0.55);
            var evaluated = new ArrayList<Object>();
            int hit1 = 0, hit3 = 0, rejected = 0, positive = 0, negative = 0;
            for (var row : rows) {
                if (!row.path("task").path("split").asText().equals("evaluation")) continue;
                var accepted = new ArrayList<String>();
                for (var hit : row.path("hits")) if (hit.path("score").asDouble() >= threshold) accepted.add(hit.path("documentId").asText());
                var expected = row.path("task").path("expected");
                if (expected.isNull()) { negative++; if (accepted.isEmpty()) rejected++; }
                else {
                    positive++;
                    if (!accepted.isEmpty() && accepted.getFirst().equals(expected.asText())) hit1++;
                    if (accepted.contains(expected.asText())) hit3++;
                }
                evaluated.add(java.util.Map.of("id", row.path("task").path("id").asText(), "acceptedDocuments", accepted));
            }
            var report = java.util.Map.of("threshold", threshold, "chunkCount", knowledge.chunkCount(),
                    "positiveCases", positive, "hitAt1", hit1, "hitAt3", hit3,
                    "negativeCases", negative, "noEvidence", rejected, "cases", evaluated);
            Files.writeString(EvaluationIdentity.report("retrieval-legacy-summary-"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            assertThat(hit1).isEqualTo(10);
            assertThat(hit3).isEqualTo(10);
            assertThat(rejected).isEqualTo(4);
        }
    }
}
