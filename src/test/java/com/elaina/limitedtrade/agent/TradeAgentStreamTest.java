package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.order.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeAgentStreamTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final OrderService orders = mock(OrderService.class);
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<String> rounds = new ArrayList<>();
    HttpServer server;
    ExecutorService executor;
    CountDownLatch firstSeen = new CountDownLatch(1);
    volatile boolean upstreamFinished;
    boolean waitForFirst;
    long pauseMillis;
    boolean continuous;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/chat/completions", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                String body = rounds.get(Math.min(requests.size() - 1, rounds.size() - 1));
                if (continuous) {
                    for (String frame : body.split("\n\n")) {
                        output.write((frame + "\n\n").getBytes(StandardCharsets.UTF_8));
                        output.flush();
                        Thread.sleep(30);
                    }
                    return;
                }
                int split = body.indexOf("\n\n") + 2;
                // Spring AI开启streamUsage时保留一帧前瞻，先供给两帧再等待客户端首段。
                if (waitForFirst) split = body.indexOf("\n\n", split) + 2;
                output.write(body.substring(0, split).getBytes(StandardCharsets.UTF_8));
                output.flush();
                if (waitForFirst && !firstSeen.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("未收到首段");
                if (pauseMillis > 0) Thread.sleep(pauseMillis);
                output.write(body.substring(split).getBytes(StandardCharsets.UTF_8));
                output.flush();
                upstreamFinished = true;
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }

    ApplicationContextRunner runner(String... extra) {
        return new ApplicationContextRunner().withUserConfiguration(AgentConfiguration.class)
                .withBean(OrderService.class, () -> orders).withBean(ObjectMapper.class, () -> json)
                .withPropertyValues("app.agent.enabled=true", "app.agent.api-key=test-key",
                        "app.agent.base-url=http://127.0.0.1:" + server.getAddress().getPort()).withPropertyValues(extra);
    }

    String chunk(String delta, String finish) {
        return "data: {\"id\":\"stream-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"delta\":"
                + delta + ",\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"") + "}]}\n\n";
    }

    String answer(String finish, boolean usage) {
        return chunk("{\"role\":\"assistant\",\"content\":\"你好\"}", null)
                + chunk("{\"content\":\"世界\"}", finish)
                + (usage ? "data: {\"id\":\"stream-1\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4,\"total_tokens\":14}}\n\n" : "")
                + "data: [DONE]\n\n";
    }

    @Test void trueDeltaArrivesBeforeUpstreamCompletesAndMatchesFinalAnswer() {
        waitForFirst = true;
        rounds.add(answer("stop", true));
        var events = new CopyOnWriteArrayList<AgentStream.Event>();
        var stream = new AgentStream(event -> {
            events.add(event);
            if (event.type().equals("delta") && firstSeen.getCount() > 0) {
                assertThat(upstreamFinished).isFalse();
                firstSeen.countDown();
            }
        });
        runner().run(context -> {
            var turn = context.getBean(TradeAgentService.class).chatTurnStream(7301, "你好", List.of(), stream);
            assertThat(turn.response().answer()).isEqualTo("你好世界");
            assertThat(events.stream().filter(event -> event.type().equals("delta")).map(AgentStream.Event::text).reduce("", String::concat))
                    .isEqualTo(turn.response().answer());
            assertThat(turn.response().totalTokens()).isEqualTo(14L);
        });
        assertThat(requests.getFirst().path("stream").asBoolean()).isTrue();
    }

    @Test void fragmentedToolArgumentsExecuteOnceAndDraftIsReset() {
        rounds.add(chunk("{\"role\":\"assistant\",\"content\":\"先查看\"}", null)
                + chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"get_trade_rules\",\"arguments\":\"{\\\"topic\\\":\"}}]}", null)
                + chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"PAYMENT\\\"}\"}}]}", "tool_calls")
                + "data: [DONE]\n\n");
        rounds.add(answer("stop", false));
        var events = new ArrayList<AgentStream.Event>();
        runner().run(context -> {
            var result = context.getBean(TradeAgentService.class).chatTurnStream(7301, "支付规则", List.of(), new AgentStream(events::add));
            assertThat(result.response().toolsUsed()).containsExactly("get_trade_rules");
            assertThat(result.response().answer()).isEqualTo("你好世界");
            assertThat(result.response().totalTokens()).isNull();
            assertThat(result.messages()).hasSize(4);
            assertThat(events).anySatisfy(event -> assertThat(event.type()).isEqualTo("reset"));
        });
        assertThat(requests).hasSize(2);
    }

    @Test void truncatedAnswerAndUnknownToolFail() {
        rounds.add(answer("length", false));
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class)
                .chatTurnStream(7301, "你好", List.of(), new AgentStream(event -> { })))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(502)));
    }

    @Test void absoluteModelDeadlineCancelsSlowStream() {
        rounds.add(answer("stop", false));
        pauseMillis = 1000;
        runner("app.agent.model-timeout=200ms").run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class)
                .chatTurnStream(7301, "你好", List.of(), new AgentStream(event -> { })))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(504)));
    }

    @Test void cancellationStopsBeforeAnyToolOrFinalAnswer() {
        waitForFirst = true;
        rounds.add(answer("stop", false));
        var holder = new AgentStream[1];
        holder[0] = new AgentStream(event -> { firstSeen.countDown(); holder[0].cancel(); });
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class)
                .chatTurnStream(7301, "你好", List.of(), holder[0])).isInstanceOf(CancellationException.class));
        verifyNoInteractions(orders);
    }

    @Test void unknownToolCannotExecuteAndModelBudgetCannotBeBypassed() {
        rounds.add(chunk("{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"cancel_order\",\"arguments\":\"{}\"}}]}", "tool_calls") + "data: [DONE]\n\n");
        runner().run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class)
                .chatTurnStream(7301, "取消", List.of(), new AgentStream(event -> { })))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(502)));
        verifyNoInteractions(orders);
    }

    @Test void toolRoundCannotExceedModelCallBudget() {
        rounds.add(chunk("{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"get_trade_rules\",\"arguments\":\"{\\\"topic\\\":\\\"PAYMENT\\\"}\"}}]}", "tool_calls") + "data: [DONE]\n\n");
        runner("app.agent.max-model-calls=1").run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class)
                .chatTurnStream(7301, "规则", List.of(), new AgentStream(event -> { })))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(503)));
        assertThat(requests).hasSize(1);
    }

    @Test void globalDeadlineStillLimitsLongerModelTimeout() {
        var body = new StringBuilder();
        for (int i = 0; i < 30; i++) body.append(chunk("{\"role\":\"assistant\",\"content\":\"片段\"}", null));
        body.append(chunk("{}", "stop")).append("data: [DONE]\n\n");
        rounds.add(body.toString());
        continuous = true;
        runner("app.agent.model-timeout=2s", "app.agent.request-timeout=200ms")
                .run(context -> assertThatThrownBy(() -> context.getBean(TradeAgentService.class)
                .chatTurnStream(7301, "你好", List.of(), new AgentStream(event -> { })))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(504)));
    }
}
