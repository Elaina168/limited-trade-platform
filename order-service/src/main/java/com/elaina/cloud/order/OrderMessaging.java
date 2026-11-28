package com.elaina.cloud.order;

import feign.FeignException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Configuration(proxyBeanMethods = false)
class OrderMessageTopology {
    static final String EXCHANGE = "lab.order.timeout";
    static final String READY = "lab.order.timeout.ready";
    static final String DELAY = "lab.order.timeout.delay";

    @Bean Declarables orderQueues() {
        var exchange = new DirectExchange(EXCHANGE);
        var delay = QueueBuilder.durable(DELAY).deadLetterExchange(EXCHANGE).deadLetterRoutingKey(READY).build();
        var ready = QueueBuilder.durable(READY).build();
        return new Declarables(exchange, delay, ready,
            BindingBuilder.bind(delay).to(exchange).with(DELAY), BindingBuilder.bind(ready).to(exchange).with(READY));
    }
}

@Component
class OrderMessaging {
    private static final Logger LOG = LoggerFactory.getLogger(OrderMessaging.class);
    private final OrderOutbox outbox;
    private final OrderRepository orders;
    private final OrderService service;
    private final RabbitTemplate rabbit;
    private final ProductClient products;
    private final MarketingClient marketing;

    OrderMessaging(OrderOutbox outbox, OrderRepository orders, OrderService service, RabbitTemplate rabbit,
                   ProductClient products, MarketingClient marketing) {
        this.outbox = outbox;
        this.orders = orders;
        this.service = service;
        this.rabbit = rabbit;
        this.products = products;
        this.marketing = marketing;
    }

    @Scheduled(fixedDelayString = "${lab.order.outbox-scan-ms:250}")
    void publishTimeouts() {
        for (var work : outbox.publishable(Instant.now())) {
            try (var ignored = MDC.putCloseable(TraceContextFilter.KEY, work.traceId())) {
                try { publish(work); }
                catch (AmqpException | DataAccessException failed) { retry(work, failed); }
            }
        }
    }

    private void publish(OrderOutbox.Work work) {
        var correlation = new CorrelationData();
        long delayMs = Math.max(1, Duration.between(Instant.now(), work.dueAt()).toMillis() + 1);
        rabbit.convertAndSend(OrderMessageTopology.EXCHANGE, OrderMessageTopology.DELAY,
            Long.toString(work.orderId()), message -> {
                message.getMessageProperties().setExpiration(Long.toString(delayMs));
                if (work.traceId() != null) message.getMessageProperties().setHeader(TraceContextFilter.HEADER, work.traceId());
                return message;
            }, correlation);
        try {
            var confirm = correlation.getFuture().get(2, TimeUnit.SECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) throw new AmqpException("超时消息未确认路由");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AmqpException("等待消息确认时中断", interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            throw new AmqpException("等待超时消息确认失败", failed);
        }
        outbox.published(work.orderId());
    }

    @RabbitListener(queues = OrderMessageTopology.READY)
    void onTimeout(String message, @Header(name = TraceContextFilter.HEADER, required = false) String trace) {
        try (var ignored = MDC.putCloseable(TraceContextFilter.KEY, trace)) {
            long id = Long.parseLong(message);
            service.expire(id, "MQ");
            outbox.doneTimeout(id, Instant.now());
        }
    }

    @Scheduled(fixedDelayString = "${lab.order.outbox-scan-ms:250}")
    void settleDue() {
        Instant now = Instant.now();
        // 优先由延时消息触发，丢消息或 MQ 故障时由数据库工作补关。
        for (var work : outbox.due(now, now.minusMillis(1200))) {
            try (var ignored = MDC.putCloseable(TraceContextFilter.KEY, work.traceId())) {
                try {
                    settle(work);
                    outbox.done(work);
                } catch (FeignException | ResponseStatusException | DataAccessException failed) {
                    retry(work, failed);
                }
            }
        }
    }

    private void settle(OrderOutbox.Work work) {
        long id = work.orderId();
        switch (work.event()) {
            case TIMEOUT -> service.expire(id, "OUTBOX");
            case RELEASE -> release(id);
            case VERIFY -> {
                String status = orders.state(id);
                if (OrderRepository.MISSING.equals(status) || OrderRepository.CANCELED.equals(status)
                        || OrderRepository.CLOSED.equals(status)) release(id);
            }
        }
    }

    private void release(long orderId) {
        products.release(orderId);
        marketing.release(orderId);
    }

    private void retry(OrderOutbox.Work work, RuntimeException failed) {
        LOG.warn("event=order_outbox_retry orderId={} type={}", work.orderId(), work.event(), failed);
        outbox.retry(work, Instant.now().plusMillis(1000));
    }
}
