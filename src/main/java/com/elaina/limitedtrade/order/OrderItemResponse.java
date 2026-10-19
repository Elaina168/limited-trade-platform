package com.elaina.limitedtrade.order;

import java.math.BigDecimal;

public record OrderItemResponse(Long productId, String productName, BigDecimal unitPrice,
                                Integer quantity, BigDecimal lineAmount, Long activityId) {
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.productId(), item.productName(), item.unitPrice(),
                item.quantity(), item.lineAmount(), item.activityId());
    }
}
