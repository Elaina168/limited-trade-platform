package com.elaina.limitedtrade.order.message;

import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderTimeoutOutboxScheduler {
    private final OrderTimeoutOutboxMapper mapper;
    private final OrderTimeoutOutboxService publisher;
    private final boolean enabled;

    public OrderTimeoutOutboxScheduler(OrderTimeoutOutboxMapper mapper, OrderTimeoutOutboxService publisher,
            @Value("${app.order-message.enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.publisher = publisher;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.order-message.publish-delay-ms:3000}")
    public void publishIfEnabled() {
        if (enabled) {
            publishPending();
        }
    }

    public void publishPending() {
        for (Long id : mapper.findPendingIds(LocalDateTime.now().withNano(0), 20)) {
            publisher.publishOne(id);
        }
    }
}
