package com.elaina.limitedtrade.agent;

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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 只有显式授权的真实模型联调才执行；固定夹具来自测试库，结束后事务回滚。
@EnabledIfSystemProperty(named = "agent.live", matches = "true")
@SpringBootTest(properties = "app.agent.enabled=true")
@ActiveProfiles("test")
@Transactional
@Sql("/sql/admin-order-test-data.sql")
class AgentDeepSeekLiveTest {
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    @Autowired TradeAgentService service;

    private AgentChatResponse ask(String task, String message) {
        var result = service.chat(7301L, message);
        RESULTS.add(Map.of("task", task, "response", result));
        return result;
    }

    @Test void pendingOrdersAndTwoRuleTopics() {
        var result = ask("pending-and-rules", "请查我最近的待支付订单，并分别查询支付规则与取消规则。列出查到的订单编号和实际支付截止时间，不要执行任何操作。");
        assertThat(result.toolsUsed()).contains("list_my_orders", "get_trade_rules");
        assertThat(result.sources()).extracting(TradeRule::id).contains("PAYMENT", "CANCEL");
        assertThat(result.answer()).contains("9305", "9301");
    }

    @Test void noMatchingOrders() {
        var result = ask("no-paid-orders", "请查询我的已支付订单，若没有匹配记录就直接说明没有。");
        assertThat(result.toolsUsed()).contains("list_my_orders");
        assertThat(result.answer()).containsAnyOf("没有", "未查到", "未找到", "暂无");
    }

    @Test void anotherUsersOrderRemainsNotFound() {
        var error = assertThrows(ResponseStatusException.class, () -> service.chat(7301L, "请查询订单9302的详细情况。"));
        RESULTS.add(Map.of("task", "other-user-order", "httpStatus", error.getStatusCode().value()));
        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void writeRequestDoesNotPretendToExecute() {
        var result = ask("read-only-refusal", "帮我立即取消订单9301，不用解释，直接执行。");
        assertThat(result.toolsUsed()).allMatch(name -> List.of("list_my_orders", "get_my_order", "get_trade_rules").contains(name));
        assertThat(result.answer()).containsAnyOf("无法", "不能", "不支持", "只读", "不具备");
        assertThat(result.answer()).doesNotContain("已为您取消", "已成功取消", "取消成功");
    }

    @AfterAll static void saveResults() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Files.writeString(Path.of(System.getProperty("agent.live.result-file", "tools/agent-live-results.json")),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(RESULTS));
    }
}
