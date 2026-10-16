package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.user.AppUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/order-action-test-data.sql")
class OrderActionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void payPendingOrderRecordsPaymentWithoutDeductingAgain() throws Exception {
        mockMvc.perform(post("/api/orders/9201/pay").with(buyer(7201)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.totalAmount").value(40.00))
                .andExpect(jsonPath("$.paidAt").isNotEmpty())
                .andExpect(jsonPath("$.endedAt").isEmpty());
        assertEquals(3, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void repeatedPaymentPreservesOriginalPaymentTime() throws Exception {
        LocalDateTime paidAt = LocalDateTime.now().minusMinutes(1).withNano(0);
        jdbcTemplate.update("UPDATE trade_order SET status='PAID',paid_at=? WHERE id=9201", paidAt);
        mockMvc.perform(post("/api/orders/9201/pay").with(buyer(7201)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertEquals(paidAt, jdbcTemplate.queryForObject(
                "SELECT paid_at FROM trade_order WHERE id=9201", LocalDateTime.class));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void cancelPendingOrderReturnsStockAndPreservesAmount() throws Exception {
        mockMvc.perform(post("/api/orders/9201/cancel").with(buyer(7201)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.totalAmount").value(40.00))
                .andExpect(jsonPath("$.endedAt").isNotEmpty())
                .andExpect(jsonPath("$.paidAt").isEmpty());
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void repeatedCancellationDoesNotReturnStockAgainOrChangeTime() throws Exception {
        LocalDateTime endedAt = LocalDateTime.now().minusMinutes(1).withNano(0);
        jdbcTemplate.update("UPDATE trade_order SET status='CANCELED',ended_at=? WHERE id=9201", endedAt);
        jdbcTemplate.update("UPDATE product SET stock=5 WHERE id=9201");
        mockMvc.perform(post("/api/orders/9201/cancel").with(buyer(7201)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELED"));
        assertEquals(endedAt, jdbcTemplate.queryForObject(
                "SELECT ended_at FROM trade_order WHERE id=9201", LocalDateTime.class));
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void expiredPendingOrderCannotBePaid() throws Exception {
        jdbcTemplate.update("UPDATE trade_order SET expires_at=? WHERE id=9201", LocalDateTime.now().minusSeconds(1));
        mockMvc.perform(post("/api/orders/9201/pay").with(buyer(7201)).with(csrf()))
                .andExpect(status().isConflict());
        assertEquals("PENDING_PAYMENT", jdbcTemplate.queryForObject(
                "SELECT status FROM trade_order WHERE id=9201", String.class));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void canceledOrderCannotBePaid() throws Exception {
        jdbcTemplate.update("UPDATE trade_order SET status='CANCELED',ended_at=CURRENT_TIMESTAMP WHERE id=9201");
        jdbcTemplate.update("UPDATE product SET stock=5 WHERE id=9201");
        mockMvc.perform(post("/api/orders/9201/pay").with(buyer(7201)).with(csrf()))
                .andExpect(status().isConflict());
        assertEquals(5, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @Test
    void paidOrderCannotBeCanceled() throws Exception {
        jdbcTemplate.update("UPDATE trade_order SET status='PAID',paid_at=CURRENT_TIMESTAMP WHERE id=9201");
        mockMvc.perform(post("/api/orders/9201/cancel").with(buyer(7201)).with(csrf()))
                .andExpect(status().isConflict());
        assertEquals(3, jdbcTemplate.queryForObject("SELECT stock FROM product WHERE id=9201", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay", "cancel"})
    void otherUsersCannotOperateOrder(String action) throws Exception {
        mockMvc.perform(post("/api/orders/9201/" + action).with(buyer(7202)).with(csrf()))
                .andExpect(status().isNotFound());
        assertEquals("PENDING_PAYMENT", jdbcTemplate.queryForObject(
                "SELECT status FROM trade_order WHERE id=9201", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay", "cancel"})
    void missingOrderIsNotFound(String action) throws Exception {
        mockMvc.perform(post("/api/orders/999999/" + action).with(buyer(7201)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay", "cancel"})
    void anonymousActionIsUnauthorized(String action) throws Exception {
        mockMvc.perform(post("/api/orders/9201/" + action).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay", "cancel"})
    void actionWithoutCsrfIsForbidden(String action) throws Exception {
        mockMvc.perform(post("/api/orders/9201/" + action).with(buyer(7201)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pay", "cancel"})
    void invalidOrderIdIsRejected(String action) throws Exception {
        mockMvc.perform(post("/api/orders/-1/" + action).with(buyer(7201)).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private RequestPostProcessor buyer(long id) {
        return user(new PlatformUserDetails(new AppUser(id, "action_buyer", "unused-test-hash", "USER")));
    }
}
