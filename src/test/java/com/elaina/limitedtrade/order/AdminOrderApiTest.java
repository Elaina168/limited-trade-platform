package com.elaina.limitedtrade.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/admin-order-test-data.sql")
class AdminOrderApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminListsAllUsersAndStatusesWithDefaultPage() throws Exception {
        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].id").value(9305))
                .andExpect(jsonPath("$.items[0].userId").value(7301))
                .andExpect(jsonPath("$.items[0].totalAmount").value(40))
                .andExpect(jsonPath("$.items[1].status").value("CLOSED"))
                .andExpect(jsonPath("$.items[1].endedAt").value("2026-10-16T10:18:00"))
                .andExpect(jsonPath("$.items[3].userId").value(7302))
                .andExpect(jsonPath("$.items[3].paidAt").value("2026-10-16T10:02:00"))
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void secondPageUsesOffsetAndCountsAllMatchingOrders() throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(9303))
                .andExpect(jsonPath("$.items[1].id").value(9302));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void lastPageMayContainFewerOrdersThanRequested() throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("page", "3").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(9301));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void pageBeyondLastPageIsEmptyAndKeepsTotal() throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("page", "4").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void largeValidPageDoesNotOverflowOffset() throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @ParameterizedTest
    @CsvSource({"PENDING_PAYMENT,2,9305", "PAID,1,9302", "CANCELED,1,9303", "CLOSED,1,9304"})
    @WithMockUser(roles = "ADMIN")
    void filtersByEachSupportedStatus(String orderStatus, int total, long firstId) throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("status", orderStatus))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(total))
                .andExpect(jsonPath("$.items.length()").value(total))
                .andExpect(jsonPath("$.items[0].id").value(firstId));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void filtersByBuyer() throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("userId", "7302"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(9304))
                .andExpect(jsonPath("$.items[1].id").value(9302));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void combinesFiltersForBothCountAndPage() throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("userId", "7301")
                        .param("status", "PENDING_PAYMENT").param("size", "1").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(9301));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void nonexistentBuyerHasEmptyPage() throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("userId", "7399"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @ParameterizedTest
    @CsvSource({"page,0", "page,-1", "page,abc", "page,2147483648", "size,0", "size,-1", "size,101", "size,1.5",
            "userId,0", "userId,-1", "userId,abc"})
    @WithMockUser(roles = "ADMIN")
    void rejectsInvalidNumericQueryParameter(String name, String value) throws Exception {
        mockMvc.perform(get("/api/admin/orders").param(name, value))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "paid", "UNKNOWN", "PAID' OR '1'='1"})
    @WithMockUser(roles = "ADMIN")
    void rejectsInvalidStatus(String orderStatus) throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("status", orderStatus))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousCannotListAdminOrders() throws Exception {
        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void ordinaryUserCannotListOtherBuyersEvenWithFilters() throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("userId", "7301"))
                .andExpect(status().isForbidden());
    }
}
