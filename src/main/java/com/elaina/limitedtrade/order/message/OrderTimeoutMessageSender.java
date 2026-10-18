package com.elaina.limitedtrade.order.message;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OrderTimeoutMessageSender {
    private final RabbitTemplate template;
    private final String exchange;

    public OrderTimeoutMessageSender(RabbitTemplate template,
            @Value("${app.order-message.name-prefix:limited-trade}") String prefix) {
        this.template = template;
        this.exchange = prefix + ".order-timeout.exchange";
    }

    public void send(OrderTimeoutMessage message) {
        // 每次物理发送独立关联；业务事件编号留在正文中。
        CorrelationData correlation = new CorrelationData();
        template.convertAndSend(exchange, "order-timeout.wait", message, converted -> {
            converted.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return converted;
        }, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(2, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new AmqpException("订单超时消息未获确认：" + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new AmqpException("订单超时消息不可路由：" + correlation.getReturned().getReplyText());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待订单超时消息确认时被中断", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AmqpException("等待订单超时消息确认失败", exception);
        }
    }
}
