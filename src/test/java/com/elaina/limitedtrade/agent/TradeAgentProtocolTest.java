package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.order.OrderPageResponse;
import com.elaina.limitedtrade.order.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeAgentProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final OrderService orders = mock(OrderService.class);
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private int upstreamStatus = 200;
    private long delayMs;

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/chat/completions", exchange -> {
            var request = mapper.readTree(exchange.getRequestBody());
            synchronized (requests) { requests.add(request); }
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
            var body = replies.get(Math.min(requests.size() - 1, replies.size() - 1)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(upstreamStatus, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }

    @AfterEach void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    private ApplicationContextRunner runner(String... additional) {
        return new ApplicationContextRunner().withUserConfiguration(AgentConfiguration.class)
                .withBean(OrderService.class, () -> orders).withBean(ObjectMapper.class, () -> mapper)
                .withPropertyValues("app.agent.enabled=true", "app.agent.api-key=test-key",
                        "app.agent.base-url=http://127.0.0.1:" + server.getAddress().getPort())
                .withPropertyValues(additional);
    }

    private String toolReply(String name, String arguments) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of("id", "completion-test", "object", "chat.completion",
                "created", 1, "model", "deepseek-flash", "choices", List.of(java.util.Map.of("index", 0,
                        "finish_reason", "tool_calls", "message", java.util.Map.of("role", "assistant", "content", "",
                                "tool_calls", List.of(java.util.Map.of("id", "call_" + replies.size(), "type", "function",
                                        "function", java.util.Map.of("name", name, "arguments", arguments)))))),
                "usage", java.util.Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
    }

    private static String answerReply() {
        return """
                {"id":"answer","object":"chat.completion","created":1,"model":"deepseek-flash",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"没有待支付订单。支付期限以订单expiresAt为准。"}}],
                 "usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25}}
                """;
    }

    @Test void realProtocolRunsToolsWithTrustedIdentityAndReturnsActualSources() throws Exception {
        when(orders.listUserOrders(7301L, 1, 5, "PENDING_PAYMENT"))
                .thenReturn(new OrderPageResponse(1, 5, 0, List.of()));
        replies.add(toolReply("list_my_orders", "{\"page\":1,\"size\":5,\"status\":\"PENDING_PAYMENT\"}"));
        replies.add(toolReply("get_trade_rules", "{\"topic\":\"PAYMENT\"}"));
        replies.add(answerReply());
        runner().run(context -> {
            var response = context.getBean(TradeAgentService.class).chat(7301L, "查询待支付订单和支付规则");
            assertThat(response.toolsUsed()).containsExactly("list_my_orders", "get_trade_rules");
            assertThat(response.sources()).extracting(TradeRule::id).containsExactly("PAYMENT");
            assertThat(response.modelCalls()).isEqualTo(3);
            assertThat(response.toolCalls()).isEqualTo(2);
            assertThat(response.totalTokens()).isEqualTo(55);
            assertThat(requests.getFirst().path("messages").get(0).path("content").asText())
                    .contains(response.queriedAt().toString());
        });
        verify(orders).listUserOrders(7301L, 1, 5, "PENDING_PAYMENT");
        assertThat(requests).hasSize(3);
        assertThat(requests.getFirst().path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(requests.getFirst().path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(requests.getFirst().path("tools").toString()).doesNotContain("userId", "7301");
        var history = requests.get(1).path("messages");
        var toolMessage = history.get(history.size() - 1);
        assertThat(toolMessage.path("role").asText()).isEqualTo("tool");
        var returnedPage = mapper.readTree(toolMessage.path("content").asText());
        assertThat(returnedPage.path("total").asInt()).isZero();
        assertThat(returnedPage.path("queriedAt").isTextual()).isTrue();
    }

    @Test void otherUsersOrderFailureStopsInsteadOfBecomingNoData() throws Exception {
        when(orders.getUserOrder(7301L, 9302L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        replies.add(toolReply("get_my_order", "{\"orderId\":9302}"));
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class).chat(7301, "查9302"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)));
        assertThat(requests).hasSize(1);
    }

    @Test void unknownToolIsNeverExecuted() throws Exception {
        replies.add(toolReply("cancel_order", "{\"orderId\":9301}"));
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class).chat(7301, "取消订单"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY)));
        verifyNoInteractions(orders);
    }

    @Test void toolBudgetStopsRepeatedRequests() throws Exception {
        replies.add(toolReply("get_trade_rules", "{\"topic\":\"PAYMENT\"}"));
        runner("app.agent.max-tool-calls=1").run(context -> assertThatThrownBy(
                        () -> context.getBean(TradeAgentService.class).chat(7301, "规则"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)));
        assertThat(requests).hasSize(2);
    }

    @Test void modelBudgetStopsBeforeAnotherNetworkRequest() throws Exception {
        replies.add(toolReply("get_trade_rules", "{\"topic\":\"PAYMENT\"}"));
        runner("app.agent.max-model-calls=1").run(context -> assertThatThrownBy(
                        () -> context.getBean(TradeAgentService.class).chat(7301, "规则"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)));
        assertThat(requests).hasSize(1);
    }

    @Test void upstreamErrorIs502WithoutRetryOrErrorBodyLeak() {
        upstreamStatus = 500;
        replies.add("{\"error\":{\"message\":\"upstream-private-detail\",\"type\":\"server_error\"}}");
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class).chat(7301, "规则"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(error.getReason()).doesNotContain("upstream-private-detail");
                }));
        assertThat(requests).hasSize(1);
    }

    @Test void upstreamTimeoutIs504() {
        delayMs = 500;
        replies.add(answerReply());
        runner("app.agent.model-timeout=100ms").run(context -> assertThatThrownBy(
                        () -> context.getBean(TradeAgentService.class).chat(7301, "规则"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT)));
    }

    @Test void invalidToolArgumentsDoNotReachBusinessService() throws Exception {
        replies.add(toolReply("list_my_orders", "{\"page\":1,\"size\":1000}"));
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class).chat(7301, "订单"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)));
        verifyNoInteractions(orders);
    }

    @Test void nullRequiredTopicIsBadRequestRatherThanInternalError() throws Exception {
        replies.add(toolReply("get_trade_rules", "{\"topic\":null}"));
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class).chat(7301, "规则"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)));
    }

    @Test void modelCannotAddIdentityParametersToToolSchema() throws Exception {
        replies.add(toolReply("list_my_orders", "{\"page\":1,\"size\":5,\"userId\":7302}"));
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class).chat(7301, "订单"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)));
        verifyNoInteractions(orders);
    }

    @Test void multipleToolsInOneResponseCannotBypassBudget() throws Exception {
        var response = mapper.readTree(toolReply("get_trade_rules", "{\"topic\":\"PAYMENT\"}"));
        var calls = (com.fasterxml.jackson.databind.node.ArrayNode) response.at("/choices/0/message/tool_calls");
        var second = calls.get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) second).put("id", "call_second");
        calls.add(second);
        replies.add(mapper.writeValueAsString(response));
        runner("app.agent.max-tool-calls=1").run(context -> assertThatThrownBy(
                        () -> context.getBean(TradeAgentService.class).chat(7301, "规则"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)));
        assertThat(requests).hasSize(1);
    }

    @Test void databaseFailureIsNotReportedAsAnEmptyOrderList() throws Exception {
        when(orders.listUserOrders(7301L, 1, 5, null))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private-sql-detail"));
        replies.add(toolReply("list_my_orders", "{\"page\":1,\"size\":5}"));
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class).chat(7301, "订单"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getReason()).doesNotContain("private-sql-detail");
                }));
        assertThat(requests).hasSize(1);
    }

    @Test void malformedUpstreamCompletionIs502() {
        replies.add("{}");
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class).chat(7301, "规则"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY)));
    }
}
