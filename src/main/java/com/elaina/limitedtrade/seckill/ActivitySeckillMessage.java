package com.elaina.limitedtrade.seckill;

import java.time.LocalDateTime;
import java.util.Objects;

public record ActivitySeckillMessage(
        long userId,
        long activityId,
        long productId,
        int quantity,
        Long addressId,
        Long userCouponId,
        String requestId,
        LocalDateTime reservedAt) {

    public boolean matchesReservation(ActivityPurchaseReservation reservation) {
        return requestId.equals(reservation.getRequestId())
                && activityId == reservation.getActivityId()
                && productId == reservation.getProductId()
                && quantity == reservation.getQuantity()
                && Objects.equals(addressId, reservation.getAddressId())
                && Objects.equals(userCouponId, reservation.getUserCouponId());
    }
}
