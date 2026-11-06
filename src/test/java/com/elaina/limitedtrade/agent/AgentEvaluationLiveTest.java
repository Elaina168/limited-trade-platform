package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.conversation.AgentConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

// 同步请求全部在测试事务内，夹具、会话和模拟状态变更结束后一起回滚。
@EnabledIfSystemProperty(named = "agent.evaluation.live", matches = "true")
@SpringBootTest(properties = {"app.agent.enabled=true", "app.agent.rag.enabled=true"})
@ActiveProfiles("test")
@Transactional
class AgentEvaluationLiveTest {
    @Autowired AgentConversationService conversations;
    @Autowired AgentProperties properties;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test void evaluateFixedScenariosAndKeepEveryOutcome() throws Exception {
        var suite = json.readTree(new ClassPathResource("agent-evaluation.json").getInputStream());
        String selection = System.getProperty("agent.evaluation.scenarios");
        if (selection != null) {
            var requested = java.util.Arrays.stream(selection.split(",", -1)).map(String::trim).toList();
            assertThat(requested).doesNotContain("");
            var filtered = json.createArrayNode();
            for (var scenario : suite) if (requested.contains(scenario.required("id").asText())) filtered.add(scenario);
            assertThat(java.util.stream.StreamSupport.stream(filtered.spliterator(), false).map(row -> row.required("id").asText()).toList())
                    .containsExactlyInAnyOrderElementsOf(requested);
            suite = filtered;
        }
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("limited_trade_platform_test");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id IN (7301,7302) OR username IN ('admin_query_buyer_one','admin_query_buyer_two')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE id BETWEEN 9301 AND 9305", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=9301", Integer.class)).isZero();
        new ResourceDatabasePopulator(new ClassPathResource("sql/admin-order-test-data.sql")).execute(dataSource);
        var scenarios = new ArrayList<Map<String,Object>>();
        for (var scenario : suite) {
            var steps = new ArrayList<Map<String,Object>>();
            for (var step : scenario.required("steps")) {
                var row = new LinkedHashMap<String,Object>();
                row.put("question", step.required("question").asText());
                row.put("expected", step.required("expected"));
                row.put("state", "NOT_RUN");
                steps.add(row);
            }
            scenarios.add(new LinkedHashMap<>(Map.of("id", scenario.required("id").asText(), "steps", steps)));
        }
        Path reportFile = Files.createTempFile(Path.of("tools"), "agent-evaluation-", ".json");
        boolean complete = false;
        try {
            for (int i = 0; i < suite.size(); i++) {
                var scenario = suite.get(i);
                var id = conversations.create(7301).conversationId();
                @SuppressWarnings("unchecked")
                var rows = (List<Map<String,Object>>) scenarios.get(i).get("steps");
                for (int n = 0; n < rows.size(); n++) {
                    var step = scenario.required("steps").get(n);
                    if (step.path("action").asText().equals("close-order")) jdbc.update("UPDATE trade_order SET status='CLOSED' WHERE id=9301");
                    if (step.path("action").asText().equals("clear")) conversations.clear(7301, id);
                    var before = jdbc.queryForList("SELECT * FROM trade_order WHERE id BETWEEN 9301 AND 9305 ORDER BY id");
                    var row = rows.get(n);
                    row.put("state", "STARTED");
                    long start = System.nanoTime();
                    AgentChatResponse response = null;
                    Integer errorStatus = null;
                    try {
                        response = conversations.chat(7301, id, step.required("question").asText());
                        row.put("response", response);
                    } catch (ResponseStatusException error) {
                        // 可预期的业务/上游错误保存状态，不输出可能含敏感内容的异常文本。
                        errorStatus = error.getStatusCode().value();
                        row.put("errorStatus", errorStatus);
                    } finally {
                        row.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    }
                    boolean unchanged = before.equals(jdbc.queryForList("SELECT * FROM trade_order WHERE id BETWEEN 9301 AND 9305 ORDER BY id"));
                    row.put("checks", AgentEvaluation.evaluate(step.required("expected"), response, errorStatus, unchanged));
                    row.put("state", "COMPLETE");
                }
                // 只移除本次新建的会话，避免十二场景触及十会话上限；外层事务仍负责回滚。
                jdbc.update("DELETE FROM agent_conversation WHERE id=? AND user_id=7301", id);
                jdbc.update("UPDATE trade_order SET status='PENDING_PAYMENT' WHERE id=9301");
            }
            complete = true;
        } finally {
            Files.writeString(reportFile, json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writerWithDefaultPrettyPrinter().writeValueAsString(AgentEvaluation.report(properties.model(), scenarios, complete)));
            System.out.println("Agent evaluation report: " + reportFile.toAbsolutePath());
        }
        assertThat(AgentEvaluation.report(properties.model(), scenarios, complete).get("passedScenarios"))
                .as("完整结果已保存，失败场景应据实复核，不自动重试").isEqualTo((long) scenarios.size());
    }
}
