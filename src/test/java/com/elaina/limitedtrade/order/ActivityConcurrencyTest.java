package com.elaina.limitedtrade.order;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

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
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class ActivityConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private boolean fixtureOwned;

    @BeforeEach
    void prepareCommittedFixtures() {
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE id BETWEEN 7601 AND 7620", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product WHERE id=9601", Integer.class));
        fixtureOwned = true;
        for (int index = 0; index < 20; index++) {
            jdbcTemplate.update("INSERT INTO app_user (id,username,password_hash,role) VALUES (?,?,'unused-test-hash','USER')",
                    7601 + index, "activity_parallel_" + index);
        }
        jdbcTemplate.update("INSERT INTO product (id,name,price,stock,status) VALUES (9601,'活动竞争商品',20,20,'ON_SALE')");
        jdbcTemplate.update("""
                INSERT INTO activity (id,product_id,name,total_quantity,remaining_quantity,starts_at,ends_at)
                VALUES (9601,9601,'活动竞争',5,5,DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 HOUR),
                        DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 HOUR))
                """);
    }

    @AfterEach
    void removeOwnedFixtures() {
        if (fixtureOwned) {
            jdbcTemplate.update("DELETE FROM trade_order WHERE product_id=9601");
            jdbcTemplate.update("DELETE FROM activity WHERE id=9601");
            jdbcTemplate.update("DELETE FROM product WHERE id=9601");
            jdbcTemplate.update("DELETE FROM app_user WHERE id BETWEEN 7601 AND 7620");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 3})
    void differentBuyersCannotExceedQuotaOrProductStock(int initialStock) throws Exception {
        jdbcTemplate.update("UPDATE product SET stock=? WHERE id=9601", initialStock);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            long userId = 7601L + index;
            tasks.add(() -> {
                try {
                    orderService.createOrder(userId, new CreateOrderRequest(9601L, 1, "buy-" + userId, 9601L));
                    return 201;
                } catch (ResponseStatusException failure) {
                    assertEquals(409, failure.getStatusCode().value());
                    return 409;
                }
            });
        }
        var executor = Executors.newFixedThreadPool(10);
        int successful = 0;
        try {
            for (var future : executor.invokeAll(tasks)) {
                if (future.get(10, TimeUnit.SECONDS) == 201) { successful++; }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        int expected = initialStock == 20 ? 5 : 3;
        assertEquals(expected, successful);
        assertEquals(expected, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE activity_id=9601", Integer.class));
        assertEquals(initialStock - expected, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9601", Integer.class));
        assertEquals(5 - expected, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9601", Integer.class));
    }

    @Test
    void sameRequestInParallelConsumesOneQuota() throws Exception {
        List<Callable<CreateOrderResult>> tasks = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            tasks.add(() -> orderService.createOrder(7601, new CreateOrderRequest(9601L, 1, "same-key", 9601L)));
        }
        var executor = Executors.newFixedThreadPool(10);
        int created = 0;
        try {
            for (var future : executor.invokeAll(tasks)) {
                if (future.get(10, TimeUnit.SECONDS).created()) { created++; }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, created);
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE activity_id=9601", Integer.class));
        assertEquals(19, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9601", Integer.class));
        assertEquals(4, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9601", Integer.class));
    }

    @Test
    void concurrentCancelRestoresBothOnce() throws Exception {
        long id = orderService.createOrder(7601, new CreateOrderRequest(9601L, 2, "cancel", 9601L)).order().getId();
        List<Callable<TradeOrder>> tasks = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            tasks.add(() -> orderService.cancelOrder(7601, id));
        }
        var executor = Executors.newFixedThreadPool(5);
        try {
            for (var future : executor.invokeAll(tasks)) {
                assertEquals("CANCELED", future.get(10, TimeUnit.SECONDS).getStatus());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(20, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9601", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9601", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void stockSqlFailureRollsBackStatusAndQuota(boolean close) {
        long id = orderService.createOrder(7601, new CreateOrderRequest(9601L, 2, "failure", 9601L)).order().getId();
        LocalDateTime cutoff = LocalDateTime.now().withNano(0);
        jdbcTemplate.update("UPDATE trade_order SET expires_at=? WHERE id=?", cutoff, id);
        // 故意构造库存溢出，验证已经归还的活动名额和已改状态也回滚。
        jdbcTemplate.update("UPDATE product SET stock=2147483647 WHERE id=9601");
        assertThrows(DataAccessException.class, () -> {
            if (close) { orderService.closeExpiredOrder(id, cutoff); }
            else { orderService.cancelOrder(7601, id); }
        });
        assertEquals("PENDING_PAYMENT", jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, id));
        assertTrue(jdbcTemplate.queryForObject("SELECT ended_at IS NULL FROM trade_order WHERE id=?", Boolean.class, id));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9601", Integer.class));
    }

    @Test
    void cancellationRacingClosureReturnsBothOnce() throws Exception {
        long id = orderService.createOrder(7601, new CreateOrderRequest(9601L, 2, "race", 9601L)).order().getId();
        LocalDateTime cutoff = LocalDateTime.now().withNano(0);
        jdbcTemplate.update("UPDATE trade_order SET expires_at=? WHERE id=?", cutoff, id);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var cancel = executor.submit(() -> {
                start.await();
                try { orderService.cancelOrder(7601, id); }
                catch (ResponseStatusException failure) { assertEquals(409, failure.getStatusCode().value()); }
                return null;
            });
            var closure = executor.submit(() -> {
                start.await();
                return orderService.closeExpiredOrder(id, cutoff);
            });
            start.countDown();
            cancel.get(10, TimeUnit.SECONDS);
            closure.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        String status = jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, id);
        assertTrue(List.of("CANCELED", "CLOSED").contains(status));
        assertEquals(20, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9601", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9601", Integer.class));
    }

    @Test
    void productLockWaitCannotAdmitOrderAfterActivityEnds() throws Exception {
        LocalDateTime deadline = LocalDateTime.now().withNano(0).plusSeconds(4);
        jdbcTemplate.update("UPDATE activity SET ends_at=? WHERE id=9601", deadline);
        var executor = Executors.newSingleThreadExecutor();
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM product WHERE id=9601 FOR UPDATE")) {
                statement.executeQuery().close();
            }
            var order = executor.submit(() -> assertThrows(ResponseStatusException.class,
                    () -> orderService.createOrder(7601, new CreateOrderRequest(9601L, 1, "lock-wait", 9601L))));
            // 用第三条连接的NOWAIT观察活动锁，确认请求确实已先锁活动、正在等待商品。
            boolean activityLocked = false;
            long observationLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!activityLocked && System.nanoTime() < observationLimit) {
                try (var observer = dataSource.getConnection()) {
                    observer.setAutoCommit(false);
                    try (var statement = observer.prepareStatement("SELECT id FROM activity WHERE id=9601 FOR UPDATE NOWAIT")) {
                        statement.executeQuery().close();
                    } catch (SQLException failure) {
                        if (failure.getErrorCode() != 3572) { throw failure; }
                        activityLocked = true;
                    } finally {
                        observer.rollback();
                    }
                }
                if (!activityLocked) { Thread.sleep(10); }
            }
            assertTrue(activityLocked, "下单请求应已锁活动并等待商品锁");
            assertTrue(LocalDateTime.now().isBefore(deadline), "确认请求开始等待时活动尚未结束");
            Thread.sleep(Math.max(1, Duration.between(LocalDateTime.now(), deadline.plusSeconds(1)).toMillis()));
            blocker.commit();
            assertEquals(409, order.get(10, TimeUnit.SECONDS).getStatusCode().value());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(20, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9601", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9601", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE activity_id=9601", Integer.class));
    }
}
