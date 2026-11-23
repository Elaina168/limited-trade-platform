package com.elaina.limitedtrade.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"app.agent.enabled=true", "app.agent.api-key=test-key",
        "app.agent.budget.enabled=true", "app.agent.budget.monthly-yuan=0.08",
        "app.agent.budget.daily-questions-per-user=2"})
@ActiveProfiles("test")
class AgentUsageLedgerIntegrationTest {
    @Autowired AgentUsageLedger ledger;
    @Autowired AgentProperties properties;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TradeAgentTools tools;
    @Autowired ObjectMapper json;

    @AfterEach void cleanFixtures() {
        jdbc.update("DELETE c FROM agent_budget_call c JOIN agent_budget_request r ON r.id=c.request_id WHERE r.user_id BETWEEN -76320 AND -76301");
        jdbc.update("DELETE FROM agent_budget_request WHERE user_id BETWEEN -76320 AND -76301");
    }

    @Test void simultaneousCallsOnTwoInstancesCannotReserveMoreThanOneMonthlySlot() throws Exception {
        var peer = new AgentUsageLedger(jdbc, manager, properties);
        try (var first = ledger.begin(-76301); var second = peer.begin(-76302);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<Boolean>> results = IntStream.range(0, 2).mapToObj(i -> workers.submit(() -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("没有开始");
                try (var call = (i == 0 ? first : second).reserve(1200)) {
                    return true;
                } catch (ResponseStatusException error) {
                    assertThat(error.getStatusCode().value()).isEqualTo(429);
                    return false;
                }
            })).toList();
            start.countDown();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForList("SELECT state FROM agent_budget_call", String.class)).containsExactly("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT charged_micros FROM agent_budget_call", Long.class)).isNull();
    }

    @Test void releaseAndSettlementAreIdempotentAndDoNotDoubleCount() {
        try (var request = ledger.begin(-76303)) {
            try (var call = request.reserve(1200)) { call.release(); }
            try (var call = request.reserve(1500)) {
                call.settle(100, 30, 130);
                call.settle(100, 30, 130);
            }
        }
        assertThat(jdbc.queryForList("SELECT state FROM agent_budget_call", String.class))
                .containsExactlyInAnyOrder("RELEASED", "SETTLED");
        assertThat(jdbc.queryForObject("SELECT SUM(charged_micros) FROM agent_budget_call", Long.class)).isEqualTo(440L);
        assertThat(jdbc.queryForObject("SELECT actual_total_tokens FROM agent_budget_call WHERE state='SETTLED'", Long.class))
                .isEqualTo(130L);
    }

    @Test void aToolLoopSettlesTheSuccessfulCallAndKeepsTheTimedOutCallUnknown() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    if (calls.incrementAndGet() == 2) {
                        try { Thread.sleep(5_000); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                        return;
                    }
                    var body = """
                            {"id":"loop","object":"chat.completion","created":1,"model":"deepseek-flash",
                            "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
                            "tool_calls":[{"id":"local_call","type":"function","function":{"name":"get_trade_rules","arguments":"{\\"topic\\":\\"PAYMENT\\"}"}}]}}],
                            "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                            """.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            try {
                var local = new AgentProperties(true, "test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                        properties.model(), Duration.ofMillis(1500), properties.requestTimeout(),
                        properties.maxModelCalls(), properties.maxToolCalls(), properties.maxOutputTokens(),
                        properties.rag(), properties.budget());
                var service = new TradeAgentService(local, tools, json, Optional.empty(), Optional.of(ledger));
                assertThatThrownBy(() -> service.chat(-76305, "支付规则"))
                        .isInstanceOfSatisfying(ResponseStatusException.class,
                                error -> assertThat(error.getStatusCode().value()).isEqualTo(504));
                assertThat(calls.get()).isEqualTo(2);
                assertThat(jdbc.queryForList("SELECT state FROM agent_budget_call", String.class))
                        .containsExactlyInAnyOrder("SETTLED", "UNKNOWN");
                assertThat(jdbc.queryForObject("SELECT charged_micros FROM agent_budget_call WHERE state='SETTLED'", Long.class))
                        .isEqualTo(60L);
                assertThat(jdbc.queryForObject("SELECT actual_total_tokens FROM agent_budget_call WHERE state='UNKNOWN'", Long.class))
                        .isNull();
            } finally {
                server.stop(0);
                executor.shutdownNow();
            }
        }
    }

    @Test void restartRecoversConcurrencyButKeepsUnknownCostAndDailyQuestions() {
        var abandoned = ledger.begin(-76304);
        abandoned.reserve(2000);
        assertThatThrownBy(() -> ledger.begin(-76304)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
        jdbc.update("UPDATE agent_budget_request SET lease_until='2000-01-01' WHERE user_id=-76304");
        var restarted = new AgentUsageLedger(jdbc, manager, properties);
        try (var next = restarted.begin(-76304)) {
            assertThatThrownBy(() -> next.reserve(2000)).isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
        }
        assertThatThrownBy(() -> restarted.begin(-76304)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
        assertThat(jdbc.queryForList("SELECT state FROM agent_budget_request WHERE user_id=-76304", String.class))
                .containsExactlyInAnyOrder("EXPIRED", "CLOSED");
        assertThat(jdbc.queryForObject("SELECT state FROM agent_budget_call", String.class)).isEqualTo("UNKNOWN");
    }
}
