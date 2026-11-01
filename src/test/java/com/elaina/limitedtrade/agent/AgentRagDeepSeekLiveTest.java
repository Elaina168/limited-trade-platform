package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.rag.RuleChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

// 显式启用才访问DeepSeek；只使用测试库夹具，结束时事务回滚。
@EnabledIfSystemProperty(named = "agent.rag.live", matches = "true")
@SpringBootTest(properties = {"app.agent.enabled=true", "app.agent.rag.enabled=true"})
@ActiveProfiles("test")
@Transactional
@Sql("/sql/admin-order-test-data.sql")
class AgentRagDeepSeekLiveTest {
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    @Autowired TradeAgentService service;

    private AgentChatResponse ask(String task, String question) {
        var result = service.chat(7301, question);
        RESULTS.add(Map.of("task", task, "question", question, "response", result));
        return result;
    }

    @Test void rulesWithRealRetrievedCitations() {
        var result = ask("expiry-and-restore", "请检索超时规则：到了支付截止时间但状态还是待支付，能继续付款吗？超时关闭后库存如何处理？请给出片段id依据。");
        assertThat(result.toolsUsed()).contains("search_trade_rules").doesNotContain("get_trade_rules");
        assertThat(result.knowledgeSources()).extracting(RuleChunk::documentId).contains("timeout");
        assertThat(result.knowledgeSources()).anySatisfy(chunk -> assertThat(result.answer()).contains(chunk.id()));
        assertThat(result.answer()).containsAnyOf("不能", "不允许", "不可").contains("库存");
    }

    @Test void unsupportedPolicyHasNoInventedSource() {
        var result = ask("no-return-policy", "请先检索平台商品损坏退货邮费由谁承担，再回答；没有资料就说明无法确定，不要补充行业惯例。");
        assertThat(result.toolsUsed()).contains("search_trade_rules");
        assertThat(result.knowledgeSources()).isEmpty();
        assertThat(result.answer()).containsAnyOf("无法确定", "未找到", "没有", "无相关", "未检索");
        assertThat(result.answer()).doesNotContain("商家承担", "买家承担", "平台承担");
    }

    @Test void ownedOrderAndRulesRemainDifferentDataSources() {
        var result = ask("order-and-rules", "请查询我本人的订单9301，显示它的实际支付截止时间；另外检索支付期限规则，解释刚好到截止时刻能不能付款。不要执行操作。");
        assertThat(result.toolsUsed()).contains("get_my_order", "search_trade_rules");
        assertThat(result.knowledgeSources()).extracting(RuleChunk::documentId).contains("payment");
        assertThat(result.answer()).contains("9301", "2026");
        assertThat(result.sources()).isEmpty();
    }

    @AfterAll static void saveResults() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Files.writeString(Path.of("tools/agent-rag-live-results.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(RESULTS));
    }
}
