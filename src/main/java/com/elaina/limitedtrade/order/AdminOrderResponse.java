package com.elaina.limitedtrade.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AdminOrderResponse(Long id, Long userId, BigDecimal totalAmount, BigDecimal originalAmount, BigDecimal discountAmount, String status, String requestId,
                                 LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime paidAt,
                                 LocalDateTime endedAt, List<OrderItemResponse> items, OrderShipmentResponse shipment, OrderCouponResponse coupon) {
    public static AdminOrderResponse from(TradeOrder order) {
        return new AdminOrderResponse(order.getId(), order.getUserId(), order.getTotalAmount(), order.getOriginalAmount(), order.getDiscountAmount(), order.getStatus(),
                order.getRequestId(), order.getCreatedAt(), order.getExpiresAt(), order.getPaidAt(),
                order.getEndedAt(), order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getShipment() == null ? null : OrderShipmentResponse.from(order.getShipment()),
                order.getCoupon() == null ? null : OrderCouponResponse.from(order.getCoupon()));
    }
}
