package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.order.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentToolsTest {
    private final OrderService orders = mock(OrderService.class);
    private final TradeAgentTools tools = new TradeAgentTools(orders);
    private final ToolContext context = new ToolContext(Map.of("userId", 7301L));

    @Test void listUsesTrustedUserAndExcludesPrivateOrderFields() throws Exception {
        var order = new OrderResponse(9301L, new BigDecimal("19.90"), new BigDecimal("19.90"),
                BigDecimal.ZERO, "PENDING_PAYMENT", "private-request", LocalDateTime.now(),
                LocalDateTime.now().plusMinutes(15), null, null,
                List.of(new OrderItemResponse(1L, "测试商品", new BigDecimal("19.90"), 1,
                        new BigDecimal("19.90"), null)), null, null, null);
        when(orders.listUserOrders(7301L, 1, 5, "PENDING_PAYMENT"))
                .thenReturn(new OrderPageResponse(1, 5, 1, List.of(order)));
        var result = tools.listMyOrders(1, 5, "PENDING_PAYMENT", context);
        assertThat(result.items()).extracting(AgentOrderSummary::id).containsExactly(9301L);
        var json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).contains("expiresAt", "测试商品").doesNotContain("requestId", "address", "shipment", "private-request");
        verify(orders).listUserOrders(7301L, 1, 5, "PENDING_PAYMENT");
    }

    @Test void detailKeepsNotFoundForOtherUsersOrders() {
        when(orders.getUserOrder(7301L, 9302L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        assertThatThrownBy(() -> tools.getMyOrder(9302L, context))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test void invalidModelArgumentsNeverReachService() {
        assertThatThrownBy(() -> tools.listMyOrders(0, 5, null, context)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tools.listMyOrders(1, 11, null, context)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tools.listMyOrders(1, 5, "ADMIN", context)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tools.getMyOrder(-1, context)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(orders);
    }

    @Test void ruleTopicsAreBoundedAndHaveTraceableSources() {
        assertThat(tools.getTradeRules("PAYMENT").text()).contains("15分钟", "expiresAt", "模拟支付");
        assertThat(tools.getTradeRules("CANCEL").source()).contains("OrderService.java");
        assertThat(tools.getTradeRules("TIMEOUT").text()).contains("CLOSED", "扫描");
        assertThatThrownBy(() -> tools.getTradeRules("REFUND")).isInstanceOf(ResponseStatusException.class);
    }
}
