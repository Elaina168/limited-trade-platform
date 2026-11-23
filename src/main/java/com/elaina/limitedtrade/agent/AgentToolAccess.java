package com.elaina.limitedtrade.agent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class AgentToolAccess {
    private static final String KEY = AgentToolAccess.class.getName();
    private static final Set<String> READ_ACTIONS = Set.of(
            "list_my_orders", "get_my_order", "get_trade_rules", "search_trade_rules");
    private static final org.slf4j.Logger audit = LoggerFactory.getLogger(AgentToolAccess.class);

    private AgentToolAccess() { }

    static Scope issue(long userId) { return new Scope(userId); }

    static boolean allows(String action) { return READ_ACTIONS.contains(action); }

    static long owner(ToolContext context, String action) {
        if (context != null && context.getContext().get(KEY) instanceof Scope scope) return scope.userId;
        record(null, action, 403, "missing_context");
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "工具缺少服务端授权上下文");
    }

    static void record(Scope scope, String action, int status, String reason) {
        // 动作与原因均为受控值。模型给出的未知名称和内容不进入日志。
        audit.atInfo().addKeyValue("event", "agent_tool")
                .addKeyValue("agentRequestId", scope == null ? "unbound" : scope.auditId)
                .addKeyValue("tool", allows(action) ? action : "unregistered")
                .addKeyValue("status", status).addKeyValue("reason", reason)
                .log("助手工具授权与调用");
    }

    static final class Scope {
        private final Long userId;
        private final String auditId = UUID.randomUUID().toString();

        private Scope(long userId) { this.userId = userId; }

        Map<String, Object> values() { return Map.of(KEY, this); }

        boolean matches(ToolContext context) { return context.getContext().get(KEY) == this; }

        String auditId() { return auditId; }

        @Override public String toString() { return "AgentToolScope"; }
    }
}
