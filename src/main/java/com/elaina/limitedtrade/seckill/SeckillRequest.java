package com.elaina.limitedtrade.seckill;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SeckillRequest(
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String requestId,
        @Positive Long userCouponId,
        @Positive Long addressId) {
}
