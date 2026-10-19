package com.elaina.limitedtrade.order;

import java.util.List;
import jakarta.validation.constraints.*;

public record CheckoutRequest(
        @NotEmpty(message = "请选择购物车商品") @Size(max = 50, message = "一次最多结算50种商品")
        List<@NotNull @Positive Long> productIds,
        @NotNull @Positive Long addressId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestId) {
}
