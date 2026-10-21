package com.elaina.limitedtrade.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderMessageMonitorApiTest {
    @Autowired MockMvc mvc;

    @Test void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/admin/operations/order-messages")).andExpect(status().isUnauthorized());
    }

    @Test @WithMockUser(roles = "USER") void ordinaryUserIsForbidden() throws Exception {
        mvc.perform(get("/api/admin/operations/order-messages")).andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles = "ADMIN") void administratorReceivesExplicitSourceState() throws Exception {
        mvc.perform(get("/api/admin/operations/order-messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectedAt").isNotEmpty())
                .andExpect(jsonPath("$.database.outboxPending").isNumber())
                .andExpect(jsonPath("$.queues.available").isBoolean())
                .andExpect(jsonPath("$.alerts").isArray());
    }
}
