package com.elaina.limitedtrade.product;

import java.math.BigDecimal;
import org.apache.ibatis.annotations.AutomapConstructor;

public record ProductSearchItem(
        Long id,
        String name,
        BigDecimal price,
        Integer stock,
        String status,
        Long categoryId,
        String categoryName,
        String highlightedName) {

    @AutomapConstructor
    public ProductSearchItem(Long id, String name, BigDecimal price, Integer stock,
                             String status, Long categoryId, String categoryName) {
        this(id, name, price, stock, status, categoryId, categoryName, null);
    }

    public ProductSearchItem withHighlight(String highlighted) {
        return new ProductSearchItem(id, name, price, stock, status, categoryId, categoryName, highlighted);
    }
}
