package com.elaina.limitedtrade.seckill;

import com.elaina.limitedtrade.order.OrderResponse;

public record SeckillResponse(
        String requestId,
        String status,
        Long orderId,
        OrderResponse order,
        String message) {

    public static SeckillResponse queued(String requestId) {
        return new SeckillResponse(requestId, "QUEUED", null, null, "已锁定抢购名额，正在生成订单…");
    }

    public static SeckillResponse created(String requestId, OrderResponse order) {
        return new SeckillResponse(requestId, "CREATED", order.id(), order, "订单已生成。");
    }

    public static SeckillResponse createdIdOnly(String requestId, long orderId) {
        return new SeckillResponse(requestId, "CREATED", orderId, null, "订单已生成。");
    }

    public static SeckillResponse failed(String requestId, String reason) {
        return new SeckillResponse(requestId, "FAILED", null, null,
                reason == null || reason.isBlank() ? "抢购未能完成建单。" : reason);
    }
}
