package com.elaina.limitedtrade.coupon;

import java.time.LocalDateTime;

public record UserCouponResponse(Long id, String status, LocalDateTime claimedAt, Long orderId,
                                 ActivityCoupon coupon, boolean expired) {
    public static UserCouponResponse from(UserCoupon owned, ActivityCoupon coupon, LocalDateTime now) {
        return new UserCouponResponse(owned.getId(), owned.getStatus(), owned.getClaimedAt(), owned.getOrderId(),
                coupon, !now.isBefore(coupon.getUseEndsAt()));
    }
}
