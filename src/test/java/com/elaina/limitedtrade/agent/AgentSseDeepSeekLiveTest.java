package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.conversation.AgentConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

@EnabledIfSystemProperty(named = "agent.sse.live", matches = "true")
@SpringBootTest(properties = {"app.agent.enabled=true", "app.agent.rag.enabled=true"})
@ActiveProfiles("test")
@Sql(scripts = "/sql/admin-order-test-data.sql", config = @SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED))
class AgentSseDeepSeekLiveTest {
    static final List<Map<String,Object>> RESULTS = new ArrayList<>();
    @Autowired AgentConversationService conversations;
    @Autowired JdbcTemplate jdbc;

    AgentChatResponse ask(String id, String task, String question) {
        var events = new java.util.concurrent.CopyOnWriteArrayList<Map<String,Object>>();
        long started = System.nanoTime();
        var stream = new AgentStream(event -> events.add(Map.of("event", event,
                "elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))));
        var response = conversations.stream(conversations.prepare(7301, id, question), stream);
        long ended = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        RESULTS.add(Map.of("task", task, "events", events, "response", response, "elapsedMs", ended));
        StringBuilder text = new StringBuilder();
        for (var item : events) {
            var event = (AgentStream.Event) item.get("event");
            if (event.type().equals("reset")) text.setLength(0);
            if (event.type().equals("delta")) text.append(event.text());
        }
        assertThat(text.toString()).isEqualTo(response.answer());
        assertThat(events.stream().filter(item -> ((AgentStream.Event)item.get("event")).type().equals("delta")).count()).isGreaterThan(1);
        assertThat(events).anySatisfy(item -> {
            assertThat(((AgentStream.Event)item.get("event")).type()).isEqualTo("delta");
            assertThat((Long)item.get("elapsedMs")).isLessThan(ended);
        });
        return response;
    }

    @Test void actualDeepSeekStreamsAfterToolsAndFollowsOwnedOrderReference() {
        var id = conversations.create(7301).conversationId();
        var first = ask(id, "order-and-rules", "查询我的订单9301，给出实际支付截止时间；检索平台支付规则解释刚好到截止时刻能否付款。请引用实际片段。");
        assertThat(first.toolsUsed()).contains("get_my_order", "search_trade_rules");
        assertThat(first.answer()).contains("9301", "10:15");
        assertThat(first.knowledgeSources()).isNotEmpty();
        jdbc.update("UPDATE trade_order SET status='CLOSED' WHERE id=9301");
        var second = ask(id, "follow-up", "那它现在是什么状态？请重新查询。");
        assertThat(second.toolsUsed()).contains("get_my_order");
        assertThat(second.answer()).contains("9301").containsAnyOf("CLOSED", "已关闭");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, id)).isEqualTo(2);
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM agent_conversation WHERE user_id IN (7301,7302)");
        jdbc.update("DELETE FROM order_item WHERE order_id BETWEEN 9301 AND 9305");
        jdbc.update("DELETE FROM trade_order WHERE id BETWEEN 9301 AND 9305");
        jdbc.update("DELETE FROM product WHERE id=9301");
        jdbc.update("DELETE FROM app_user WHERE id IN (7301,7302)");
    }

    @AfterAll static void save() throws Exception {
        Files.writeString(Path.of("tools/agent-sse-live-results.json"), new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writerWithDefaultPrettyPrinter().writeValueAsString(RESULTS));
    }
}
