package com.elaina.limitedtrade.agent;

import com.elaina.limitedtrade.order.OrderItemResponse;
import com.elaina.limitedtrade.order.OrderResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AgentOrderSummary(Long id, String status, BigDecimal totalAmount, LocalDateTime createdAt,
                                LocalDateTime expiresAt, LocalDateTime paidAt, LocalDateTime endedAt,
                                List<OrderItemResponse> items) {
    static AgentOrderSummary from(OrderResponse order) {
        // 只投影咨询需要的字段，不把收货信息、运单和请求幂等键交给模型。
        return new AgentOrderSummary(order.id(), order.status(), order.totalAmount(), order.createdAt(),
                order.expiresAt(), order.paidAt(), order.endedAt(), order.items());
    }
}
