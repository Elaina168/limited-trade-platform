package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/order-action-test-data.sql")
class OrderTimeoutTest {

    @Autowired
    private OrderTimeoutScheduler scheduler;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void scanClosesExpiredOrderAndReturnsPurchasedQuantity() {
        jdbcTemplate.update("UPDATE trade_order SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 MINUTE) WHERE id=9201");
        scheduler.closeExpiredOrders();
        assertEquals("CLOSED", jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=9201", String.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
        assertEquals(40, jdbcTemplate.queryForObject("SELECT total_amount FROM trade_order WHERE id=9201", Integer.class));
        assertNull(jdbcTemplate.queryForObject("SELECT paid_at FROM trade_order WHERE id=9201", LocalDateTime.class));
        assertTrue(jdbcTemplate.queryForObject("SELECT ended_at IS NOT NULL FROM trade_order WHERE id=9201", Boolean.class));
    }

    @Test
    void beforeDeadlineDoesNotCloseButExactDeadlineDoes() {
        LocalDateTime deadline = LocalDateTime.of(2026, 10, 16, 12, 0);
        jdbcTemplate.update("UPDATE trade_order SET expires_at=? WHERE id=9201", deadline);
        assertFalse(orderService.closeExpiredOrder(9201, deadline.minusSeconds(1)));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
        assertTrue(orderService.closeExpiredOrder(9201, deadline));
        assertEquals(deadline, jdbcTemplate.queryForObject("SELECT ended_at FROM trade_order WHERE id=9201", LocalDateTime.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void repeatedClosureKeepsOriginalEndTimeAndReturnsStockOnce() {
        LocalDateTime cutoff = LocalDateTime.now().withNano(0);
        jdbcTemplate.update("UPDATE trade_order SET expires_at=? WHERE id=9201", cutoff);
        assertTrue(orderService.closeExpiredOrder(9201, cutoff));
        assertFalse(orderService.closeExpiredOrder(9201, cutoff.plusMinutes(1)));
        assertEquals(cutoff, jdbcTemplate.queryForObject("SELECT ended_at FROM trade_order WHERE id=9201", LocalDateTime.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void staleCandidateCannotClosePaidOrder() {
        orderService.payOrder(7201, 9201);
        assertFalse(orderService.closeExpiredOrder(9201, LocalDateTime.now().plusHours(1)));
        assertEquals("PAID", jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=9201", String.class));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void staleCandidateCannotRestoreCanceledOrderAgain() {
        orderService.cancelOrder(7201, 9201);
        assertFalse(orderService.closeExpiredOrder(9201, LocalDateTime.now().plusHours(1)));
        assertEquals("CANCELED", jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=9201", String.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void scanLeavesUnexpiredOrderPending() {
        scheduler.closeExpiredOrders();
        assertEquals("PENDING_PAYMENT", jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=9201", String.class));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void scanProcessesBoundedBatchAndNextScanFinishesRemainder() {
        jdbcTemplate.update("UPDATE trade_order SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 MINUTE) WHERE id=9201");
        // 连同原订单共101单；额外100单每单占用1件，当前可售库存设为0。
        jdbcTemplate.update("UPDATE product SET stock=0 WHERE id=9201");
        for (int index = 0; index < 100; index++) {
            jdbcTemplate.update("""
                    INSERT INTO trade_order (user_id,product_id,product_name,unit_price,quantity,total_amount,
                                             status,request_id,created_at,expires_at)
                    VALUES (7201,9201,'批量关闭商品',20,1,20,'PENDING_PAYMENT',?,
                            DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 16 MINUTE),DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 MINUTE))
                    """, "timeout-batch-" + index);
        }
        scheduler.closeExpiredOrders();
        assertEquals(100, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE status='CLOSED'", Integer.class));
        scheduler.closeExpiredOrders();
        assertEquals(101, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE status='CLOSED'", Integer.class));
        assertEquals(102, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }
}
