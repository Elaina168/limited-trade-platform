package com.elaina.limitedtrade.observability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "消息运维")
@RestController
public class AdminOrderMessageController {
    private final OrderMessageMonitor monitor;

    public AdminOrderMessageController(OrderMessageMonitor monitor) {
        this.monitor = monitor;
    }

    @Operation(summary = "查询订单消息监控快照", description = "只读；队列计数仅含待投递消息，不含unacked。"
            + "RabbitMQ不可用时available=false、计数为null并列出告警；数据库不可用返回503。")
    @GetMapping("/api/admin/operations/order-messages")
    public OrderMessageMonitor.Snapshot snapshot() {
        return monitor.snapshot();
    }
}
