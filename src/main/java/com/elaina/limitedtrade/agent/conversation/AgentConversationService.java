package com.elaina.limitedtrade.agent.conversation;

import com.elaina.limitedtrade.agent.AgentChatResponse;
import com.elaina.limitedtrade.agent.AgentProperties;
import com.elaina.limitedtrade.agent.TradeAgentService;
import com.elaina.limitedtrade.user.AppUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class AgentConversationService {
    private final AgentConversationMapper mapper;
    private final AppUserMapper users;
    private final TradeAgentService agent;
    private final AgentProperties properties;
    private final AgentHistory history;
    private final TransactionTemplate transaction;

    public AgentConversationService(AgentConversationMapper mapper, AppUserMapper users, TradeAgentService agent,
                                    AgentProperties properties, ObjectMapper json, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.users = users;
        this.agent = agent;
        this.properties = properties;
        this.history = new AgentHistory(json);
        this.transaction = new TransactionTemplate(manager);
    }

    public CreatedConversation create(long userId) {
        return transaction.execute(status -> {
            // 锁定同一用户，避免并发创建绕过十个会话的上限。
            users.lockById(userId);
            var now = LocalDateTime.now();
            mapper.deleteExpired(userId, now);
            if (mapper.countOwned(userId) >= 10) throw conflict("未过期会话最多10个");
            String id = UUID.randomUUID().toString();
            var expiry = now.plusHours(24);
            mapper.insert(new AgentConversation(id, userId, 0, null, null, expiry));
            return new CreatedConversation(id, expiry);
        });
    }

    public AgentChatResponse chat(long userId, String id, String message) {
        purgeExpired(userId);
        var ticket = transaction.execute(status -> {
            var now = LocalDateTime.now();
            var conversation = owned(userId, id, now);
            if (conversation.generationToken() != null && conversation.busyUntil().isAfter(now))
                throw conflict("当前会话正在生成回答");
            String token = UUID.randomUUID().toString();
            mapper.claim(id, token, now.plus(properties.requestTimeout()).plusSeconds(30));
            return new Ticket(conversation.revision(), token, mapper.findTurns(id));
        });
        try {
            // 外部模型调用位于事务外，历史只来自服务端数据库。
            var turn = agent.chatTurn(userId, message, history.messages(ticket.turns()));
            String encoded = history.encode(turn.messages());
            transaction.executeWithoutResult(status -> {
                var now = LocalDateTime.now();
                var current = owned(userId, id, now);
                if (current.revision() != ticket.revision() || !ticket.token().equals(current.generationToken())
                        || !current.busyUntil().isAfter(now)) throw conflict("会话已变化，请重新发送问题");
                var turns = new ArrayList<>(ticket.turns());
                turns.add(encoded);
                var retained = history.retain(turns);
                mapper.deleteTurns(id);
                for (int i = 0; i < retained.size(); i++) mapper.insertTurn(id, i + 1, retained.get(i));
                mapper.advance(id, now.plusHours(24));
            });
            return turn.response();
        } catch (RuntimeException error) {
            // 只释放本次资格；清空或接管后的新请求不受影响。清理失败保留为原错误的附加诊断。
            try { transaction.executeWithoutResult(status -> mapper.release(id, ticket.token())); }
            catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    public void clear(long userId, String id) {
        purgeExpired(userId);
        transaction.executeWithoutResult(status -> {
            var now = LocalDateTime.now();
            owned(userId, id, now);
            mapper.deleteTurns(id);
            mapper.advance(id, now.plusHours(24));
        });
    }

    private void purgeExpired(long userId) {
        // 独立提交清理，随后404异常不会回滚过期数据的删除。
        transaction.executeWithoutResult(status -> mapper.deleteExpired(userId, LocalDateTime.now()));
    }

    private AgentConversation owned(long userId, String id, LocalDateTime now) {
        var conversation = mapper.lockOwned(id, userId);
        if (conversation == null || !conversation.expiresAt().isAfter(now))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或已过期");
        return conversation;
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    public record CreatedConversation(String conversationId, LocalDateTime expiresAt) { }
    private record Ticket(long revision, String token, List<String> turns) { }
}
