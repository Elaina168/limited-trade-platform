package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.conversation.AgentConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;

import static org.assertj.core.api.Assertions.*;

// 多轮存储使用独立短事务；固定夹具显式提交，测试后按固定ID清理，不访问开发库。
@EnabledIfSystemProperty(named = "agent.conversation.live", matches = "true")
@SpringBootTest(properties = {"app.agent.enabled=true", "app.agent.rag.enabled=true"})
@ActiveProfiles("test")
@Sql(scripts = "/sql/admin-order-test-data.sql", config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED))
class AgentConversationDeepSeekLiveTest {
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    @Autowired AgentConversationService service;
    @Autowired JdbcTemplate jdbc;

    private AgentChatResponse ask(String id, String task, String message) {
        var result = service.chat(7301, id, message);
        RESULTS.add(Map.of("task", task, "question", message, "response", result));
        return result;
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM agent_conversation WHERE user_id IN (7301,7302)");
        jdbc.update("DELETE FROM order_item WHERE order_id BETWEEN 9301 AND 9305");
        jdbc.update("DELETE FROM trade_order WHERE id BETWEEN 9301 AND 9305");
        jdbc.update("DELETE FROM product WHERE id=9301");
        jdbc.update("DELETE FROM app_user WHERE id IN (7301,7302)");
    }

    @Test void orderReferenceRequeriesCurrentStateAndClearRemovesReference() {
        var id = service.create(7301).conversationId();
        var first = ask(id, "orders-first-turn", "查询我最近的待支付订单，按返回顺序列出编号和截止时间。");
        assertThat(first.toolsUsed()).contains("list_my_orders");
        assertThat(first.answer()).contains("9305", "9301");
        jdbc.update("UPDATE trade_order SET status='CLOSED' WHERE id=9305");
        var second = ask(id, "reference-fresh-state", "第一笔现在是什么状态，支付截止时间是什么？请重新查询确认。");
        assertThat(second.toolsUsed()).containsAnyOf("get_my_order", "list_my_orders");
        assertThat(second.answer()).contains("9305").containsAnyOf("CLOSED", "已关闭");
        assertThat(second.answer()).contains("10:19");
        service.clear(7301, id);
        var cleared = ask(id, "after-clear", "第一笔现在是什么状态？");
        assertThat(cleared.answer()).containsAnyOf("请", "提供", "哪", "明确", "确认");
        assertThat(cleared.answer()).doesNotContain("9305");
        assertThat(cleared.toolsUsed()).doesNotContain("get_my_order");
    }

    @Test void ruleFollowUpUsesNewRetrieval() {
        var id = service.create(7301).conversationId();
        var first = ask(id, "rule-first-turn", "请检索平台规则，解释待支付订单可以主动取消吗？");
        assertThat(first.toolsUsed()).contains("search_trade_rules");
        var second = ask(id, "rule-follow-up", "那重复操作会不会多归还一次库存？请根据规则片段解释。");
        assertThat(second.toolsUsed()).contains("search_trade_rules");
        assertThat(second.knowledgeSources()).isNotEmpty();
        assertThat(second.answer()).containsAnyOf("不会", "不重复", "不会重复");
    }

    @AfterAll static void saveResults() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Files.writeString(Path.of("tools/agent-conversation-live-results.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(RESULTS));
    }
}
