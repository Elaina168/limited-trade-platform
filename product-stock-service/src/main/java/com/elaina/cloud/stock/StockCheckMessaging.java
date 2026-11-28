package com.elaina.cloud.stock;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Configuration(proxyBeanMethods = false)
class StockQueueConfiguration {
    @Bean
    Declarables stockCheckQueues(@Value("${lab.stock.queue-prefix}") String prefix) {
        return new Declarables(
                QueueBuilder.durable(prefix + ".stock.check.delay")
                        .deadLetterExchange("").deadLetterRoutingKey(prefix + ".stock.check.ready").build(),
                QueueBuilder.durable(prefix + ".stock.check.ready").build());
    }
}

@Service
class StockCheckMessaging {
    private static final Logger log = LoggerFactory.getLogger(StockCheckMessaging.class);
    private final RabbitTemplate rabbit;
    private final OrderStateClient orders;
    private final StockTasks tasks;
    private final String delayQueue;
    private final long delayMs;

    StockCheckMessaging(RabbitTemplate rabbit, OrderStateClient orders, StockTasks tasks,
                        @Value("${lab.stock.queue-prefix}") String prefix,
                        @Value("${lab.stock.check-delay-ms}") long delayMs) {
        this.rabbit = rabbit;
        this.orders = orders;
        this.tasks = tasks;
        this.delayQueue = prefix + ".stock.check.delay";
        this.delayMs = delayMs;
    }

    // 调用方已完成数据库事务；发送失败时保留工作单的下次检查时间。
    @Async
    public void send(long orderId) {
        try {
            rabbit.convertAndSend("", delayQueue, Long.toString(orderId), msg -> {
                msg.getMessageProperties().setExpiration(Long.toString(delayMs));
                return msg;
            });
        } catch (AmqpException failure) {
            log.warn("stock_check_publish_failed orderId={}", orderId, failure);
        }
    }

    @RabbitListener(queues = "${lab.stock.queue-prefix}.stock.check.ready")
    public void consume(String orderId) {
        verify(Long.parseLong(orderId), "mq");
    }

    @Scheduled(fixedDelayString = "${lab.stock.recovery-interval-ms}")
    void recover() {
        for (long orderId : tasks.dueOrderIds()) verify(orderId, "database");
    }

    void verify(long orderId, String source) {
        var trace = tasks.openTrace(orderId);
        if (trace.isEmpty()) return;
        try (var ignored = MDC.putCloseable("traceId", trace.getFirst())) {
            String state;
            try {
                var response = orders.state(orderId);
                state = response == null ? null : response.status();
            } catch (FeignException failure) {
                tasks.retry(orderId);
                log.warn("stock_check_retry orderId={} source={} upstreamStatus={}", orderId, source, failure.status());
                return;
            }
            switch (state) {
                case "MISSING", "CANCELED", "CLOSED" -> tasks.release(orderId);
                case "PAID" -> tasks.settlePaid(orderId);
                case "PENDING_PAYMENT" -> tasks.retry(orderId);
                case null, default -> {
                    tasks.retry(orderId);
                    log.warn("stock_check_unknown_state orderId={} source={} orderState={}", orderId, source, state);
                    return;
                }
            }
            log.info("stock_check orderId={} source={} orderState={}", orderId, source, state);
        }
    }
}
