package com.elaina.limitedtrade.order;

import java.math.BigDecimal;

public record OrderItem(Long orderId, Long productId, String productName, BigDecimal unitPrice,
                        Integer quantity, BigDecimal lineAmount, Long activityId) {
}
