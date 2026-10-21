package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;
import java.util.List;
import com.elaina.limitedtrade.observability.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderMessageMonitorTest {
    private final OrderMessageMetricsMapper mapper = mock(OrderMessageMetricsMapper.class);
    private final AmqpAdmin admin = mock(AmqpAdmin.class);
    private final OrderMessageMonitor monitor = new OrderMessageMonitor(mapper, admin, "monitor-test",
            new OrderMessageMonitorProperties(false, 30000, 60, 100, 60));

    @BeforeEach void healthySources() {
        when(mapper.snapshot(any(LocalDateTime.class))).thenReturn(new OrderMessageDatabaseStats(0,0,0,0,0,0,0,0));
        when(admin.getQueueInfo("monitor-test.order-timeout.wait")).thenReturn(new QueueInformation("wait", 2, 0));
        when(admin.getQueueInfo("monitor-test.order-timeout.ready")).thenReturn(new QueueInformation("ready", 0, 1));
        when(admin.getQueueInfo("monitor-test.order-timeout.failed")).thenReturn(new QueueInformation("failed", 0, 0));
    }

    @Test void healthySnapshotOnlyPassivelyReadsQueues() {
        var snapshot = monitor.snapshot();
        assertTrue(snapshot.queues().available());
        assertEquals(2, snapshot.queues().waitReady());
        assertTrue(snapshot.alerts().isEmpty());
        verify(admin).getQueueInfo("monitor-test.order-timeout.wait");
        verify(admin).getQueueInfo("monitor-test.order-timeout.ready");
        verify(admin).getQueueInfo("monitor-test.order-timeout.failed");
        verifyNoMoreInteractions(admin);
    }

    @Test void thresholdsIncludeEqualityAndHistoricalFailuresDoNotAlertByThemselves() {
        when(mapper.snapshot(any())).thenReturn(new OrderMessageDatabaseStats(1,1,9,60,1,60,1,120));
        when(admin.getQueueInfo("monitor-test.order-timeout.ready")).thenReturn(new QueueInformation("ready",100,0));
        when(admin.getQueueInfo("monitor-test.order-timeout.failed")).thenReturn(new QueueInformation("failed",1,0));
        assertEquals(List.of("OUTBOX_DELAYED","ORDER_CLOSE_DELAYED","FAILED_MESSAGES","READY_BACKLOG"), monitor.sample().alerts());
        healthySources();
        when(mapper.snapshot(any())).thenReturn(new OrderMessageDatabaseStats(0,0,9,0,0,0,1,120));
        assertTrue(monitor.sample().alerts().isEmpty());
    }

    @Test void belowThresholdIsNotAnAlert() {
        when(mapper.snapshot(any())).thenReturn(new OrderMessageDatabaseStats(1,1,2,59,1,59,0,0));
        when(admin.getQueueInfo("monitor-test.order-timeout.ready")).thenReturn(new QueueInformation("ready",99,0));
        assertTrue(monitor.snapshot().alerts().isEmpty());
    }

    @Test void unavailableBrokerIsNotReportedAsZeroMessages() {
        when(admin.getQueueInfo("monitor-test.order-timeout.wait"))
                .thenThrow(new AmqpConnectException(new java.net.ConnectException("test connection refused")));
        var snapshot = monitor.sample();
        assertFalse(snapshot.queues().available());
        assertNull(snapshot.queues().failedReady());
        assertEquals(List.of("MESSAGE_QUEUES_UNAVAILABLE"), snapshot.alerts());
    }

    @Test void missingTopologyIsExplicitlyUnavailable() {
        when(admin.getQueueInfo("monitor-test.order-timeout.failed")).thenReturn(null);
        var snapshot = monitor.snapshot();
        assertFalse(snapshot.queues().available());
        assertNull(snapshot.queues().readyReady());
        assertTrue(snapshot.alerts().contains("MESSAGE_QUEUES_UNAVAILABLE"));
    }

    @Test void databaseFailureFailsFastWithoutHealthyFallback() {
        when(mapper.snapshot(any())).thenThrow(new DataAccessResourceFailureException("test failure"));
        var error = assertThrows(ResponseStatusException.class, monitor::snapshot);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verifyNoInteractions(admin);
    }

    @Test void disabledAutomaticSamplingDoesNotReadSources() {
        monitor.sampleIfEnabled();
        verifyNoInteractions(mapper, admin);
    }
}
