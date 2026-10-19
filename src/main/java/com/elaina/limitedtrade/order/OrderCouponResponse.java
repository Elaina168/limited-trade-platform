package com.elaina.limitedtrade.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderCouponResponse(Long userCouponId, Long couponId, Long activityId, String name,
                                  BigDecimal thresholdAmount, BigDecimal discountAmount,
                                  LocalDateTime useStartsAt, LocalDateTime useEndsAt) {
    public static OrderCouponResponse from(OrderCoupon c) {
        return new OrderCouponResponse(c.userCouponId(),c.couponId(),c.activityId(),c.name(),
                c.thresholdAmount(),c.discountAmount(),c.useStartsAt(),c.useEndsAt());
    }
}
