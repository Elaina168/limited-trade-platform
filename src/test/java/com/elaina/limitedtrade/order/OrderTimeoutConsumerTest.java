package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;
import com.elaina.limitedtrade.order.message.OrderTimeoutConsumer;
import com.elaina.limitedtrade.order.message.OrderTimeoutMessage;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderTimeoutConsumerTest {
    @Test
    void messageBoundaryRejectsInvalidFieldsBeforeCallingOrders() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var orders = mock(OrderService.class);
            var consumer = new OrderTimeoutConsumer(orders, factory.getValidator());
            for (var message : new OrderTimeoutMessage[]{
                    new OrderTimeoutMessage(null, 1L, LocalDateTime.now()),
                    new OrderTimeoutMessage(1L, -1L, LocalDateTime.now()),
                    new OrderTimeoutMessage(1L, 1L, null)}) {
                assertThrows(ConstraintViolationException.class, () -> consumer.onTimeout(message));
            }
            verifyNoInteractions(orders);
        }
    }

    @Test
    void validMessageUsesCurrentTimeAndPropagatesBusinessFailure() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var orders = mock(OrderService.class);
            var consumer = new OrderTimeoutConsumer(orders, factory.getValidator());
            var before = LocalDateTime.now().withNano(0);
            var message = new OrderTimeoutMessage(1L, 2L, before.plusDays(1));
            consumer.onTimeout(message);
            verify(orders).closeExpiredOrder(eq(2L), argThat(time -> !time.isBefore(before)
                    && !time.isAfter(LocalDateTime.now())));
            doThrow(new IllegalStateException("database")).when(orders).closeExpiredOrder(eq(2L), any());
            assertThrows(IllegalStateException.class, () -> consumer.onTimeout(message));
        }
    }
}
