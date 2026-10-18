package com.elaina.limitedtrade.cart;

import java.math.BigDecimal;

public record CartItemResponse(Long productId, String name, BigDecimal price,
                               Integer stock, String status, Integer quantity) {
}
