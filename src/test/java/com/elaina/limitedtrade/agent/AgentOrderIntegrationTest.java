package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.order.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "app.agent.enabled=false")
@ActiveProfiles("test")
@Transactional
@Sql("/sql/admin-order-test-data.sql")
class AgentOrderIntegrationTest {
    @Autowired OrderService orders;
    @Autowired ObjectMapper mapper;

    @Test void actualMapperAndServiceKeepUserScopeAndReturnOnlySummary() throws Exception {
        var tools = new TradeAgentTools(orders);
        var context = new ToolContext(Map.of("userId", 7301L));
        var page = tools.listMyOrders(1, 10, null, context);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(AgentOrderSummary::id).containsExactly(9305L, 9303L, 9301L);
        var detail = tools.getMyOrder(9301, context);
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.expiresAt().toString()).isEqualTo("2026-10-16T10:15");
        assertThat(mapper.writeValueAsString(detail)).doesNotContain("address", "phone", "requestId", "shipment");
        assertThatThrownBy(() -> tools.getMyOrder(9302, context))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(tools.listMyOrders(1, 5, "PAID", context).items()).isEmpty();
    }
}
