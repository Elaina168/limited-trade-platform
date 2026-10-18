package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.elaina.limitedtrade.order.message.OrderTimeoutMessage;
import com.elaina.limitedtrade.order.message.OrderTimeoutMessageSender;
import com.elaina.limitedtrade.order.message.OrderTimeoutOutboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
@ActiveProfiles("test")
class OrderTimeoutPublisherIntegrationTest {
    @Autowired private OrderService orders;
    @Autowired private OrderTimeoutOutboxService publisher;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private OrderTimeoutMessageSender sender;
    private boolean owned;
    private long eventId;
    private long orderId;

    @BeforeEach
    void prepare() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=7802", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=9802", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='test_outbox_sent_reject_9802'", Integer.class));
        owned = true;
        jdbc.update("INSERT INTO app_user (id,username,password_hash,role) VALUES (7802,'publisher_buyer','unused','USER')");
        jdbc.update("INSERT INTO product (id,name,price,stock,status) VALUES (9802,'投递测试商品',20,10,'ON_SALE')");
        orderId = orders.createOrder(7802, new CreateOrderRequest(9802L, 1, "publisher", null)).order().getId();
        eventId = jdbc.queryForObject("SELECT id FROM order_timeout_outbox WHERE order_id=?", Long.class, orderId);
    }

    @AfterEach
    void cleanup() {
        if (owned) {
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT id FROM trade_order WHERE product_id=9802)");
            jdbc.update("DELETE FROM trade_order WHERE product_id=9802");
            jdbc.update("DELETE FROM product WHERE id=9802");
            jdbc.update("DELETE FROM app_user WHERE id=7802");
        }
    }

    @Test
    void confirmationMarksSentAndSecondWorkerDoesNotSendAgain() {
        assertTrue(publisher.publishOne(eventId));
        var event = jdbc.queryForMap("SELECT * FROM order_timeout_outbox WHERE id=?", eventId);
        assertEquals("SENT", event.get("status"));
        assertEquals(1L, ((Number) event.get("attempt_count")).longValue());
        assertNotNull(event.get("sent_at"));
        assertNull(event.get("last_error"));
        assertFalse(publisher.publishOne(eventId));
        verify(sender).send(new OrderTimeoutMessage(eventId, orderId,
                jdbc.queryForObject("SELECT expires_at FROM trade_order WHERE id=?", LocalDateTime.class, orderId)));
    }

    @Test
    void failuresRemainPendingAndBackoffCapsAtSixtySeconds() {
        doThrow(new AmqpException("x".repeat(600))).when(sender).send(any());
        for (int delay : new int[]{5, 10, 20, 40, 60, 60}) {
            jdbc.update("UPDATE order_timeout_outbox SET next_attempt_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?", eventId);
            var before = LocalDateTime.now().withNano(0);
            assertFalse(publisher.publishOne(eventId));
            var next = jdbc.queryForObject("SELECT next_attempt_at FROM order_timeout_outbox WHERE id=?", LocalDateTime.class, eventId);
            assertFalse(next.isBefore(before.plusSeconds(delay)));
            assertFalse(next.isAfter(LocalDateTime.now().plusSeconds(delay)));
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM order_timeout_outbox WHERE id=?", String.class, eventId));
            assertEquals(500, jdbc.queryForObject("SELECT CHAR_LENGTH(last_error) FROM order_timeout_outbox WHERE id=?", Integer.class, eventId));
        }
        assertEquals(6L, jdbc.queryForObject("SELECT attempt_count FROM order_timeout_outbox WHERE id=?", Long.class, eventId));
        assertFalse(publisher.publishOne(eventId));
        verify(sender, times(6)).send(any());
        reset(sender);
        jdbc.update("UPDATE order_timeout_outbox SET next_attempt_at=NOW() WHERE id=?", eventId);
        assertTrue(publisher.publishOne(eventId));
        assertNull(jdbc.queryForObject("SELECT last_error FROM order_timeout_outbox WHERE id=?", String.class, eventId));
    }

    @Test
    void concurrentWorkerSkipsLockedEventWhileNetworkCallIsInProgress() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(sender).send(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> publisher.publishOne(eventId));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(pool.submit(() -> publisher.publishOne(eventId)).get(2, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertTrue(first.get(5, TimeUnit.SECONDS));
        }
        verify(sender).send(any());
    }

    @Test
    void databaseFailureAfterRemoteAckRollsBackAndAllowsSameEventToBeSentAgain() {
        jdbc.execute("""
                CREATE TRIGGER test_outbox_sent_reject_9802 BEFORE UPDATE ON order_timeout_outbox
                FOR EACH ROW BEGIN
                    IF NEW.id=%d AND NEW.status='SENT' THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='sent fixture rejection';
                    END IF;
                END
                """.formatted(eventId));
        try {
            assertThrows(DataAccessException.class, () -> publisher.publishOne(eventId));
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM order_timeout_outbox WHERE id=?", String.class, eventId));
            assertEquals(0L, jdbc.queryForObject("SELECT attempt_count FROM order_timeout_outbox WHERE id=?", Long.class, eventId));
        } finally {
            jdbc.execute("DROP TRIGGER test_outbox_sent_reject_9802");
        }
        assertTrue(publisher.publishOne(eventId));
        verify(sender, times(2)).send(any());
    }

    @Test
    void interruptionPropagatesWithoutRecordingACompletedAttempt() {
        doThrow(new IllegalStateException("interrupted")).when(sender).send(any());
        assertThrows(IllegalStateException.class, () -> publisher.publishOne(eventId));
        assertEquals(0L, jdbc.queryForObject("SELECT attempt_count FROM order_timeout_outbox WHERE id=?", Long.class, eventId));
    }
}
