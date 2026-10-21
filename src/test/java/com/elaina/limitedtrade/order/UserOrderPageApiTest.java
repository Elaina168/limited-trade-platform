package com.elaina.limitedtrade.order;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.user.AppUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/admin-order-test-data.sql")
class UserOrderPageApiTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void defaultPageContainsOnlyOwnedOrdersAndDetails() throws Exception {
        mvc.perform(get("/api/orders").with(user(new PlatformUserDetails(
                        new AppUser(7301L, "buyer", "unused", "USER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].id").value(9305))
                .andExpect(jsonPath("$.items[1].id").value(9303))
                .andExpect(jsonPath("$.items[2].id").value(9301))
                .andExpect(jsonPath("$.items[0].items[0].productName").value("管理查询测试商品"));
    }

    @ParameterizedTest
    @CsvSource({"1,2,9305", "2,1,9301"})
    void pagesKeepOwnedTotalAndDescendingOrder(int page, int count, int firstId) throws Exception {
        mvc.perform(get("/api/orders").param("page", String.valueOf(page)).param("size", "2")
                        .with(user(new PlatformUserDetails(new AppUser(7301L, "buyer", "unused", "USER")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(page))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(count))
                .andExpect(jsonPath("$.items[0].id").value(firstId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "2147483647"})
    void outOfRangePageIsEmptyWithoutOffsetOverflow(String page) throws Exception {
        mvc.perform(get("/api/orders").param("page", page).param("size", "2")
                        .with(user(new PlatformUserDetails(new AppUser(7301L, "buyer", "unused", "USER")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void statusFilterAppliesToCountAndPageAndCannotSelectAnotherUser() throws Exception {
        mvc.perform(get("/api/orders").param("status", "PENDING_PAYMENT").param("size", "1")
                        .param("page", "2").param("userId", "7302")
                        .with(user(new PlatformUserDetails(new AppUser(7301L, "buyer", "unused", "USER")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(9301));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "SHIPPED", "COMPLETED", "CLOSED"})
    void validStatusesWithNoOwnedMatchReturnEmptyPage(String value) throws Exception {
        mvc.perform(get("/api/orders").param("status", value)
                        .with(user(new PlatformUserDetails(new AppUser(7301L, "buyer", "unused", "USER")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void adminIdentityStillOnlyListsOwnOrders() throws Exception {
        mvc.perform(get("/api/orders").with(user(new PlatformUserDetails(
                        new AppUser(7399L, "admin", "unused", "ADMIN")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @ParameterizedTest
    @CsvSource({"page,0", "page,-1", "page,abc", "page,2147483648", "size,0", "size,-1", "size,101",
            "size,1.5", "status,UNKNOWN", "status,paid"})
    void invalidParametersReturnBadRequest(String key, String value) throws Exception {
        mvc.perform(get("/api/orders").param(key, value)
                        .with(user(new PlatformUserDetails(new AppUser(7301L, "buyer", "unused", "USER")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousCannotListOrders() throws Exception {
        mvc.perform(get("/api/orders").param("page", "1")).andExpect(status().isUnauthorized());
    }
}
