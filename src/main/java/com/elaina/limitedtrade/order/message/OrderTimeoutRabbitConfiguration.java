package com.elaina.limitedtrade.order.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OrderTimeoutRabbitConfiguration {
    @Bean
    Declarables orderTimeoutTopology(@Value("${app.order-message.name-prefix:limited-trade}") String prefix,
            @Value("${app.order-message.wait-ttl-ms:900000}") int ttl) {
        String waiting = prefix + ".order-timeout.wait";
        String ready = prefix + ".order-timeout.ready";
        String failed = prefix + ".order-timeout.failed";
        var entrance = new DirectExchange(prefix + ".order-timeout.exchange");
        var expired = new DirectExchange(prefix + ".order-timeout.dlx");
        var failures = new DirectExchange(prefix + ".order-timeout.failed.exchange");
        return new Declarables(entrance, expired, failures,
                QueueBuilder.durable(waiting).ttl(ttl).deadLetterExchange(expired.getName())
                        .deadLetterRoutingKey("order-timeout.ready").build(),
                QueueBuilder.durable(ready).deadLetterExchange(failures.getName())
                        .deadLetterRoutingKey("order-timeout.failed").build(),
                QueueBuilder.durable(failed).build(),
                new Binding(waiting, Binding.DestinationType.QUEUE, entrance.getName(), "order-timeout.wait", null),
                new Binding(ready, Binding.DestinationType.QUEUE, expired.getName(), "order-timeout.ready", null),
                new Binding(failed, Binding.DestinationType.QUEUE, failures.getName(), "order-timeout.failed", null));
    }

    @Bean
    Jackson2JsonMessageConverter orderTimeoutMessageConverter(ObjectMapper mapper) {
        var converter = new Jackson2JsonMessageConverter(mapper, "com.elaina.limitedtrade.order.message");
        // 若外部声明类型头，必须经过受信任类型检查，不能被方法参数推断覆盖。
        converter.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.TYPE_ID);
        return converter;
    }
}
