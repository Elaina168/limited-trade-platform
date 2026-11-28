package com.elaina.cloud.order;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class OrderOutbox {
    enum Event { TIMEOUT, RELEASE, VERIFY }
    record Work(long orderId, Event event, Instant dueAt, String traceId) {}

    private static final RowMapper<Work> ROW = (rs, n) -> new Work(rs.getLong("order_id"),
        Event.valueOf(rs.getString("event_type")), rs.getTimestamp("due_at").toInstant(), rs.getString("trace_id"));
    private final JdbcTemplate jdbc;
    private final long rollbackProofMs;

    OrderOutbox(JdbcTemplate jdbc, @Value("${lab.order.rollback-proof-ms:12000}") long rollbackProofMs) {
        this.jdbc = jdbc;
        this.rollbackProofMs = rollbackProofMs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void watchRollback(long id, Instant openedAt) {
        // 远程操作前独立提交反查工作，覆盖本地事务回滚或 JVM 中断。
        Instant due = openedAt.plusMillis(rollbackProofMs);
        enqueue(id, Event.VERIFY, due, due);
    }

    void enqueue(long id, Event type, Instant due, Instant nextAttempt) {
        jdbc.update("""
            INSERT INTO lab_order_outbox (order_id,event_type,due_at,next_attempt_at,trace_id)
            VALUES (?,?,?,?,?)
            """, id, type.name(), Timestamp.from(due), Timestamp.from(nextAttempt), MDC.get(TraceContextFilter.KEY));
    }

    List<Work> publishable(Instant now) {
        return jdbc.query("""
            SELECT order_id,event_type,due_at,trace_id FROM lab_order_outbox
            WHERE event_type='TIMEOUT' AND status='PENDING' AND next_attempt_at<=? AND due_at>?
            ORDER BY next_attempt_at,order_id LIMIT 16
            """, ROW, Timestamp.from(now), Timestamp.from(now));
    }

    List<Work> due(Instant now, Instant fallbackBefore) {
        return jdbc.query("""
            SELECT order_id,event_type,due_at,trace_id FROM lab_order_outbox
            WHERE next_attempt_at<=? AND (
                (event_type IN ('RELEASE','VERIFY') AND status='PENDING' AND due_at<=?)
                OR (event_type='TIMEOUT' AND status IN ('PENDING','PUBLISHED') AND due_at<=?)
            )
            ORDER BY due_at,order_id LIMIT 16
            """, ROW, Timestamp.from(now), Timestamp.from(now), Timestamp.from(fallbackBefore));
    }

    void published(long orderId) {
        jdbc.update("UPDATE lab_order_outbox SET status='PUBLISHED' WHERE order_id=? AND event_type='TIMEOUT' AND status='PENDING'", orderId);
    }

    void done(Work work) {
        jdbc.update("""
            UPDATE lab_order_outbox SET status='DONE' WHERE order_id=? AND event_type=? AND status<>'DONE'
            """, work.orderId(), work.event().name());
    }

    void doneTimeout(long orderId, Instant now) {
        jdbc.update("""
            UPDATE lab_order_outbox SET status='DONE'
            WHERE order_id=? AND event_type='TIMEOUT' AND due_at<=? AND status<>'DONE'
            """, orderId, Timestamp.from(now));
    }

    void retry(Work work, Instant nextAttempt) {
        jdbc.update("""
            UPDATE lab_order_outbox SET next_attempt_at=? WHERE order_id=? AND event_type=? AND status<>'DONE'
            """, Timestamp.from(nextAttempt), work.orderId(), work.event().name());
    }
}
