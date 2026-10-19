package com.elaina.limitedtrade.order;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.user.AppUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderActionConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private boolean fixtureOwned;

    @BeforeEach
    void prepareCommittedOrder() {
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=8901", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product WHERE id=8901", Integer.class));
        fixtureOwned = true;
        jdbcTemplate.update("""
                INSERT INTO app_user (id,username,password_hash,role)
                VALUES (8901,'action_parallel_buyer','unused-test-hash','USER')
                """);
        jdbcTemplate.update("""
                INSERT INTO product (id,name,price,stock,status) VALUES (8901,'状态竞争商品',10,4,'ON_SALE')
                """);
        jdbcTemplate.update("""
                INSERT INTO trade_order (id,user_id,total_amount,
                                         status,request_id,created_at,expires_at)
                VALUES (8901,8901,10,'PENDING_PAYMENT','action-race',
                        CURRENT_TIMESTAMP,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 15 MINUTE))
                """);
        jdbcTemplate.update("INSERT INTO order_item(order_id,product_id,product_name,unit_price,quantity,line_amount) VALUES (8901,8901,'状态竞争商品',10,1,10)");
    }

    @AfterEach
    void removeOwnedFixtures() {
        if (fixtureOwned) {
            jdbcTemplate.update("DELETE FROM trade_order WHERE id=8901");
            jdbcTemplate.update("DELETE FROM product WHERE id=8901");
            jdbcTemplate.update("DELETE FROM app_user WHERE id=8901");
        }
    }

    @Test
    void paymentAndCancellationChooseOneFinalState() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var payment = executor.submit(() -> {
                start.await();
                return performAction("pay");
            });
            var cancellation = executor.submit(() -> {
                start.await();
                return performAction("cancel");
            });
            start.countDown();
            List<Integer> statuses = List.of(payment.get(), cancellation.get());
            assertEquals(1L, statuses.stream().filter(value -> value == 200).count());
            assertEquals(1L, statuses.stream().filter(value -> value == 409).count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        String finalStatus = jdbcTemplate.queryForObject("SELECT status FROM trade_order WHERE id=8901", String.class);
        int stock = jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=8901", Integer.class);
        if ("PAID".equals(finalStatus)) {
            assertEquals(4, stock);
        } else {
            assertEquals("CANCELED", finalStatus);
            assertEquals(5, stock);
        }
    }

    @Test
    void repeatedConcurrentCancellationReturnsStockOnce() throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            tasks.add(() -> performAction("cancel"));
        }
        var executor = Executors.newFixedThreadPool(5);
        try {
            for (var future : executor.invokeAll(tasks)) {
                assertEquals(200, future.get());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals("CANCELED", jdbcTemplate.queryForObject(
                "SELECT status FROM trade_order WHERE id=8901", String.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=8901", Integer.class));
    }

    @Test
    void repeatedConcurrentPaymentDoesNotDeductStockAgain() throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            tasks.add(() -> performAction("pay"));
        }
        var executor = Executors.newFixedThreadPool(5);
        try {
            for (var future : executor.invokeAll(tasks)) {
                assertEquals(200, future.get());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals("PAID", jdbcTemplate.queryForObject(
                "SELECT status FROM trade_order WHERE id=8901", String.class));
        assertEquals(4, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=8901", Integer.class));
    }

    private int performAction(String action) throws Exception {
        return mockMvc.perform(post("/api/orders/8901/" + action)
                        .with(user(new PlatformUserDetails(new AppUser(
                                8901L, "action_parallel_buyer", "unused-test-hash", "USER"))))
                        .with(csrf()))
                .andReturn().getResponse().getStatus();
    }
}
