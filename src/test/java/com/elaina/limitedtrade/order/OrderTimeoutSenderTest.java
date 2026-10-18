package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.elaina.limitedtrade.order.message.OrderTimeoutMessage;
import com.elaina.limitedtrade.order.message.OrderTimeoutMessageSender;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderTimeoutSenderTest {

    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final OrderTimeoutMessage message = new OrderTimeoutMessage(1L, 2L, LocalDateTime.of(2026, 10, 18, 12, 0));

    @Test
    void ackWithoutReturnSucceedsAndEveryAttemptUsesFreshCorrelation() {
        List<String> correlations = new ArrayList<>();
        doAnswer(call -> {
            assertEquals("limited-trade.test.order-timeout.exchange", call.getArgument(0));
            assertEquals("order-timeout.wait", call.getArgument(1));
            assertEquals(message, call.getArgument(2));
            MessagePostProcessor processor = call.getArgument(3);
            assertEquals(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT,
                    processor.postProcessMessage(new Message(new byte[0], new MessageProperties())).getMessageProperties().getDeliveryMode());
            CorrelationData correlation = call.getArgument(4);
            correlations.add(correlation.getId());
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
        var sender = new OrderTimeoutMessageSender(template, "limited-trade.test");
        assertDoesNotThrow(() -> sender.send(message));
        assertDoesNotThrow(() -> sender.send(message));
        assertNotEquals(correlations.get(0), correlations.get(1));
    }

    @Test
    void ackWithReturnIsStillFailure() {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(4);
            correlation.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", "test", "missing"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
        assertThrows(AmqpException.class, () -> new OrderTimeoutMessageSender(template, "test").send(message));
    }

    @Test
    void nackIsFailure() {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker rejection"));
            return null;
        }).when(template).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
        assertThrows(AmqpException.class, () -> new OrderTimeoutMessageSender(template, "test").send(message));
    }

    @Test
    void missingConfirmationTimesOutInsteadOfClaimingSuccess() {
        assertThrows(AmqpException.class, () -> new OrderTimeoutMessageSender(template, "test").send(message));
    }

    @Test
    void interruptionRemainsVisibleAndPreservesInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IllegalStateException.class, () -> new OrderTimeoutMessageSender(template, "test").send(message));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
