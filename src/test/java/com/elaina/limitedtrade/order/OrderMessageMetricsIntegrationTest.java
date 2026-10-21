package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;
import com.elaina.limitedtrade.observability.OrderMessageMetricsMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OrderMessageMetricsIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderMessageMetricsMapper mapper;

    @Test void realSqlCountsPendingRetriesAndRecordedCloseDelays() {
        assertEquals("limited_trade_platform_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
        LocalDateTime now = LocalDateTime.of(2026,10,21,12,0);
        var empty = mapper.snapshot(now.minusSeconds(1));
        assertEquals(0, empty.outboxPending());
        assertEquals(0, empty.oldestOverdueSeconds());
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (7905,'message_metrics_fixture','unused','USER')");
        jdbc.update("""
                INSERT INTO trade_order(id,user_id,total_amount,status,request_id,created_at,expires_at,ended_at) VALUES
                (99051,7905,20,'PENDING_PAYMENT','metrics-1',?, ?,NULL),
                (99052,7905,20,'PENDING_PAYMENT','metrics-2',?, ?,NULL),
                (99053,7905,20,'CLOSED','metrics-3',?, ?,?),
                (99054,7905,20,'PAID','metrics-4',?, ?,NULL)
                """, now.minusMinutes(20),now.minusSeconds(90),now.minusMinutes(5),now.plusMinutes(10),
                now.minusMinutes(30),now.minusMinutes(10),now.minusMinutes(8),now.minusMinutes(30),now.minusMinutes(10));
        jdbc.update("""
                INSERT INTO order_timeout_outbox(order_id,expires_at,status,attempt_count,next_attempt_at,created_at,sent_at)
                VALUES (99051,?,'PENDING',2,?,?,NULL),(99052,?,'PENDING',0,?,?,NULL),(99053,?,'SENT',3,?,?,?)
                """, now.minusSeconds(90),now.plusSeconds(30),now.minusMinutes(3),
                now.plusMinutes(10),now,now.minusSeconds(20),
                now.minusMinutes(10),now.minusMinutes(8),now.minusMinutes(30),now.minusMinutes(8));
        var stats = mapper.snapshot(now);
        assertEquals(2, stats.outboxPending());
        assertEquals(1, stats.outboxRetrying());
        assertEquals(4, stats.storedFailedAttempts());
        assertEquals(180, stats.oldestPendingSeconds());
        assertEquals(1, stats.overdueOrders());
        assertEquals(90, stats.oldestOverdueSeconds());
        assertEquals(1, stats.closedLastHour());
        assertEquals(120, stats.closedLastHourMaxDelaySeconds());
        assertEquals(0, mapper.snapshot(now.plusHours(2)).closedLastHour());
    }
}
