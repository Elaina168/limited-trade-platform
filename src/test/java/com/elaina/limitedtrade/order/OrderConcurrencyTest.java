package com.elaina.limitedtrade.order;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.user.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class OrderConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private boolean fixtureOwned;

    @BeforeEach
    void prepareCommittedFixtures() {
        // 并发线程需要看到已提交的数据，不能依赖测试主线程的未提交事务。
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE id BETWEEN 8801 AND 8820", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product WHERE id = 8801", Integer.class));
        fixtureOwned = true;
        for (int index = 0; index < 20; index++) {
            jdbcTemplate.update("""
                    INSERT INTO app_user (id,username,password_hash,role) VALUES (?,?,'unused-test-hash','USER')
                    """, 8801 + index, "parallel_buyer_" + index);
        }
        jdbcTemplate.update("""
                INSERT INTO product (id,name,price,stock,status) VALUES (8801,'并发测试商品',10,5,'ON_SALE')
                """);
    }

    @AfterEach
    void removeOwnedFixtures() {
        if (fixtureOwned) {
            jdbcTemplate.update("DELETE FROM trade_order WHERE product_id = 8801");
            jdbcTemplate.update("DELETE FROM product WHERE id = 8801");
            jdbcTemplate.update("DELETE FROM app_user WHERE id BETWEEN 8801 AND 8820");
        }
    }

    @Test
    void differentBuyersCannotOversellFiveUnits() throws Exception {
        List<Callable<Integer>> requests = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            long userId = 8801L + index;
            String requestId = "parallel-" + index;
            requests.add(() -> mockMvc.perform(post("/api/orders")
                            .with(user(new PlatformUserDetails(new AppUser(
                                    userId, "parallel_buyer", "unused-test-hash", "USER"))))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":8801,\"quantity\":1,\"requestId\":\"" + requestId + "\"}"))
                    .andReturn().getResponse().getStatus());
        }
        var executor = Executors.newFixedThreadPool(10);
        int successful = 0;
        try {
            for (var future : executor.invokeAll(requests)) {
                int status = future.get();
                if (status == 201) {
                    successful++;
                } else {
                    assertEquals(409, status);
                }
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(5, successful);
        assertEquals(5, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT SUM(quantity) FROM trade_order", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=8801", Integer.class));
    }

    @Test
    void concurrentRetriesCreateOneOrderAndDeductOnce() throws Exception {
        jdbcTemplate.update("UPDATE product SET stock=1 WHERE id=8801");
        List<Callable<Long>> requests = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            requests.add(() -> {
                var response = mockMvc.perform(post("/api/orders")
                                .with(user(new PlatformUserDetails(new AppUser(
                                        8801L, "parallel_buyer", "unused-test-hash", "USER"))))
                                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"productId\":8801,\"quantity\":1,\"requestId\":\"same-request\"}"))
                        .andReturn().getResponse();
                assertTrue(response.getStatus() == 200 || response.getStatus() == 201);
                return objectMapper.readTree(response.getContentAsString()).get("id").asLong();
            });
        }
        var executor = Executors.newFixedThreadPool(10);
        List<Long> ids = new ArrayList<>();
        try {
            for (var future : executor.invokeAll(requests)) {
                ids.add(future.get());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1L, ids.stream().distinct().count());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=8801", Integer.class));
    }
}
