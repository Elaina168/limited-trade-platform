package com.elaina.limitedtrade.cart;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AddCartItemRequest(
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity) {
}
