package com.elaina.limitedtrade.agent.rag;

import com.elaina.limitedtrade.agent.EvaluationIdentity;
import com.elaina.limitedtrade.agent.rag.RagProperties.RetrievalMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "agent.evaluation.storefront-corpus", matches = "true")
class RuleCorpusExpansionTest {

    @Test void evaluateExpandedStorefrontCorpus() throws Exception {
        var json = new ObjectMapper();
        var output = EvaluationIdentity.report("storefront-corpus-v1-");
        var index = output.resolveSibling(output.getFileName() + ".index");
        var modelPath = Path.of(System.getProperty("agent.evaluation.bge-directory", "tools/models/bge-small-zh-v1.5"));
        byte[] newData = EvaluationIdentity.resource("rag-storefront-corpus-v1.json");
        byte[] oldData = EvaluationIdentity.resource("rag-evaluation.json");
        var report = new LinkedHashMap<String, Object>();
        var results = new LinkedHashMap<String, Object>();
        report.put("complete", false);
        report.put("datasets", Map.of("expanded", EvaluationIdentity.digest(newData), "legacy", EvaluationIdentity.digest(oldData)));
        report.put("results", results);
        var fixture = json.readTree(newData);
        assertThat(fixture).hasSize(32);
        try (var model = new BgeEmbeddingModel(modelPath)) {
            report.put("modelFingerprint", model.fingerprint());
            for (var mode : List.of(RetrievalMode.VECTOR, RetrievalMode.KEYWORD, RetrievalMode.HYBRID)) {
                var settings = new RagProperties(true, modelPath, index, 320, 3, 0.55, false, mode, false, false);
                var kb = new RuleKnowledgeBase(mode == RetrievalMode.KEYWORD ? null : model, settings, json);
                report.put("chunkCount", kb.chunkCount());
                var rows = new ArrayList<Map<String, Object>>();
                for (var row : fixture) {
                    var result = kb.search(row.required("query").asText());
                    int rank = 0;
                    for (int i = 0; i < result.chunks().size(); i++) {
                        var hit = result.chunks().get(i);
                        if (!row.required("document").isNull() && hit.documentId().equals(row.required("document").asText())
                                && hit.text().contains(row.required("evidence").asText())) {
                            rank = i + 1;
                            break;
                        }
                    }
                    rows.add(Map.of("task", row, "status", result.status(), "rank", rank, "chunks", result.chunks()));
                }
                var historical = new ArrayList<Map<String, Object>>();
                for (var row : json.readTree(oldData)) {
                    var result = kb.search(row.required("query").asText());
                    var documents = result.chunks().stream().map(RuleChunk::documentId).distinct().toList();
                    int rank = row.required("expected").isNull() ? 0 : documents.indexOf(row.required("expected").asText()) + 1;
                    historical.add(Map.of("task", row, "status", result.status(), "rank", rank, "documents", documents));
                }
                results.put(mode.name(), Map.of("fingerprint", kb.fingerprint(), "expanded", rows, "legacy", historical));
                assertThat(rows).hasSize(32);
                assertThat(historical).hasSize(20);
            }
            report.put("dimensions", model.dimensions());
            if (Boolean.getBoolean("agent.evaluation.storefront.rebuild-default")) {
                Path appIndex = Path.of("tools/rag-index/rules.json");
                new RuleKnowledgeBase(model, new RagProperties(true, modelPath, appIndex, 320, 3, 0.55,
                        true, RetrievalMode.VECTOR, false, false), json);
                var reloaded = new RuleKnowledgeBase(model, new RagProperties(true, modelPath, appIndex, 320, 3, 0.55,
                        false, RetrievalMode.VECTOR, false, false), json);
                assertThat(reloaded.chunkCount()).isEqualTo(report.get("chunkCount"));
                report.put("rebuiltDefaultIndex", appIndex.toString());
            }
            report.put("complete", true);
        } finally {
            Files.deleteIfExists(index);
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            System.out.println("Storefront corpus: " + output);
        }
    }
}
