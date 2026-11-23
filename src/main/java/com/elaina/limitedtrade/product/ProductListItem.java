package com.elaina.limitedtrade.product;

import java.math.BigDecimal;

// 固定旧列表的公开字段，新增分类元数据通过分页查询返回。
public record ProductListItem(Long id, String name, BigDecimal price, Integer stock, String status) {
    static ProductListItem from(Product product) {
        return new ProductListItem(product.getId(), product.getName(), product.getPrice(),
                product.getStock(), product.getStatus());
    }
}
