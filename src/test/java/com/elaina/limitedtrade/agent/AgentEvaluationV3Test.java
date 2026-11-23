package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.conversation.AgentConversationService;
import com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "agent.evaluation.v3", matches = "local|live")
@SpringBootTest(properties = {"app.agent.enabled=true", "app.agent.rag.enabled=true",
        "app.agent.model=deepseek-flash", "app.agent.rag.chunk-chars=320", "app.agent.rag.top-k=3",
        "app.agent.rag.similarity-threshold=0.55",
        "app.agent.budget.enabled=true", "app.agent.budget.monthly-yuan=2",
        "app.agent.budget.daily-questions-per-user=50"})
@ActiveProfiles("test")
@Transactional
class AgentEvaluationV3Test {
    private static final boolean LIVE = "live".equals(System.getProperty("agent.evaluation.v3"));
    private static EvaluationModelFixture model;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry properties) throws IOException {
        if (LIVE) {
            properties.add("app.agent.base-url", () -> "https://api.deepseek.com");
        } else {
            model = new EvaluationModelFixture();
            properties.add("app.agent.base-url", model::url);
            properties.add("app.agent.api-key", () -> "evaluation-local");
        }
        properties.add("app.agent.rag.index-file", () -> "tools/evaluation-baselines/agent-v3-index.json");
    }

    @AfterAll static void stopModel() {
        if (model != null) model.close();
    }

    @Autowired AgentConversationService conversations;
    @Autowired AgentProperties properties;
    @Autowired RuleKnowledgeBase knowledge;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;

    @Test void measureEveryLegacyAndHoldoutScenario() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("limited_trade_acceptance");
        for (String table : List.of("app_user", "product", "agent_budget_request", "agent_budget_call")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).as(table).isZero();
        }
        assertThat(properties.budget().enabled()).isTrue();
        assertThat(properties.budget().monthlyYuan()).isEqualByComparingTo("2");
        new ResourceDatabasePopulator(new ClassPathResource("sql/admin-order-test-data.sql")).execute(dataSource);

        var legacy = EvaluationIdentity.resource("agent-evaluation.json");
        var holdout = EvaluationIdentity.resource("agent-evaluation-v3-holdout.json");
        var fixtures = EvaluationIdentity.resource("agent-evaluation-v3-local-scripts.json");
        var scripts = json.readTree(fixtures);
        var suite = new ArrayList<JsonNode>();
        json.readTree(legacy).forEach(suite::add);
        json.readTree(holdout).forEach(suite::add);
        assertThat(suite).hasSize(15);
        assertThat(suite).extracting(row -> row.required("id").asText()).doesNotHaveDuplicates();
        var rows = new ArrayList<Map<String, Object>>();
        var stepsByCase = new ArrayList<List<Map<String, Object>>>();
        for (int i = 0; i < suite.size(); i++) {
            var item = suite.get(i);
            String id = item.required("id").asText();
            var steps = new ArrayList<Map<String, Object>>();
            assertThat(scripts.required(id).size()).isEqualTo(item.required("steps").size());
            for (var step : item.required("steps")) {
                var row = new LinkedHashMap<String, Object>();
                row.put("question", step.required("question").asText());
                row.put("expected", step.required("expected"));
                row.put("state", "NOT_RUN");
                steps.add(row);
            }
            stepsByCase.add(steps);
            rows.add(Map.of("id", id, "split", i < 12 ? "legacy" : "holdout", "steps", steps));
        }

        var ledgerRead = new TransactionTemplate(manager);
        ledgerRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var seenCalls = new HashSet<String>();
        var allCalls = new ArrayList<Map<String, Object>>();
        var output = EvaluationIdentity.report(LIVE ? "agent-v3-live-" : "agent-v3-local-");
        boolean complete = false;
        try {
            for (int i = 0; i < suite.size(); i++) {
                var scenario = suite.get(i);
                var created = conversations.create(7301);
                var steps = scenario.required("steps");
                for (int n = 0; n < steps.size(); n++) {
                    var step = steps.get(n);
                    switch (step.path("action").asText()) {
                        case "close-order" -> jdbc.update("UPDATE trade_order SET status='CLOSED' WHERE id=9301");
                        case "clear" -> conversations.clear(7301, created.conversationId());
                    }
                    if (!LIVE) model.step(scripts.required(scenario.required("id").asText()).get(n));
                    var previous = orders();
                    var row = stepsByCase.get(i).get(n);
                    var firstDelta = new AtomicLong(-1);
                    var stream = new AgentStream(event -> {
                        if ("delta".equals(event.type()) && !event.text().isBlank()) {
                            firstDelta.compareAndSet(-1, System.nanoTime());
                        }
                    });
                    row.put("state", "STARTED");
                    long started = System.nanoTime();
                    AgentChatResponse response = null;
                    Integer status = null;
                    try {
                        var turn = conversations.prepare(7301, created.conversationId(), step.required("question").asText());
                        response = conversations.stream(turn, stream);
                        row.put("response", response);
                    } catch (ResponseStatusException error) {
                        status = error.getStatusCode().value();
                        row.put("errorStatus", status);
                    } finally {
                        row.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                        row.put("firstTextMs", firstDelta.get() < 0 ? null
                                : TimeUnit.NANOSECONDS.toMillis(firstDelta.get() - started));
                    }
                    var usage = ledgerRead.execute(tx -> jdbc.queryForList("""
                            SELECT id, state, model, price_version, actual_input_tokens, actual_output_tokens,
                                actual_total_tokens, charged_micros, reserved_micros FROM agent_budget_call
                            """)).stream().filter(call -> seenCalls.add((String) call.remove("id"))).toList();
                    row.put("usage", usage);
                    allCalls.addAll(usage);
                    var expected = step.required("expected");
                    var checks = AgentEvaluation.evaluate(expected, response, status, previous.equals(orders()));
                    if (expected.has("documents")) {
                        var answer = response;
                        int covered = 0;
                        if (answer != null) for (var document : expected.required("documents")) {
                            if (answer.knowledgeSources().stream().anyMatch(chunk ->
                                    chunk.documentId().equals(document.asText()) && answer.answer().contains(chunk.id()))) covered++;
                        }
                        row.put("expectedDocumentCount", expected.required("documents").size());
                        row.put("citedExpectedDocuments", covered);
                        checks.put("citesExpectedDocuments", covered == expected.required("documents").size());
                    }
                    row.put("checks", checks);
                    row.put("state", "COMPLETE");
                }
                conversations.delete(7301, created.conversationId());
                jdbc.update("UPDATE trade_order SET status='PENDING_PAYMENT' WHERE id=9301");
            }
            complete = true;
        } finally {
            var report = AgentEvaluation.report(properties.model(), rows, complete);
            report.put("evaluationVersion", 3);
            report.put("mode", LIVE ? "live" : "scripted-local");
            report.put("providerRevision", LIVE ? "alias-only" : "local-fixture-v3");
            report.put("costScope", LIVE ? "estimated-provider-cost" : "simulated-usage-estimate");
            report.put("datasets", Map.of("legacy", EvaluationIdentity.digest(legacy),
                    "holdout", EvaluationIdentity.digest(holdout), "localScripts", EvaluationIdentity.digest(fixtures)));
            report.put("corpusFingerprint", knowledge.fingerprint());
            report.put("configuration", Map.of("transport", "stream", "chunkChars", properties.rag().chunkChars(),
                    "topK", properties.rag().topK(), "threshold", properties.rag().similarityThreshold(),
                    "maxOutputTokens", properties.maxOutputTokens(), "monthlyYuan", properties.budget().monthlyYuan()));
            report.put("source", Map.of("agent", EvaluationIdentity.source("src/main/java/com/elaina/limitedtrade/agent/TradeAgentService.java"),
                    "ledger", EvaluationIdentity.source("src/main/java/com/elaina/limitedtrade/agent/AgentUsageLedger.java"),
                    "scorer", EvaluationIdentity.source("src/test/java/com/elaina/limitedtrade/agent/AgentEvaluation.java"),
                    "runner", EvaluationIdentity.source("src/test/java/com/elaina/limitedtrade/agent/AgentEvaluationV3Test.java")));
            var settled = allCalls.stream().filter(call -> "SETTLED".equals(call.get("state"))).toList();
            long held = allCalls.stream().filter(call -> call.get("charged_micros") == null)
                    .mapToLong(call -> ((Number) call.get("reserved_micros")).longValue()).sum();
            report.put("ledgerTotals", Map.of("calls", allCalls.size(), "settledCalls", settled.size(),
                    "unknownOrReservedCalls", allCalls.stream().filter(call -> call.get("charged_micros") == null).count(),
                    "knownTokens", settled.stream().mapToLong(call -> ((Number) call.get("actual_total_tokens")).longValue()).sum(),
                    "chargedMicros", settled.stream().mapToLong(call -> ((Number) call.get("charged_micros")).longValue()).sum(),
                    "heldMicros", held));
            var completedSteps = stepsByCase.stream().flatMap(List::stream).toList();
            report.put("citationCoverage", Map.of(
                    "expectedDocuments", completedSteps.stream().mapToInt(row -> (int) row.getOrDefault("expectedDocumentCount", 0)).sum(),
                    "citedDocuments", completedSteps.stream().mapToInt(row -> (int) row.getOrDefault("citedExpectedDocuments", 0)).sum()));
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            System.out.println("Agent baseline: " + output);
        }
        assertThat(AgentEvaluation.report(properties.model(), rows, complete).get("passedScenarios"))
                .isEqualTo((long) suite.size());
    }

    private List<Map<String, Object>> orders() {
        return jdbc.queryForList("SELECT * FROM trade_order WHERE id BETWEEN 9301 AND 9305 ORDER BY id");
    }
}
