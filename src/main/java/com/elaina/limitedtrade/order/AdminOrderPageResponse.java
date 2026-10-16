package com.elaina.limitedtrade.order;

import java.util.List;

public record AdminOrderPageResponse(int page, int size, long total, List<AdminOrderResponse> items) {
}
