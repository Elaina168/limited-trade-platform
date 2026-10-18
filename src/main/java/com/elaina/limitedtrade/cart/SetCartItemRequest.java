package com.elaina.limitedtrade.cart;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SetCartItemRequest(
        @NotNull(message = "购买数量不能为空")
        @Positive(message = "购买数量必须大于0") Integer quantity) {
}
