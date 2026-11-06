package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.conversation.AgentConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ai.chat.messages.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.agent.enabled=true", "app.agent.api-key=test-key", "app.agent.rag.enabled=false"})
@ActiveProfiles("test")
class AgentSseHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired AgentConversationService conversations;
    @MockitoBean TradeAgentService agent;
    HttpClient client;
    long userId;
    String conversation;
    String csrf;
    String header;
    final CountDownLatch produced = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach void login() throws Exception {
        String username = "sse" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,?,'USER')", username, passwords.encode("Sse-test-123"));
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
        client = HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(3)).build();
        refreshCsrf();
        var login = client.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header(header, csrf).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=Sse-test-123")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(204);
        refreshCsrf();
        conversation = conversations.create(userId).conversationId();
    }

    URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    void refreshCsrf() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var token = json.readTree(response.body());
        header = token.path("headerName").asText();
        csrf = token.path("token").asText();
    }

    HttpResponse<java.io.InputStream> connect() throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/agent/conversations/" + conversation + "/messages/stream"))
                .timeout(Duration.ofSeconds(10)).header(header, csrf).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"问题\"}")).build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    @AfterEach void cleanup() {
        release.countDown();
        if (client != null) client.shutdownNow();
        jdbc.update("DELETE FROM agent_conversation WHERE user_id=?", userId);
        jdbc.update("DELETE FROM app_user WHERE id=?", userId);
    }

    @Test void realHttpFlushesDeltaBeforeModelCompletesAndDoneFollowsDatabaseCommit() throws Exception {
        when(agent.chatTurnStream(eq(userId), eq("问题"), anyList(), any())).thenAnswer(call -> {
            AgentStream stream = call.getArgument(3);
            stream.emit("delta", 1, "第一段\n第二行");
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            stream.emit("delta", 1, "最后段");
            return new AgentTurn(new AgentChatResponse("第一段\n第二行最后段", java.time.LocalDateTime.now(), List.of(), List.of(), 1, 0, 1L, 1, List.of()),
                    List.of(new UserMessage("问题"), new AssistantMessage("第一段\n第二行最后段")));
        });
        var response = connect();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String first = executor.submit(() -> {
                for (String line; (line = reader.readLine()) != null;) if (line.contains("第一段")) return line;
                return "";
            }).get(5, TimeUnit.SECONDS);
            assertThat(first).contains("\\n");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, conversation)).isZero();
            release.countDown();
            String rest = executor.submit(() -> reader.lines().reduce("", (a,b) -> a + b + "\n")).get(5, TimeUnit.SECONDS);
            assertThat(rest).contains("event:done", "最后段");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, conversation)).isEqualTo(1);
        }
    }

    @Test void disconnectCancelsGenerationAndDoesNotSaveHalfTurn() throws Exception {
        when(agent.chatTurnStream(eq(userId), eq("问题"), anyList(), any())).thenAnswer(call -> {
            AgentStream stream = call.getArgument(3);
            stream.bindWorker();
            try {
                for (int i = 0; i < 1000; i++) {
                    stream.emit("delta", 1, "片段".repeat(1024));
                    Thread.sleep(5);
                }
                throw new IllegalStateException("断开未取消任务");
            } finally { stream.unbindWorker(); produced.countDown(); }
        });
        var response = connect();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().readNBytes(100)).isNotEmpty();
        response.body().close();
        assertThat(produced.await(8, TimeUnit.SECONDS)).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (jdbc.queryForObject("SELECT generation_token FROM agent_conversation WHERE id=?", String.class, conversation) != null
                && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(jdbc.queryForObject("SELECT generation_token FROM agent_conversation WHERE id=?", String.class, conversation)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, conversation)).isZero();
    }

    @Test void failureAfterDeltaSendsErrorWithoutDoneOrSavedHistory() throws Exception {
        when(agent.chatTurnStream(eq(userId), eq("问题"), anyList(), any())).thenAnswer(call -> {
            call.getArgument(3, AgentStream.class).emit("delta", 1, "尚未完成");
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "模型服务失败");
        });
        var response = connect();
        assertThat(response.statusCode()).isEqualTo(200);
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String body = executor.submit(() -> reader.lines().reduce("", (a,b) -> a + b + "\n")).get(5, TimeUnit.SECONDS);
            assertThat(body).contains("event:delta", "event:error", "502").doesNotContain("event:done");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, conversation)).isZero();
        }
    }
}
