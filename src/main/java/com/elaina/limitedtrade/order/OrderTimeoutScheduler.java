package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class OrderTimeoutScheduler {

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final boolean enabled;

    public OrderTimeoutScheduler(OrderMapper orderMapper, OrderService orderService,
            @Value("${app.order-timeout.enabled:true}") boolean enabled) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.order-timeout.scan-delay-ms:30000}")
    public void scanIfEnabled() {
        if (enabled) {
            closeExpiredOrders();
        }
    }

    public void closeExpiredOrders() {
        LocalDateTime cutoff = LocalDateTime.now().withNano(0);
        // 每轮只取一批；下一轮继续处理剩余到期订单。
        for (Long id : orderMapper.findExpiredOrderIds(cutoff, 100)) {
            orderService.closeExpiredOrder(id, cutoff);
        }
    }
}
