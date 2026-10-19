package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class OrderTimeoutOutboxIntegrationTest {

    @Autowired private OrderService orders;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private boolean fixtureOwned;

    @BeforeEach
    void prepareCommittedFixtures() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=7801", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=9801", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='test_order_outbox_reject_9801'", Integer.class));
        fixtureOwned = true;
        jdbc.update("INSERT INTO app_user (id,username,password_hash,role) VALUES (7801,'outbox_buyer','unused-test-hash','USER')");
        jdbc.update("INSERT INTO product (id,name,price,stock,status) VALUES (9801,'消息测试商品',20.00,10,'ON_SALE')");
        jdbc.update("""
                INSERT INTO activity (id,product_id,name,total_quantity,remaining_quantity,starts_at,ends_at)
                VALUES (9801,9801,'消息测试活动',5,5,DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 HOUR),
                        DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 HOUR))
                """);
    }

    @AfterEach
    void removeOwnedFixtures() {
        if (fixtureOwned) {
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT order_id FROM order_item WHERE product_id=9801)");
            jdbc.update("DELETE FROM trade_order WHERE id IN (SELECT order_id FROM order_item WHERE product_id=9801)");
            jdbc.update("DELETE FROM activity WHERE id=9801");
            jdbc.update("DELETE FROM product WHERE id=9801");
            jdbc.update("DELETE FROM app_user WHERE id=7801");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstOrderRegistersOnePendingEventAndRetryDoesNotDuplicate(boolean activity) {
        var request = new CreateOrderRequest(9801L, 2, "outbox-create", activity ? 9801L : null);
        var first = orders.createOrder(7801, request);
        assertTrue(first.created());
        long orderId = first.order().getId();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox WHERE order_id=?", Integer.class, orderId));
        var event = jdbc.queryForMap("SELECT * FROM order_timeout_outbox WHERE order_id=?", orderId);
        assertEquals("PENDING", event.get("status"));
        assertEquals(0L, ((Number) event.get("attempt_count")).longValue());
        assertNull(event.get("sent_at"));
        assertEquals(first.order().getExpiresAt(), jdbc.queryForObject("SELECT expires_at FROM order_timeout_outbox WHERE order_id=?", LocalDateTime.class, orderId));
        var retry = orders.createOrder(7801, request);
        assertFalse(retry.created());
        assertEquals(orderId, retry.order().getId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox WHERE order_id=?", Integer.class, orderId));
    }

    @Test
    void realEventInsertFailureRollsBackOrderStockAndQuota() {
        // 仅针对本类自有用户拒绝事件插入，真实触发Service事务回滚。
        jdbc.execute("""
                CREATE TRIGGER test_order_outbox_reject_9801 BEFORE INSERT ON order_timeout_outbox
                FOR EACH ROW BEGIN
                    IF (SELECT user_id FROM trade_order WHERE id=NEW.order_id)=7801 THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='outbox fixture rejection';
                    END IF;
                END
                """);
        try {
            assertThrows(DataAccessException.class, () -> orders.createOrder(7801,
                    new CreateOrderRequest(9801L, 2, "outbox-rollback", 9801L)));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE user_id=7801", Integer.class));
            assertEquals(10, jdbc.queryForObject("SELECT stock FROM product WHERE id=9801", Integer.class));
            assertEquals(5, jdbc.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9801", Integer.class));
        } finally {
            jdbc.execute("DROP TRIGGER test_order_outbox_reject_9801");
        }
    }

    @Test
    void outerTransactionRollbackLeavesNoOrderOrEvent() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            orders.createOrder(7801, new CreateOrderRequest(9801L, 2, "outer-rollback", 9801L));
            tx.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE user_id=7801", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox", Integer.class));
        assertEquals(10, jdbc.queryForObject("SELECT stock FROM product WHERE id=9801", Integer.class));
    }

    @Test
    void afterCommitFailureKeepsCommittedOrderAndEventForIdempotentRetry() {
        var request = new CreateOrderRequest(9801L, 2, "after-commit", null);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            orders.createOrder(7801, request);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    throw new IllegalStateException("模拟提交后外部操作失败");
                }
            });
        }));
        var retry = orders.createOrder(7801, request);
        assertFalse(retry.created());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox WHERE order_id=?", Integer.class, retry.order().getId()));
        assertEquals(8, jdbc.queryForObject("SELECT stock FROM product WHERE id=9801", Integer.class));
    }
}
