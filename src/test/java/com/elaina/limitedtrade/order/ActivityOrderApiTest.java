package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.elaina.limitedtrade.activity.ActivityMapper;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.seckill.ActivitySeckillMapper;
import com.elaina.limitedtrade.seckill.ActivitySeckillMessage;
import com.elaina.limitedtrade.seckill.ActivitySeckillService;
import com.elaina.limitedtrade.user.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActivityOrderApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ActivityMapper activityMapper;

    @Autowired
    private ActivitySeckillMapper seckillMapper;

    @Autowired
    private ActivitySeckillService seckillService;

    @Autowired
    private DataSource dataSource;

    private boolean fixtureOwned;

    @BeforeEach
    void prepareCommittedFixtures() {
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=7401", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product WHERE id IN (9401,9402)", Integer.class));
        fixtureOwned = true;
        new ResourceDatabasePopulator(new ClassPathResource("sql/activity-test-data.sql")).execute(dataSource);
    }

    @AfterEach
    void removeOwnedFixtures() {
        if (fixtureOwned) {
            jdbcTemplate.update("DELETE FROM activity_purchase_reservation WHERE user_id=7401");
            jdbcTemplate.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT id FROM trade_order WHERE user_id=7401)");
            jdbcTemplate.update("DELETE FROM trade_order WHERE user_id=7401");
            jdbcTemplate.update("DELETE FROM activity WHERE product_id IN (9401,9402)");
            jdbcTemplate.update("DELETE FROM product WHERE id IN (9401,9402)");
            jdbcTemplate.update("DELETE FROM app_user WHERE id=7401");
        }
    }

    @Test
    void activityOrderRetainsActivityId() throws Exception {
        mockMvc.perform(post("/api/orders").with(user(new PlatformUserDetails(new AppUser(
                                7401L,"activity_buyer","unused-test-hash","USER")))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":9401,"quantity":2,"requestId":"activity-order","activityId":9401}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].activityId").value(9401))
                .andExpect(jsonPath("$.totalAmount").value(40));
        assertEquals(8, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
    }

    @Test
    void ordinaryOrderDoesNotConsumeActivityQuota() throws Exception {
        placeOrder(2, null, "ordinary").andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].activityId").isEmpty());
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
        assertEquals(8, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
    }

    @Test
    void consumerRejectsOrdinaryOrderWithSameRequestId() throws Exception {
        assertFalse(seckillMapper.hasSubmittedRequest(7401L, "cross-entry"));
        placeOrder(1, null, "cross-entry").andExpect(status().isCreated());
        assertTrue(seckillMapper.hasSubmittedRequest(7401L, "cross-entry"));
        var message = new ActivitySeckillMessage(7401L, 9401L, 9401L, 1, null, null,
                "cross-entry", LocalDateTime.now());
        var error = assertThrows(ResponseStatusException.class,
                () -> orderService.materializeSeckillOrder(message));
        assertEquals(409, error.getStatusCode().value());
        seckillService.consumeMessage(message);
        assertNull(seckillMapper.findByUserAndRequest(7401L, "cross-entry"));
        placeOrder(1, null, "cross-entry").andExpect(status().isOk());
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
        assertEquals(9, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
    }

    @Test
    void completedSeckillCannotReplayThroughOrdinaryEndpoint() throws Exception {
        var message = new ActivitySeckillMessage(7401L, 9401L, 9401L, 1, null, null,
                "seckill-owner", LocalDateTime.now());
        var order = orderService.materializeSeckillOrder(message);
        assertTrue(seckillMapper.hasSubmittedRequest(7401L, "seckill-owner"));
        assertEquals(order.getId(), orderService.materializeSeckillOrder(message).getId());
        placeOrder(1, 9401L, "seckill-owner").andExpect(status().isConflict());
        var mismatched = new ActivitySeckillMessage(7401L, 9402L, 9401L, 1, null, null,
                "seckill-owner", LocalDateTime.now());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> orderService.materializeSeckillOrder(mismatched)).getStatusCode().value());
        assertEquals(4, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
        assertEquals(9, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
    }

    @Test
    void competingSyncAndSeckillTransactionsDebitOnlyTheWinningOrder() throws Exception {
        var barrier = new CyclicBarrier(2);
        var ordinary = new CreateOrderRequest(9401L, 1, "entry-race", null);
        var seckill = new ActivitySeckillMessage(7401L, 9401L, 9401L, 1, null, null,
                "entry-race", LocalDateTime.now());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var syncStatus = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    orderService.createOrder(7401L, ordinary);
                    return 201;
                } catch (ResponseStatusException conflict) {
                    return conflict.getStatusCode().value();
                }
            });
            var asyncStatus = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    orderService.materializeSeckillOrder(seckill);
                    return 201;
                } catch (ResponseStatusException conflict) {
                    return conflict.getStatusCode().value();
                }
            });
            int sync = syncStatus.get(10, TimeUnit.SECONDS);
            int async = asyncStatus.get(10, TimeUnit.SECONDS);
            assertTrue((sync == 201 && async == 409) || (sync == 409 && async == 201));
            assertEquals(async == 201 ? 4 : 5,
                    jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
        }
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order WHERE user_id=7401", Integer.class));
        assertEquals(9, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
    }

    @Test
    void sameKeyReplayAfterActivityEndsReturnsOriginalWithoutDeductingAgain() throws Exception {
        String first = placeOrder(5, 9401L, "replay").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(first).get("id").asLong();
        jdbcTemplate.update("UPDATE activity SET ends_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=9401");
        placeOrder(5, 9401L, "replay").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
    }

    @Test
    void sameKeyCannotSwitchActivities() throws Exception {
        placeOrder(1, 9401L, "switch").andExpect(status().isCreated());
        placeOrder(1, 9402L, "switch").andExpect(status().isConflict());
        assertEquals(3, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9402", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sameKeyCannotSwitchBetweenOrdinaryAndActivity(boolean activityFirst) throws Exception {
        placeOrder(1, activityFirst ? 9401L : null, "switch-kind").andExpect(status().isCreated());
        placeOrder(1, activityFirst ? null : 9401L, "switch-kind").andExpect(status().isConflict());
    }

    @Test
    void mismatchedProductDoesNotDeductEitherCounter() throws Exception {
        mockMvc.perform(post("/api/orders").with(buyer()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":9402,\"quantity\":1,\"requestId\":\"mismatch\",\"activityId\":9401}"))
                .andExpect(status().isConflict());
        assertEquals(10, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9402", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
    }

    @Test
    void nonexistentActivityIsNotFound() throws Exception {
        placeOrder(1, 999999L, "missing").andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidActivityIdIsBadRequest(long activityId) throws Exception {
        placeOrder(1, activityId, "invalid").andExpect(status().isBadRequest());
    }

    @Test
    void notStartedActivityRollsBackProductDeduction() throws Exception {
        jdbcTemplate.update("UPDATE activity SET starts_at=DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 30 MINUTE) WHERE id=9401");
        placeOrder(1, 9401L, "early").andExpect(status().isConflict());
        assertEquals(10, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
    }

    @Test
    void endedActivityRollsBackProductDeduction() throws Exception {
        jdbcTemplate.update("UPDATE activity SET ends_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=9401");
        placeOrder(1, 9401L, "late").andExpect(status().isConflict());
        assertEquals(10, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
    }

    @Test
    void insufficientQuotaRollsBackProductDeduction() throws Exception {
        placeOrder(6, 9401L, "too-many").andExpect(status().isConflict());
        assertEquals(10, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
    }

    @Test
    void insufficientProductStockDoesNotConsumeQuota() throws Exception {
        jdbcTemplate.update("UPDATE product SET stock=1 WHERE id=9401");
        placeOrder(2, 9401L, "no-stock").andExpect(status().isConflict());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
    }

    @Test
    void offSaleProductDoesNotConsumeQuota() throws Exception {
        jdbcTemplate.update("UPDATE product SET status='OFF_SALE' WHERE id=9401");
        placeOrder(1, 9401L, "off-sale").andExpect(status().isConflict());
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
    }

    @Test
    void repeatedCancelReturnsBothCountersOnceEvenAfterActivityEnds() throws Exception {
        long id = createActivityOrder();
        jdbcTemplate.update("UPDATE activity SET ends_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=9401");
        for (int index = 0; index < 2; index++) {
            mockMvc.perform(post("/api/orders/" + id + "/cancel").with(buyer()).with(csrf()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        }
        assertEquals(10, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
    }

    @Test
    void repeatedCloseReturnsBothCountersOnce() throws Exception {
        long id = createActivityOrder();
        LocalDateTime cutoff = LocalDateTime.now().withNano(0);
        jdbcTemplate.update("UPDATE trade_order SET expires_at=? WHERE id=?", cutoff, id);
        assertEquals(true, orderService.closeExpiredOrder(id, cutoff));
        assertEquals(false, orderService.closeExpiredOrder(id, cutoff));
        assertEquals(10, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
    }

    @Test
    void paymentAfterActivityEndsKeepsOriginalOrderDeadline() throws Exception {
        long id = createActivityOrder();
        jdbcTemplate.update("UPDATE activity SET ends_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE id=9401");
        mockMvc.perform(post("/api/orders/" + id + "/pay").with(buyer()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertEquals(8, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9401", Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
        assertEquals(15, jdbcTemplate.queryForObject(
                "SELECT TIMESTAMPDIFF(MINUTE,created_at,expires_at) FROM trade_order WHERE id=?", Integer.class, id));
    }

    @Test
    void activityIdSurvivesOwnerListDetailAndAdminQuery() throws Exception {
        long id = createActivityOrder();
        mockMvc.perform(get("/api/orders/" + id).with(buyer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].activityId").value(9401));
        mockMvc.perform(get("/api/orders").with(buyer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].items[0].activityId").value(9401));
        mockMvc.perform(get("/api/admin/orders").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].items[0].activityId").value(9401));
    }

    @Test
    void exactStartIsIncludedAndExactEndIsExcluded() {
        LocalDateTime startsAt = LocalDateTime.of(2026, 10, 16, 10, 0);
        LocalDateTime endsAt = startsAt.plusHours(1);
        jdbcTemplate.update("UPDATE activity SET starts_at=?,ends_at=? WHERE id=9401", startsAt, endsAt);
        assertEquals(0, activityMapper.deductQuantity(9401, 1, startsAt.minusSeconds(1)));
        assertEquals(1, activityMapper.deductQuantity(9401, 1, startsAt));
        assertEquals(0, activityMapper.deductQuantity(9401, 1, endsAt));
        assertEquals(4, jdbcTemplate.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9401", Integer.class));
    }

    private long createActivityOrder() throws Exception {
        String response = placeOrder(2, 9401L, "activity-2").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private ResultActions placeOrder(int quantity, Long activityId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/orders").with(buyer()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":9401,\"quantity\":%d,\"requestId\":\"%s\",\"activityId\":%s}"
                        .formatted(quantity, requestId, activityId)));
    }

    private RequestPostProcessor buyer() {
        return user(new PlatformUserDetails(new AppUser(7401L,"activity_buyer","unused-test-hash","USER")));
    }
}
