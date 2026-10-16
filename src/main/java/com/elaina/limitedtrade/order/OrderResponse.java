package com.elaina.limitedtrade.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponse(Long id, Long productId, String productName, BigDecimal unitPrice,
                            Integer quantity, BigDecimal totalAmount, String status, String requestId,
                            LocalDateTime createdAt, LocalDateTime expiresAt,
                            LocalDateTime paidAt, LocalDateTime endedAt, Long activityId) {

    public static OrderResponse from(TradeOrder order) {
        return new OrderResponse(order.getId(), order.getProductId(), order.getProductName(),
                order.getUnitPrice(), order.getQuantity(), order.getTotalAmount(), order.getStatus(),
                order.getRequestId(), order.getCreatedAt(), order.getExpiresAt(),
                order.getPaidAt(), order.getEndedAt(), order.getActivityId());
    }
}
