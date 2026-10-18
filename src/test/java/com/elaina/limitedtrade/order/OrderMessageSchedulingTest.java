package com.elaina.limitedtrade.order;

import com.elaina.limitedtrade.config.TaskSchedulingConfiguration;
import com.elaina.limitedtrade.order.message.OrderTimeoutOutboxMapper;
import com.elaina.limitedtrade.order.message.OrderTimeoutOutboxScheduler;
import com.elaina.limitedtrade.order.message.OrderTimeoutOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderMessageSchedulingTest {
    @Test
    void disablingOrderScanDoesNotDisableSharedScheduling() {
        new ApplicationContextRunner().withUserConfiguration(TaskSchedulingConfiguration.class)
                .withPropertyValues("app.scheduling.enabled=true", "app.order-timeout.enabled=false")
                .run(context -> assertEquals(1, context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).size()));
        new ApplicationContextRunner().withUserConfiguration(TaskSchedulingConfiguration.class)
                .withPropertyValues("app.scheduling.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty()));
    }

    @Test
    void scanFlagControlsScheduledWrapperWhileManualEntryRemainsAvailable() {
        var mapper = mock(OrderMapper.class);
        var orders = mock(OrderService.class);
        var disabled = new OrderTimeoutScheduler(mapper, orders, false);
        disabled.scanIfEnabled();
        verifyNoInteractions(mapper);
        disabled.closeExpiredOrders();
        verify(mapper).findExpiredOrderIds(any(), eq(100));
        reset(mapper);
        new OrderTimeoutScheduler(mapper, orders, true).scanIfEnabled();
        verify(mapper).findExpiredOrderIds(any(), eq(100));
    }

    @Test
    void relayFlagControlsScheduledWrapperWhileManualEntryRemainsAvailable() {
        var mapper = mock(OrderTimeoutOutboxMapper.class);
        var publisher = mock(OrderTimeoutOutboxService.class);
        var disabled = new OrderTimeoutOutboxScheduler(mapper, publisher, false);
        disabled.publishIfEnabled();
        verifyNoInteractions(mapper);
        disabled.publishPending();
        verify(mapper).findPendingIds(any(), eq(20));
        reset(mapper);
        when(mapper.findPendingIds(any(), eq(20))).thenReturn(java.util.List.of(1L, 2L));
        new OrderTimeoutOutboxScheduler(mapper, publisher, true).publishIfEnabled();
        verify(publisher).publishOne(1L);
        verify(publisher).publishOne(2L);
    }
}
