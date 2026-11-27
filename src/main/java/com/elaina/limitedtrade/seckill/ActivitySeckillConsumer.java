package com.elaina.limitedtrade.seckill;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"app.order-message.enabled", "app.seckill.enabled"}, havingValue = "true", matchIfMissing = true)
public class ActivitySeckillConsumer {

    private final ActivitySeckillService seckillService;

    public ActivitySeckillConsumer(ActivitySeckillService seckillService) {
        this.seckillService = seckillService;
    }

    @RabbitListener(queues = "${app.order-message.name-prefix:limited-trade}.activity-seckill.ready")
    public void onSeckillOrder(ActivitySeckillMessage message) {
        seckillService.consumeMessage(message);
    }
}
