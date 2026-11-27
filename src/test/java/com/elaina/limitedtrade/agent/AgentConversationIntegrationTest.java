package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.agent.conversation.AgentConversationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"app.agent.enabled=true", "app.agent.api-key=test-key", "app.agent.rag.enabled=false"})
@ActiveProfiles("test")
class AgentConversationIntegrationTest {
    @Autowired AgentConversationService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TradeAgentService agent;
    long userId;
    long otherId;

    @BeforeEach void fixture() {
        String username = "conversation-" + UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?, 'unused', 'USER')", username);
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?, 'unused', 'USER')", username + "b");
        otherId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username + "b");
        when(agent.chatTurn(anyLong(), anyString(), anyList())).thenAnswer(call -> new AgentTurn(
                new AgentChatResponse("订单9301", java.time.LocalDateTime.now(), List.of(), List.of(), 1, 0, 1L, 1, List.of()),
                List.of(new UserMessage(call.getArgument(1, String.class)), new AssistantMessage("订单9301"))));
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM agent_conversation WHERE user_id IN (?,?)", userId, otherId);
        jdbc.update("DELETE FROM app_user WHERE id IN (?,?)", userId, otherId);
    }

    @Test void ownedHistoryReloadsAndClearRemovesIt() {
        var id = service.create(userId).conversationId();
        service.chat(userId, id, "查订单");
        service.chat(userId, id, "第一笔呢");
        verify(agent).chatTurn(eq(userId), eq("第一笔呢"), argThat(history -> history.size() == 2));
        service.clear(userId, id);
        service.chat(userId, id, "第一笔呢");
        verify(agent).chatTurn(userId, "第一笔呢", List.of());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test void ownershipExpiryAndCreationLimitAreEnforcedBeforeModel() {
        var id = service.create(userId).conversationId();
        assertStatus(404, () -> service.chat(otherId, id, "偷看"));
        assertStatus(404, () -> service.clear(otherId, id));
        verifyNoInteractions(agent);
        jdbc.update("UPDATE agent_conversation SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?", id);
        assertStatus(404, () -> service.chat(userId, id, "过期"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation WHERE id=?", Integer.class, id)).isZero();
        for (int i = 0; i < 10; i++) service.create(userId);
        assertStatus(409, () -> service.create(userId));
    }

    @Test void onlySixCompleteTurnsAreStoredAndFailureDoesNotAppend() {
        var id = service.create(userId).conversationId();
        for (int i = 0; i < 8; i++) service.chat(userId, id, "问题" + i);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, id)).isEqualTo(6);
        when(agent.chatTurn(eq(userId), eq("失败"), anyList())).thenThrow(new IllegalStateException("模型失败"));
        assertThatThrownBy(() -> service.chat(userId, id, "失败")).hasMessage("模型失败");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, id)).isEqualTo(6);
        service.chat(userId, id, "恢复");
    }

    @Test void concurrentRequestIsRejectedAndClearInvalidatesInflightAnswer() {
        var id = service.create(userId).conversationId();
        when(agent.chatTurn(eq(userId), eq("生成中"), anyList())).thenAnswer(call -> {
            assertStatus(409, () -> service.chat(userId, id, "并发"));
            service.clear(userId, id);
            return new AgentTurn(new AgentChatResponse("旧答案", java.time.LocalDateTime.now(), List.of(), List.of(), 1, 0, 1L, 1, List.of()),
                    List.of(new UserMessage("生成中"), new AssistantMessage("旧答案")));
        });
        assertStatus(409, () -> service.chat(userId, id, "生成中"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, id)).isZero();
        service.chat(userId, id, "新问题");
    }

    @Test void expiredLeaseCanBeTakenOverAndOversizedTurnClearsOlderContext() {
        var id = service.create(userId).conversationId();
        service.chat(userId, id, "开始");
        jdbc.update("UPDATE agent_conversation SET generation_token=?,busy_until=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?", UUID.randomUUID().toString(), id);
        service.chat(userId, id, "接管");
        when(agent.chatTurn(eq(userId), eq("超长"), anyList())).thenReturn(new AgentTurn(
                new AgentChatResponse("答案", java.time.LocalDateTime.now(), List.of(), List.of(), 1, 0, 1L, 1, List.of()),
                List.of(new UserMessage("超长"), new AssistantMessage("字".repeat(24_001)))));
        service.chat(userId, id, "超长");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, id)).isZero();
        service.chat(userId, id, "下一轮");
        verify(agent).chatTurn(userId, "下一轮", List.of());
    }

    @Test void concurrentCreationNeverExceedsTen() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                try { service.create(userId); return true; }
                catch (ResponseStatusException error) {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    return false;
                }
            }));
            int success = 0;
            for (var future : futures) if (future.get(10, java.util.concurrent.TimeUnit.SECONDS)) success++;
            assertThat(success).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation WHERE user_id=?", Integer.class, userId)).isEqualTo(10);
        }
    }

    @Test void supersededGenerationCannotOverwriteNewerSuccessfulTurn() {
        var id = service.create(userId).conversationId();
        when(agent.chatTurn(eq(userId), eq("旧请求"), anyList())).thenAnswer(call -> {
            jdbc.update("UPDATE agent_conversation SET busy_until=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?", id);
            service.chat(userId, id, "新请求");
            return new AgentTurn(new AgentChatResponse("旧答案", java.time.LocalDateTime.now(), List.of(), List.of(), 1, 0, 1L, 1, List.of()),
                    List.of(new UserMessage("旧请求"), new AssistantMessage("旧答案")));
        });
        assertStatus(409, () -> service.chat(userId, id, "旧请求"));
        var json = jdbc.queryForObject("SELECT messages_json FROM agent_conversation_turn WHERE conversation_id=?", String.class, id);
        assertThat(json).contains("新请求").doesNotContain("旧请求", "旧答案");
    }

    @Test void damagedStoredHistoryFailsWithoutAppendingOrHoldingLease() {
        var id = service.create(userId).conversationId();
        service.chat(userId, id, "原问题");
        jdbc.update("UPDATE agent_conversation_turn SET messages_json='broken-json' WHERE conversation_id=?", id);
        clearInvocations(agent);
        assertThatThrownBy(() -> service.chat(userId, id, "下一轮")).hasMessage("会话历史反序列化失败");
        verifyNoInteractions(agent);
        assertThat(jdbc.queryForObject("SELECT generation_token FROM agent_conversation WHERE id=?", String.class, id)).isNull();
        assertThat(jdbc.queryForObject("SELECT messages_json FROM agent_conversation_turn WHERE conversation_id=?", String.class, id)).isEqualTo("broken-json");
    }

    @Test void listAndHistoryOnlyExposeOwnedActiveConversationsAndReadsDoNotRenewExpiry() {
        var first = service.create(userId).conversationId();
        var second = service.create(userId).conversationId();
        jdbc.update("UPDATE agent_conversation SET expires_at=DATE_ADD(NOW(),INTERVAL 2 HOUR) WHERE id=?", first);
        var expired = service.create(userId).conversationId();
        jdbc.update("UPDATE agent_conversation SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?", expired);
        var other = service.create(otherId).conversationId();

        var page = service.list(userId, 1, 1);
        assertThat(page.items()).extracting(AgentConversationService.CreatedConversation::conversationId).containsExactly(second);
        assertThat(page.hasNext()).isTrue();
        var next = service.list(userId, 2, 1);
        assertThat(next.items()).extracting(AgentConversationService.CreatedConversation::conversationId).containsExactly(first);
        assertThat(next.hasNext()).isFalse();
        assertThat(service.readHistory(userId, first).expiresAt()).isEqualTo(next.items().getFirst().expiresAt());
        assertThat(service.readHistory(userId, first).turns()).isEmpty();
        assertStatus(404, () -> service.readHistory(userId, expired));
        assertStatus(404, () -> service.readHistory(userId, other));
        assertStatus(404, () -> service.delete(userId, other));
    }

    @Test void visibleHistoryRetainsFiftyCompletedTurnsIndependentlyFromModelContext() {
        var created = service.create(userId);
        var id = created.conversationId();
        assertThat(created.title()).isEqualTo("新对话");
        assertThat(service.list(userId, 1, 10).items().getFirst().title()).isEqualTo("新对话");
        String title = "问".repeat(21) + "😀";
        service.chat(userId, id, title + "后续");
        for (int i = 1; i < 52; i++) service.chat(userId, id, "问题" + i);
        assertThat(service.list(userId, 1, 10).items().getFirst().title()).isEqualTo(title);
        assertThat(service.list(otherId, 1, 10).items()).isEmpty();
        var turns = service.readHistory(userId, id).turns();
        assertThat(turns).hasSize(50);
        assertThat(turns.getFirst().question()).isEqualTo("问题2");
        assertThat(turns.getLast().question()).isEqualTo("问题51");
        verify(agent).chatTurn(eq(userId), eq("问题51"), argThat(context -> context.size() == 12));
        when(agent.chatTurn(eq(userId), eq("带来源"), anyList())).thenReturn(new AgentTurn(
                new AgentChatResponse("最终回答", java.time.LocalDateTime.now(), List.of("内部工具"),
                        List.of(new TradeRule("公开规则", "规则", "公开文档")), 2, 1, 2L, 9, List.of()),
                List.of(new UserMessage("带来源"), new AssistantMessage("内部文本"),
                        new AssistantMessage("最终回答"))));
        service.chat(userId, id, "带来源");
        var visible = service.readHistory(userId, id).turns().getLast();
        assertThat(visible.answer()).isEqualTo("最终回答");
        assertThat(visible.sources()).extracting(TradeRule::id).containsExactly("公开规则");
        service.clear(userId, id);
        assertThat(service.list(userId, 1, 10).items().getFirst().title()).isEqualTo("新对话");
    }

    @Test void legacyVisibleTurnProvidesTitleAndEmptyOrders() {
        var id = service.create(userId).conversationId();
        service.chat(userId, id, "原来的问题");
        jdbc.update("""
                UPDATE agent_conversation_visible_turn
                SET display_json=JSON_REMOVE(display_json, '$.title', '$.orders')
                WHERE conversation_id=?
                """, id);
        assertThat(service.list(userId, 1, 10).items().getFirst().title()).isEqualTo("原来的问题");
        assertThat(service.readHistory(userId, id).turns().getFirst().orders()).isEmpty();
    }

    @Test void historyReadsCommittedSnapshotAndClearOrDeleteInvalidatesPendingGeneration() {
        var id = service.create(userId).conversationId();
        service.chat(userId, id, "已提交");
        when(agent.chatTurn(eq(userId), eq("清空中"), anyList())).thenAnswer(call -> {
            var before = service.readHistory(userId, id);
            assertThat(before.generating()).isTrue();
            assertThat(before.turns()).extracting(AgentConversationService.VisibleTurn::question).containsExactly("已提交");
            service.clear(userId, id);
            return new AgentTurn(new AgentChatResponse("旧答", java.time.LocalDateTime.now(), List.of(), List.of(), 1, 0, 1L, 1, List.of()),
                    List.of(new UserMessage("清空中"), new AssistantMessage("旧答")));
        });
        assertStatus(409, () -> service.chat(userId, id, "清空中"));
        assertThat(service.readHistory(userId, id).turns()).isEmpty();
        service.chat(userId, id, "待删除");
        var prepared = service.prepare(userId, id, "删除中");
        service.delete(userId, id);
        var stream = new AgentStream(event -> { });
        when(agent.chatTurnStream(eq(userId), eq("删除中"), anyList(), eq(stream))).thenReturn(new AgentTurn(
                new AgentChatResponse("旧答", java.time.LocalDateTime.now(), List.of(), List.of(), 1, 0, 1L, 1, List.of()),
                List.of(new UserMessage("删除中"), new AssistantMessage("旧答"))));
        assertStatus(404, () -> service.stream(prepared, stream));
        assertStatus(404, () -> service.readHistory(userId, id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_visible_turn WHERE conversation_id=?", Integer.class, id)).isZero();
        for (int i = 0; i < 10; i++) service.create(userId);
        assertStatus(409, () -> service.create(userId));
    }

    private static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }

    @Test void preparedStreamClaimsBeforeOpeningAndOnlyCommitsCompleteTurn() {
        var id = service.create(userId).conversationId();
        var prepared = service.prepare(userId, id, "流式问题");
        assertStatus(409, () -> service.prepare(userId, id, "并发"));
        var stream = new AgentStream(event -> { });
        when(agent.chatTurnStream(eq(userId), eq("流式问题"), anyList(), eq(stream))).thenReturn(new AgentTurn(
                new AgentChatResponse("完整答案", java.time.LocalDateTime.now(), List.of(), List.of(), 1, 0, 1L, 1, List.of()),
                List.of(new UserMessage("流式问题"), new AssistantMessage("完整答案"))));
        assertThat(service.stream(prepared, stream).answer()).isEqualTo("完整答案");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, id)).isEqualTo(1);
        var next = service.prepare(userId, id, "被取消");
        var cancelled = new AgentStream(event -> { });
        cancelled.cancel();
        assertThatThrownBy(() -> service.stream(next, cancelled)).isInstanceOf(java.util.concurrent.CancellationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, id)).isEqualTo(1);
        var last = service.prepare(userId, id, "被清空");
        when(agent.chatTurnStream(eq(userId), eq("被清空"), anyList(), any())).thenAnswer(call -> {
            service.clear(userId, id);
            return new AgentTurn(new AgentChatResponse("旧答案", java.time.LocalDateTime.now(), List.of(), List.of(), 1, 0, 1L, 1, List.of()),
                    List.of(new UserMessage("被清空"), new AssistantMessage("旧答案")));
        });
        assertStatus(409, () -> service.stream(last, new AgentStream(event -> { })));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_turn WHERE conversation_id=?", Integer.class, id)).isZero();
    }
}
