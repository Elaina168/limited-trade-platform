package com.elaina.limitedtrade.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderCoupon(Long orderId, Long userCouponId, Long couponId, Long activityId, String name,
                          BigDecimal thresholdAmount, BigDecimal discountAmount,
                          LocalDateTime useStartsAt, LocalDateTime useEndsAt) {
}
