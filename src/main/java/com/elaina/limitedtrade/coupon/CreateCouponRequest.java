package com.elaina.limitedtrade.coupon;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import jakarta.validation.constraints.*;

public record CreateCouponRequest(
        @NotNull @Positive Long activityId,
        @NotBlank @Size(max=100) String name,
        @NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal thresholdAmount,
        @NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal discountAmount,
        @NotNull @Positive Integer totalQuantity,
        @NotNull LocalDateTime claimStartsAt,
        @NotNull LocalDateTime claimEndsAt,
        @NotNull LocalDateTime useStartsAt,
        @NotNull LocalDateTime useEndsAt) {
}
