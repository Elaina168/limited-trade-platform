package com.elaina.limitedtrade.product;

import java.util.List;

public record ProductSearchPage(int page, int size, long total, List<ProductSearchItem> items) {
}
