package com.elaina.limitedtrade.product;

import java.util.List;

public record ProductSearchPage(
        int page,
        int size,
        long total,
        List<ProductSearchItem> items,
        List<CategoryFacet> categoryFacets,
        String engine,
        boolean degraded) {

    public ProductSearchPage(int page, int size, long total, List<ProductSearchItem> items) {
        this(page, size, total, items, List.of(), "mysql", false);
    }

    public ProductSearchPage asDegraded() {
        return new ProductSearchPage(page, size, total, items, categoryFacets, engine, true);
    }
}
