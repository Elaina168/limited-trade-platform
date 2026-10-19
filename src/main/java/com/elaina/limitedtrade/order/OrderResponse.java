package com.elaina.limitedtrade.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(Long id, BigDecimal totalAmount, BigDecimal originalAmount, BigDecimal discountAmount, String status, String requestId,
                                 LocalDateTime createdAt, LocalDateTime expiresAt, LocalDateTime paidAt,
                                 LocalDateTime endedAt, List<OrderItemResponse> items, OrderAddressResponse address,
                                 OrderShipmentResponse shipment, OrderCouponResponse coupon) {
    public static OrderResponse from(TradeOrder order) {
        return new OrderResponse(order.getId(), order.getTotalAmount(), order.getOriginalAmount(), order.getDiscountAmount(), order.getStatus(),
                order.getRequestId(), order.getCreatedAt(), order.getExpiresAt(), order.getPaidAt(),
                order.getEndedAt(), order.getItems().stream().map(OrderItemResponse::from).toList(),
                order.getAddress() == null ? null : OrderAddressResponse.from(order.getAddress()),
                order.getShipment() == null ? null : OrderShipmentResponse.from(order.getShipment()),
                order.getCoupon() == null ? null : OrderCouponResponse.from(order.getCoupon()));
    }
}
