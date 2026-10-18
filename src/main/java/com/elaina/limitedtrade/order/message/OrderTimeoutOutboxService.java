package com.elaina.limitedtrade.order.message;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.amqp.AmqpException;
import java.time.LocalDateTime;

@Service
public class OrderTimeoutOutboxService {
    private final OrderTimeoutOutboxMapper mapper;
    private final OrderTimeoutMessageSender sender;

    public OrderTimeoutOutboxService(OrderTimeoutOutboxMapper mapper, OrderTimeoutMessageSender sender) {
        this.mapper = mapper;
        this.sender = sender;
    }

    @Transactional
    public boolean publishOne(long id) {
        OrderTimeoutOutbox event = mapper.lockPending(id, LocalDateTime.now().withNano(0));
        if (event == null) {
            return false;
        }
        try {
            sender.send(new OrderTimeoutMessage(event.getId(), event.getOrderId(), event.getExpiresAt()));
        } catch (AmqpException exception) {
            long delay = Math.min(60, 5L << Math.min(event.getAttemptCount(), 4));
            String error = exception.toString();
            mapper.markFailed(id, LocalDateTime.now().withNano(0).plusSeconds(delay),
                    error.substring(0, Math.min(500, error.length())));
            return false;
        }
        mapper.markSent(id, LocalDateTime.now().withNano(0));
        return true;
    }
}
