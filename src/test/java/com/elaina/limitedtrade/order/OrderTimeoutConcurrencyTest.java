package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class OrderTimeoutConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderTimeoutScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private boolean fixtureOwned;

    @BeforeEach
    void prepareCommittedExpiredOrder() {
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=8951", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product WHERE id=8951", Integer.class));
        fixtureOwned = true;
        jdbcTemplate.update("INSERT INTO app_user (id,username,password_hash,role) VALUES (8951,'timeout_buyer','unused-test-hash','USER')");
        jdbcTemplate.update("INSERT INTO product (id,name,price,stock,status) VALUES (8951,'超时测试商品',20,3,'ON_SALE')");
        jdbcTemplate.update("""
                INSERT INTO trade_order (id,user_id,total_amount,
                                         status,request_id,created_at,expires_at)
                VALUES (8951,8951,40,'PENDING_PAYMENT','timeout-race',
                        DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 16 MINUTE),DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 MINUTE))
                """);
        jdbcTemplate.update("INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity,line_amount) VALUES (8951,8951,'超时测试商品',20,2,40)");
    }

    @AfterEach
    void removeOwnedFixtures() {
        if (fixtureOwned) {
            jdbcTemplate.update("DELETE FROM trade_order WHERE id=8951");
            jdbcTemplate.update("DELETE FROM product WHERE id=8951");
            jdbcTemplate.update("DELETE FROM app_user WHERE id=8951");
        }
    }

    @Test
    void concurrentScansReturnStockOnlyOnce() throws Exception {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            tasks.add(() -> { scheduler.closeExpiredOrders(); return null; });
        }
        var executor = Executors.newFixedThreadPool(5);
        try {
            for (var future : executor.invokeAll(tasks)) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals("CLOSED", jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=8951", String.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=8951", Integer.class));
    }

    @Test
    void expiredPaymentAndClosureRaceEndsClosed() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var payment = executor.submit(() -> {
                start.await();
                ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                        () -> orderService.payOrder(8951, 8951));
                assertEquals(409, failure.getStatusCode().value());
                return null;
            });
            var closure = executor.submit(() -> {
                start.await();
                scheduler.closeExpiredOrders();
                return null;
            });
            start.countDown();
            payment.get(10, TimeUnit.SECONDS);
            closure.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals("CLOSED", jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=8951", String.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=8951", Integer.class));
    }

    @Test
    void cancellationAndClosureRaceRestoreOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var cancellation = executor.submit(() -> {
                start.await();
                try {
                    orderService.cancelOrder(8951, 8951);
                } catch (ResponseStatusException failure) {
                    assertEquals(409, failure.getStatusCode().value());
                }
                return null;
            });
            var closure = executor.submit(() -> {
                start.await();
                scheduler.closeExpiredOrders();
                return null;
            });
            start.countDown();
            cancellation.get(10, TimeUnit.SECONDS);
            closure.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        String status = jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=8951", String.class);
        assertTrue(List.of("CLOSED", "CANCELED").contains(status));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=8951", Integer.class));
    }

    @Test
    void failedStockRestorationRollsBackClosure() {
        // 故意构造 INT 溢出，让真实库存 SQL 失败，验证外部调用确实开启了事务。
        jdbcTemplate.update("UPDATE product SET stock=2147483647 WHERE id=8951");
        assertThrows(DataAccessException.class,
                () -> orderService.closeExpiredOrder(8951, LocalDateTime.now().withNano(0)));
        assertEquals("PENDING_PAYMENT", jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=8951", String.class));
        assertTrue(jdbcTemplate.queryForObject("SELECT ended_at IS NULL FROM trade_order WHERE id=8951", Boolean.class));
        assertEquals(2147483647, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=8951", Integer.class));
        // 修复本测试故意制造的数据后，下一轮能够重试成功。
        jdbcTemplate.update("UPDATE product SET stock=3 WHERE id=8951");
        scheduler.closeExpiredOrders();
        assertEquals("CLOSED", jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=8951", String.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=8951", Integer.class));
    }
}
