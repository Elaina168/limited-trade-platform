package com.elaina.limitedtrade.order;

public record CreateOrderResult(TradeOrder order, boolean created) {
}
