package com.elaina.limitedtrade.agent;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

final class AgentUsageLedger {
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PRICE_VERSION = "deepseek-flash-2026-11-23-peak-miss";
    // 元/百万 Token 与微元/Token 的数值相同。
    private static final int INPUT_RATE = 2;
    private static final int OUTPUT_RATE = 8;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AgentBudgetProperties policy;
    private final long monthlyMicros;
    private final AgentProperties properties;

    AgentUsageLedger(JdbcTemplate jdbc, PlatformTransactionManager manager, AgentProperties properties) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.properties = properties;
        this.policy = properties.budget();
        this.monthlyMicros = policy.monthlyYuan().movePointRight(6).longValueExact();
    }

    Request begin(long userId) {
        if (!"deepseek-flash".equals(properties.model())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "当前模型没有已配置的费用版本");
        }
        Request request = transaction.execute(status -> {
            lockBudget();
            var now = LocalDateTime.now(BILLING_ZONE);
            recoverExpired(now);
            long daily = count("SELECT COUNT(*) FROM agent_budget_request WHERE user_id=? AND quota_day=?",
                    userId, now.toLocalDate());
            long userOpen = count("SELECT COUNT(*) FROM agent_budget_request WHERE state='OPEN' AND user_id=?", userId);
            long totalOpen = count("SELECT COUNT(*) FROM agent_budget_request WHERE state='OPEN'");
            if (daily >= policy.dailyQuestionsPerUser() || userOpen >= policy.concurrentPerUser()
                    || totalOpen >= policy.concurrentGlobal()) {
                return null;
            }
            String id = UUID.randomUUID().toString();
            Duration lease = properties.requestTimeout().plusSeconds(30);
            jdbc.update("""
                    INSERT INTO agent_budget_request(id,user_id,quota_day,state,lease_until)
                    VALUES (?,?,?,'OPEN',?)
                    """, id, userId, now.toLocalDate(), now.plus(lease));
            return new Request(id);
        });
        if (request == null) throw exhausted("已达到助手提问或并发额度");
        return request;
    }

    private void recoverExpired(LocalDateTime now) {
        // 进程中断后无法证明未出站，保留未结算调用的资金预留。
        jdbc.update("""
                UPDATE agent_budget_call c JOIN agent_budget_request r ON r.id=c.request_id
                SET c.state='UNKNOWN' WHERE c.state='RESERVED' AND r.state='OPEN' AND r.lease_until<=?
                """, now);
        jdbc.update("UPDATE agent_budget_request SET state='EXPIRED' WHERE state='OPEN' AND lease_until<=?", now);
    }

    private void lockBudget() {
        jdbc.queryForObject("SELECT id FROM agent_budget_gate WHERE id=1 FOR UPDATE", Integer.class);
    }

    private void writeState(Runnable mutation) {
        // SSE 取消会中断线程，先记录账务状态，再恢复中断标志。
        boolean interrupted = Thread.interrupted();
        try {
            transaction.executeWithoutResult(status -> {
                lockBudget();
                mutation.run();
            });
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private ResponseStatusException exhausted(String reason) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, reason);
    }

    final class Request implements AutoCloseable {
        private final String id;

        private Request(String id) { this.id = id; }

        Call reserve(int estimatedInput) {
            return transaction.execute(status -> {
                lockBudget();
                var now = LocalDateTime.now(BILLING_ZONE);
                if (count("SELECT COUNT(*) FROM agent_budget_request WHERE id=? AND state='OPEN' AND lease_until>?",
                        id, now) != 1) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "助手预算资格已过期");
                }
                LocalDate month = now.toLocalDate().withDayOfMonth(1);
                long held = count("""
                        SELECT COALESCE(SUM(COALESCE(charged_micros,reserved_micros)),0)
                        FROM agent_budget_call WHERE billing_month=?
                        """, month);
                int maxOutput = properties.maxOutputTokens();
                long ceiling = (long) (policy.contextTokens() - maxOutput) * INPUT_RATE + (long) maxOutput * OUTPUT_RATE;
                if (ceiling > monthlyMicros - held) throw exhausted("本月助手费用预算余额不足");
                String callId = UUID.randomUUID().toString();
                jdbc.update("""
                        INSERT INTO agent_budget_call(id,request_id,billing_month,state,model,price_version,input_rate,
                            output_rate,estimated_input_tokens,max_output_tokens,reserved_micros)
                        VALUES (?,?,?,'RESERVED',?,?,?,?,?,?,?)
                        """, callId, id, month, properties.model(), PRICE_VERSION, INPUT_RATE,
                        OUTPUT_RATE, estimatedInput, maxOutput, ceiling);
                return new Call(callId);
            });
        }

        @Override public void close() {
            writeState(() -> jdbc.update(
                    "UPDATE agent_budget_request SET state='CLOSED' WHERE id=? AND state='OPEN'", id));
        }
    }

    final class Call implements AutoCloseable {
        private final String id;

        private Call(String id) { this.id = id; }

        void settle(int input, int output, long total) {
            // 使用该调用保存的价格，允许补记迟到的可信 usage，重复结算不会改变已结算行。
            writeState(() -> jdbc.update("""
                        UPDATE agent_budget_call SET state='SETTLED',actual_input_tokens=?,actual_output_tokens=?,
                            actual_total_tokens=?,charged_micros=? * input_rate + ? * output_rate
                        WHERE id=? AND state IN ('RESERVED','UNKNOWN')
                        """, input, output, total, (long) input, (long) output, id));
        }

        void release() {
            writeState(() -> jdbc.update("""
                    UPDATE agent_budget_call SET state='RELEASED',charged_micros=0 WHERE id=? AND state='RESERVED'
                    """, id));
        }

        @Override public void close() {
            writeState(() -> jdbc.update(
                    "UPDATE agent_budget_call SET state='UNKNOWN' WHERE id=? AND state='RESERVED'", id));
        }
    }
}
