package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.order.OrderPageResponse;
import com.elaina.limitedtrade.order.OrderResponse;
import com.elaina.limitedtrade.order.OrderService;
import com.elaina.limitedtrade.order.TradeOrder;
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
            assertThat(response.orders()).isEmpty();
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

    @Test void orderFollowUpPromptSeparatesFactsFromRuleConclusions() {
        replies.add(answerReply());
        runner().run(context -> context.getBean(TradeAgentService.class).chat(7301, "那它现在是什么状态？"));
        assertThat(requests.getFirst().path("messages").get(0).path("content").asText())
                .contains("只报告本次订单工具返回的事实", "必须在本轮调用get_trade_rules", "不得用历史规则片段代替本轮查询");
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

    @Test void ragToolReturnsOnlyActuallyRetrievedChunkSources() throws Exception {
        var knowledge = mock(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class);
        var chunk = new com.elaina.limitedtrade.agent.rag.RuleChunk("chunk-1", "payment", "支付期限",
                "knowledge/payment.md", 4, 4, "订单创建15分钟后到期。", 0.9);
        var unused = new com.elaina.limitedtrade.agent.rag.RuleChunk("unused-2", "coupon", "领券",
                "knowledge/coupon-claim.md", 5, 5, "活动支持领券。", 0.8);
        when(knowledge.search("支付期限"))
                .thenReturn(new com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.SearchResult("MATCHED", List.of(chunk, unused)));
        replies.add(toolReply("search_trade_rules", "{\"query\":\"支付期限\"}"));
        replies.add(answerReply().replace("支付期限以订单expiresAt为准。", "支付期限以订单expiresAt为准。[[chunk-1]]"));
        runner().withBean(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class, () -> knowledge).run(context -> {
            var result = context.getBean(TradeAgentService.class).chat(7301, "支付期限");
            assertThat(result.knowledgeSources()).containsExactly(chunk);
            assertThat(result.sources()).isEmpty();
        });
        assertThat(requests.getFirst().path("tools").toString()).contains("search_trade_rules").doesNotContain("\"name\":\"get_trade_rules\"");
    }

    @Test void ragDoesNotPublishUnknownCitationsOrUnsupportedRules() throws Exception {
        var knowledge = mock(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class);
        var coupon = new com.elaina.limitedtrade.agent.rag.RuleChunk("coupon-1", "coupon", "领券",
                "knowledge/coupon-claim.md", 5, 5, "活动支持领券。", 0.8);
        when(knowledge.search("信用卡返现是否叠券")).thenReturn(
                new com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.SearchResult("MATCHED", List.of(coupon)));
        replies.add(toolReply("search_trade_rules", "{\"query\":\"信用卡返现是否叠券\"}"));
        replies.add(answerReply().replace("没有待支付订单。支付期限以订单expiresAt为准。", "可以叠加。[[invented-1]]"));
        runner().withBean(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class, () -> knowledge).run(context -> {
            var result = context.getBean(TradeAgentService.class).chat(7301, "信用卡返现是否叠券");
            assertThat(result.answer()).contains("无法确认").doesNotContain("可以叠加", "invented-1");
            assertThat(result.knowledgeSources()).isEmpty();
        });
    }

    @Test void ruleFollowUpRewritesOnlyTheToolQueryUsingThePreviousUserMessage() throws Exception {
        var knowledge = mock(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class);
        String rewritten = "模拟支付 还有效吗";
        when(knowledge.search(rewritten)).thenReturn(
                new com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.SearchResult("NO_EVIDENCE", List.of()));
        replies.add(toolReply("search_trade_rules", "{\"query\":\"还有效吗\"}"));
        replies.add(answerReply());
        var history = List.<org.springframework.ai.chat.messages.Message>of(
                new org.springframework.ai.chat.messages.UserMessage("模拟支付的规则是什么？"),
                new org.springframework.ai.chat.messages.AssistantMessage("其他主题是确认收货。"));
        runner("app.agent.rag.query-rewrite-enabled=true")
                .withBean(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class, () -> knowledge)
                .run(context -> {
                    var response = context.getBean(TradeAgentService.class).chatTurn(7301, "那还有效吗？", history);
                    assertThat(response.response().toolCalls()).isEqualTo(1);
                });
        verify(knowledge).search(rewritten);
        assertThat(requests).hasSize(2);
    }

    @Test void oversizedRuleResultNeverReachesAnotherModelRoundOrAuditPayload() throws Exception {
        var knowledge = mock(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class);
        String privateFixture = "只用于测试的长资料标记";
        var chunk = new com.elaina.limitedtrade.agent.rag.RuleChunk("large-1", "payment", "说明",
                "knowledge/payment.md", 1, 1, privateFixture.repeat(2000), 0.9);
        when(knowledge.search("支付期限")).thenReturn(
                new com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.SearchResult("MATCHED", List.of(chunk)));
        replies.add(toolReply("search_trade_rules", "{\"query\":\"支付期限\"}"));

        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AgentToolAccess.class);
        var audit = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        audit.start();
        logger.addAppender(audit);
        try {
            runner().withBean(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class, () -> knowledge)
                    .run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class).chat(7301, "支付期限"))
                            .isInstanceOfSatisfying(ResponseStatusException.class,
                                    error -> assertThat(error.getStatusCode().value()).isEqualTo(413)));
            assertThat(requests).hasSize(1);
            assertThat(audit.list).singleElement().satisfies(entry -> {
                assertThat(entry.getKeyValuePairs()).extracting(pair -> pair.key)
                        .containsExactly("event", "agentRequestId", "tool", "status", "reason");
                assertThat(entry.getKeyValuePairs().toString()).contains("search_trade_rules", "413")
                        .doesNotContain(privateFixture, "支付期限", "7301");
                assertThat(entry.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(audit);
            audit.stop();
        }
    }

    @Test void contextTrimsAnEntireOldToolTurnAndKeepsNewerHistory() throws Exception {
        replies.add(toolReply("get_trade_rules", "{\"topic\":\"PAYMENT\"}"));
        replies.add(answerReply());
        replies.add(answerReply());
        runner().run(context -> {
            var service = context.getBean(TradeAgentService.class);
            var old = service.chatTurn(7301, "旧规则", List.of());
            var history = new ArrayList<>(old.messages());
            history.set(0, new org.springframework.ai.chat.messages.UserMessage("旧".repeat(15000)));
            history.add(new org.springframework.ai.chat.messages.UserMessage("保留的近期问题"));
            history.add(new org.springframework.ai.chat.messages.AssistantMessage("保留的近期回答"));
            var latest = service.chatTurn(7301, "新问题", history);
            var sent = requests.get(2).path("messages");
            assertThat(sent).hasSize(4);
            assertThat(sent.get(1).path("content").asText()).isEqualTo("保留的近期问题");
            assertThat(sent.toString()).doesNotContain("tool_call_id", "旧旧");
            assertThat(latest.messages()).hasSize(2);
        });
    }

    @Test void oversizedCurrentInputIsRejectedBeforeAnUpstreamHttpRequest() {
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class)
                .chat(7301, "大".repeat(12000))).isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(413)));
        assertThat(requests).isEmpty();
    }

    @Test void successfulTurnsKeepPairedToolHistoryButNotOldSources() throws Exception {
        replies.add(toolReply("get_trade_rules", "{\"topic\":\"PAYMENT\"}"));
        replies.add(answerReply());
        replies.add(answerReply());
        runner().run(context -> {
            var service = context.getBean(TradeAgentService.class);
            var first = service.chatTurn(7301, "支付规则", List.of());
            assertThat(first.messages()).hasSize(4);
            assertThat(first.messages().stream().map(message -> message.getMessageType().getValue()).toList())
                    .containsExactly("user", "assistant", "tool", "assistant");
            var second = service.chatTurn(7301, "继续解释", first.messages());
            assertThat(second.response().sources()).isEmpty();
            assertThat(second.response().knowledgeSources()).isEmpty();
            assertThat(second.messages()).hasSize(2);
            assertThat(requests.get(2).path("messages")).hasSize(6);
            assertThat(requests.get(2).path("messages").get(3).path("role").asText()).isEqualTo("tool");
        });
    }

    @Test void followUpToolQueryUsesFreshOrderDataAndSameTrustedUser() throws Exception {
        var order = new TradeOrder();
        order.setId(9301L);
        order.setUserId(7301L);
        order.setStatus("PENDING_PAYMENT");
        order.setTotalAmount(new java.math.BigDecimal("19.90"));
        order.setItems(List.of());
        when(orders.listUserOrders(7301L, 1, 5, null))
                .thenReturn(new OrderPageResponse(1, 5, 1, List.of(OrderResponse.from(order))));
        when(orders.getUserOrder(7301L, 9301L)).thenReturn(order);
        replies.add(toolReply("list_my_orders", "{\"page\":1,\"size\":5}"));
        replies.add(answerReply());
        replies.add(toolReply("get_my_order", "{\"orderId\":9301}"));
        replies.add(answerReply());
        replies.add(answerReply().replace("没有待支付订单。", "订单编号 9301。"));
        runner().run(context -> {
            var service = context.getBean(TradeAgentService.class);
            var first = service.chatTurn(7301, "查询9301", List.of());
            assertThat(first.response().orders()).singleElement().satisfies(card -> {
                assertThat(card.id()).isEqualTo(9301L);
                assertThat(card.status()).isEqualTo("PENDING_PAYMENT");
                assertThat(card.totalAmount()).isEqualByComparingTo("19.90");
            });
            order.setStatus("CLOSED");
            var second = service.chatTurn(7301, "它现在什么状态", first.messages());
            assertThat(second.response().orders()).singleElement().satisfies(card -> {
                assertThat(card.id()).isEqualTo(9301L);
                assertThat(card.status()).isEqualTo("CLOSED");
            });
            var third = service.chatTurn(7301, "订单编号是多少", second.messages());
            assertThat(third.response().answer()).contains("9301");
            assertThat(third.response().orders()).isEmpty();
            var messages = requests.get(3).path("messages");
            var latestTool = messages.get(messages.size() - 1);
            assertThat(latestTool.path("role").asText()).isEqualTo("tool");
            assertThat(latestTool.path("content").asText()).contains("CLOSED");
            assertThat(requests.get(2).path("messages").get(0).path("content").asText())
                    .contains("历史订单结果不是当前事实", "重新查询本人订单工具", "本次请求重新调用规则工具", "先请求提供订单ID或明确指代");
        });
        verify(orders).listUserOrders(7301L, 1, 5, null);
        verify(orders).getUserOrder(7301L, 9301L);
    }

    @Test void noEvidenceDoesNotCreateSources() throws Exception {
        var knowledge = mock(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class);
        when(knowledge.search("退货邮费"))
                .thenReturn(new com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.SearchResult("NO_EVIDENCE", List.of()));
        replies.add(toolReply("search_trade_rules", "{\"query\":\"退货邮费\"}"));
        replies.add(answerReply());
        runner().withBean(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class, () -> knowledge).run(context -> {
            var result = context.getBean(TradeAgentService.class).chat(7301, "退货邮费");
            assertThat(result.answer()).contains("无法确认");
            assertThat(result.knowledgeSources()).isEmpty();
            assertThat(result.sources()).isEmpty();
        });
        assertThat(requests.get(1).path("messages").toString()).contains("NO_EVIDENCE");
    }

    @Test void localInferenceFailurePropagatesInsteadOfBecomingNoEvidence() throws Exception {
        var knowledge = mock(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class);
        when(knowledge.search("支付期限")).thenThrow(new IllegalStateException("本地BGE推理失败"));
        replies.add(toolReply("search_trade_rules", "{\"query\":\"支付期限\"}"));
        runner().withBean(com.elaina.limitedtrade.agent.rag.RuleKnowledgeBase.class, () -> knowledge).run(context ->
                assertThatThrownBy(() -> context.getBean(TradeAgentService.class).chat(7301, "支付期限"))
                        .hasStackTraceContaining("本地BGE推理失败"));
        assertThat(requests).hasSize(1);
    }
}
