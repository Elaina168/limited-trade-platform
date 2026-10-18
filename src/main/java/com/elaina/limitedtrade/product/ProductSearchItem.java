package com.elaina.limitedtrade.product;

import java.math.BigDecimal;

public record ProductSearchItem(Long id, String name, BigDecimal price, Integer stock,
                                String status, Long categoryId, String categoryName) {
}
