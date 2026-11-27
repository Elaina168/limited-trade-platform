package com.elaina.limitedtrade.product;

import java.util.List;

public record ProductChangedEvent(List<Long> productIds) {
    public static ProductChangedEvent of(long productId) {
        return new ProductChangedEvent(List.of(productId));
    }
}
