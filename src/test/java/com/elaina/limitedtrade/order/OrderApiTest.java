package com.elaina.limitedtrade.order;

import java.math.BigDecimal;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.user.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/order-test-data.sql")
class OrderApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createOrderUsesServerPriceAndAuthenticatedOwner() throws Exception {
        String response = mockMvc.perform(post("/api/orders").with(buyer(7101)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":9101,"quantity":2,"requestId":"create-1",
                                 "userId":7102,"unitPrice":0.01,"totalAmount":0.02}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].productName").value("订单测试键盘"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(129.00))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.totalAmount").value(258.00))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(response).get("id").asLong();
        assertEquals(7101L, jdbcTemplate.queryForObject(
                "SELECT user_id FROM trade_order WHERE id = ?", Long.class, orderId));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT stock FROM product WHERE id = 9101", Integer.class));
    }

    @Test
    void replayReturnsOriginalOrderEvenAfterStockIsExhausted() throws Exception {
        String body = "{\"productId\":9101,\"quantity\":5,\"requestId\":\"retry-1\"}";
        String first = mockMvc.perform(post("/api/orders").with(buyer(7101)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long firstId = objectMapper.readTree(first).get("id").asLong();
        mockMvc.perform(post("/api/orders").with(buyer(7101)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT stock FROM product WHERE id = 9101", Integer.class));
    }

    @Test
    void sameRequestIdWithDifferentQuantityIsConflict() throws Exception {
        mockMvc.perform(post("/api/orders").with(buyer(7101)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":9101,\"quantity\":1,\"requestId\":\"same-key\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/orders").with(buyer(7101)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":9101,\"quantity\":2,\"requestId\":\"same-key\"}"))
                .andExpect(status().isConflict());
        assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT stock FROM product WHERE id = 9101", Integer.class));
    }

    @Test
    void insufficientStockDoesNotLeaveAnOrder() throws Exception {
        mockMvc.perform(post("/api/orders").with(buyer(7101)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":9101,\"quantity\":6,\"requestId\":\"too-many\"}"))
                .andExpect(status().isConflict());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject(
                "SELECT stock FROM product WHERE id = 9101", Integer.class));
    }

    @Test
    void offSaleProductCannotBeOrdered() throws Exception {
        mockMvc.perform(post("/api/orders").with(buyer(7101)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":9102,\"quantity\":1,\"requestId\":\"off-sale\"}"))
                .andExpect(status().isConflict());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trade_order", Integer.class));
    }

    @Test
    void missingProductIsNotFound() throws Exception {
        mockMvc.perform(post("/api/orders").with(buyer(7101)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":999999,\"quantity\":1,\"requestId\":\"missing\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void orderSnapshotsAndOwnershipArePreserved() throws Exception {
        String response = mockMvc.perform(post("/api/orders").with(buyer(7101)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":9101,\"quantity\":2,\"requestId\":\"snapshot\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long orderId = objectMapper.readTree(response).get("id").asLong();
        jdbcTemplate.update("UPDATE product SET name = '已改名', price = 299 WHERE id = 9101");
        mockMvc.perform(get("/api/orders/" + orderId).with(buyer(7101)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productName").value("订单测试键盘"))
                .andExpect(jsonPath("$.totalAmount").value(258.00));
        mockMvc.perform(get("/api/orders/" + orderId).with(buyer(7102)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/orders").with(buyer(7102)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/orders").with(buyer(7101)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        assertEquals(new BigDecimal("258.00"), jdbcTemplate.queryForObject(
                "SELECT total_amount FROM trade_order WHERE id = ?", BigDecimal.class, orderId));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"productId\":9101,\"quantity\":0,\"requestId\":\"invalid\"}",
            "{\"productId\":9101,\"quantity\":-1,\"requestId\":\"invalid\"}",
            "{\"productId\":9101,\"quantity\":1.5,\"requestId\":\"invalid\"}",
            "{\"productId\":9101,\"requestId\":\"invalid\"}",
            "{\"quantity\":1,\"requestId\":\"invalid\"}",
            "{\"productId\":-1,\"quantity\":1,\"requestId\":\"invalid\"}",
            "{\"productId\":9101,\"quantity\":1}",
            "{\"productId\":9101,\"quantity\":1,\"requestId\":\" \"}"
    })
    void invalidRequestDoesNotDeductStock(String body) throws Exception {
        mockMvc.perform(post("/api/orders").with(buyer(7101)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertEquals(5, jdbcTemplate.queryForObject(
                "SELECT stock FROM product WHERE id = 9101", Integer.class));
    }

    @Test
    void anonymousCannotAccessOrders() throws Exception {
        mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/orders").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":9101,\"quantity\":1,\"requestId\":\"anonymous\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creationWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/api/orders").with(buyer(7101))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":9101,\"quantity\":1,\"requestId\":\"no-csrf\"}"))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor buyer(long id) {
        return user(new PlatformUserDetails(new AppUser(id, "buyer_" + id, "unused-test-hash", "USER")));
    }
}
