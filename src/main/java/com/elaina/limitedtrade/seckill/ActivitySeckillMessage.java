package com.elaina.limitedtrade.seckill;

import java.time.LocalDateTime;

public record ActivitySeckillMessage(
        long userId,
        long activityId,
        long productId,
        int quantity,
        Long addressId,
        Long userCouponId,
        String requestId,
        LocalDateTime reservedAt) {
}
