package com.elaina.limitedtrade.order.message;

import com.elaina.limitedtrade.order.OrderService;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolationException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class OrderTimeoutConsumer {
    private final OrderService orders;
    private final Validator validator;

    public OrderTimeoutConsumer(OrderService orders, Validator validator) {
        this.orders = orders;
        this.validator = validator;
    }

    @RabbitListener(id = "orderTimeoutConsumer",
            queues = "${app.order-message.name-prefix:limited-trade}.order-timeout.ready",
            autoStartup = "${app.order-message.enabled:true}")
    public void onTimeout(OrderTimeoutMessage message) {
        var violations = validator.validate(message);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        orders.closeExpiredOrder(message.orderId(), LocalDateTime.now().withNano(0));
    }
}
