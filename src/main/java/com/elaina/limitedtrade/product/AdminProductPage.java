package com.elaina.limitedtrade.product;

import java.util.List;

public record AdminProductPage(int page, int size, long total, List<Product> items) {
}
