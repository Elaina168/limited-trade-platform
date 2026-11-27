package com.elaina.limitedtrade.product;

import java.math.BigDecimal;

public record ProductSearchCriteria(
        int page,
        int size,
        Long categoryId,
        String keyword,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        boolean inStockOnly,
        String sort) {

    public ProductSearchCriteria(int page, int size, Long categoryId, String keyword) {
        this(page, size, categoryId, keyword, null, null, false, null);
    }

    public String normalizedKeyword() {
        return keyword == null || keyword.isBlank() ? null : keyword.strip();
    }

    public String normalizedSort() {
        if (sort == null || sort.isBlank()) {
            return "default";
        }
        return sort.strip().toLowerCase();
    }
}
