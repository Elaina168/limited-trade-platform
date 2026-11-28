package com.elaina.cloud.stock;

import feign.FeignException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockCheckMessagingTest {
    private final StockTasks tasks = mock(StockTasks.class);
    private final OrderStateClient orders = mock(OrderStateClient.class);
    private final StockCheckMessaging messages = new StockCheckMessaging(mock(RabbitTemplate.class),
            orders, tasks, "stock-test", 10_000);

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "CANCELED", "CLOSED"})
    void onlyAuthoritativeTerminalStateReturnsStock(String state) {
        when(tasks.openTrace(1L)).thenReturn(List.of("a".repeat(32)));
        when(orders.state(1L)).thenReturn(new OrderStateClient.State(state));

        messages.consume("1");

        verify(tasks).release(1L);
        verify(tasks, never()).retry(1L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"PENDING_PAYMENT", "UNKNOWN"})
    void unresolvedOrderMaintainsDurableRetry(String state) {
        when(tasks.openTrace(1L)).thenReturn(List.of("a".repeat(32)));
        when(orders.state(1L)).thenReturn(new OrderStateClient.State(state));
        when(tasks.dueOrderIds()).thenReturn(List.of(1L));

        messages.recover();

        verify(tasks).retry(1L);
        verify(tasks, never()).release(1L);
    }

    @Test
    void upstreamFailureRetriesInsteadOfReleasing() {
        when(tasks.openTrace(1L)).thenReturn(List.of("a".repeat(32)));
        when(orders.state(1L)).thenThrow(mock(FeignException.ServiceUnavailable.class));

        messages.consume("1");

        verify(tasks).retry(1L);
        verify(tasks, never()).release(1L);
    }

    @Test
    void paidStopsCheckWithoutReturningStock() {
        when(tasks.openTrace(1L)).thenReturn(List.of("a".repeat(32)));
        when(orders.state(1L)).thenReturn(new OrderStateClient.State("PAID"));

        messages.consume("1");

        verify(tasks).settlePaid(1L);
        verify(tasks, never()).release(1L);
    }
}
