package com.elaina.limitedtrade.seckill;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SeckillProperties.class)
public class ActivitySeckillRabbitConfiguration {

    @Bean
    Declarables activitySeckillTopology(@Value("${app.order-message.name-prefix:limited-trade}") String prefix) {
        String ready = prefix + ".activity-seckill.ready";
        String failed = prefix + ".activity-seckill.failed";
        var exchange = new DirectExchange(prefix + ".activity-seckill.exchange");
        var dlx = new DirectExchange(prefix + ".activity-seckill.dlx");
        return new Declarables(
                exchange,
                dlx,
                QueueBuilder.durable(ready)
                        .deadLetterExchange(dlx.getName())
                        .deadLetterRoutingKey("activity-seckill.failed")
                        .build(),
                QueueBuilder.durable(failed).build(),
                new Binding(ready, Binding.DestinationType.QUEUE, exchange.getName(), "activity-seckill.create", null),
                new Binding(failed, Binding.DestinationType.QUEUE, dlx.getName(), "activity-seckill.failed", null));
    }
}
