package com.elaina.limitedtrade.observability;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@EnableConfigurationProperties(OrderMessageMonitorProperties.class)
public class OrderMessageMonitor {
    private static final Logger log = LoggerFactory.getLogger(OrderMessageMonitor.class);
    private final OrderMessageMetricsMapper mapper;
    private final AmqpAdmin admin;
    private final String prefix;
    private final OrderMessageMonitorProperties properties;

    public OrderMessageMonitor(OrderMessageMetricsMapper mapper, AmqpAdmin admin,
            @Value("${app.order-message.name-prefix:limited-trade}") String prefix,
            OrderMessageMonitorProperties properties) {
        this.mapper = mapper;
        this.admin = admin;
        this.prefix = prefix;
        this.properties = properties;
    }

    public Snapshot snapshot() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        OrderMessageDatabaseStats database;
        try {
            database = mapper.snapshot(now);
        } catch (DataAccessException error) {
            log.atError().addKeyValue("event", "order_message_monitor_error")
                    .addKeyValue("source", "database").addKeyValue("exceptionType", error.getClass().getName())
                    .log("消息监控数据库采集失败");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "消息监控数据库不可用", error);
        }
        Queues queues;
        try {
            // 被动查询，不消费、清空或重新投递消息；messageCount只包含ready消息。
            var waiting = admin.getQueueInfo(prefix + ".order-timeout.wait");
            var ready = admin.getQueueInfo(prefix + ".order-timeout.ready");
            var failed = admin.getQueueInfo(prefix + ".order-timeout.failed");
            if (waiting == null || ready == null || failed == null) {
                log.atWarn().addKeyValue("event", "order_message_monitor_error").addKeyValue("source", "rabbitmq")
                        .log("消息监控所需队列不存在");
                queues = new Queues(false, null, null, null, null);
            } else {
                queues = new Queues(true, waiting.getMessageCount(), ready.getMessageCount(),
                        failed.getMessageCount(), ready.getConsumerCount());
            }
        } catch (AmqpException error) {
            log.atWarn().addKeyValue("event", "order_message_monitor_error").addKeyValue("source", "rabbitmq")
                    .addKeyValue("exceptionType", error.getClass().getName()).log("消息监控队列采集失败");
            queues = new Queues(false, null, null, null, null);
        }
        List<String> alerts = new ArrayList<>();
        if (database.outboxPending() > 0 && database.oldestPendingSeconds() >= properties.pendingAgeSeconds())
            alerts.add("OUTBOX_DELAYED");
        if (database.overdueOrders() > 0 && database.oldestOverdueSeconds() >= properties.overdueSeconds())
            alerts.add("ORDER_CLOSE_DELAYED");
        if (!queues.available()) {
            alerts.add("MESSAGE_QUEUES_UNAVAILABLE");
        } else {
            if (queues.failedReady() > 0) alerts.add("FAILED_MESSAGES");
            if (queues.readyReady() >= properties.readyMessages()) alerts.add("READY_BACKLOG");
        }
        return new Snapshot(now, database, queues, List.copyOf(alerts));
    }

    @Scheduled(fixedDelayString = "${app.order-message.monitor.interval-ms:30000}")
    public void sampleIfEnabled() {
        if (properties.enabled()) sample();
    }

    public Snapshot sample() {
        Snapshot snapshot = snapshot();
        var db = snapshot.database();
        var queues = snapshot.queues();
        var event = snapshot.alerts().isEmpty() ? log.atInfo() : log.atWarn();
        event.addKeyValue("event", "order_message_health")
                .addKeyValue("outboxPending", db.outboxPending()).addKeyValue("outboxRetrying", db.outboxRetrying())
                .addKeyValue("storedFailedAttempts", db.storedFailedAttempts())
                .addKeyValue("oldestPendingSeconds", db.oldestPendingSeconds())
                .addKeyValue("overdueOrders", db.overdueOrders()).addKeyValue("oldestOverdueSeconds", db.oldestOverdueSeconds())
                .addKeyValue("closedLastHour", db.closedLastHour())
                .addKeyValue("closedLastHourMaxDelaySeconds", db.closedLastHourMaxDelaySeconds())
                .addKeyValue("queuesAvailable", queues.available() ? 1 : 0)
                .addKeyValue("waitReady", queues.waitReady()).addKeyValue("readyReady", queues.readyReady())
                .addKeyValue("failedReady", queues.failedReady()).addKeyValue("readyConsumers", queues.readyConsumers())
                .addKeyValue("alertCount", snapshot.alerts().size()).addKeyValue("alerts", snapshot.alerts())
                .log("订单消息监控采样");
        return snapshot;
    }

    public record Queues(boolean available, Integer waitReady, Integer readyReady, Integer failedReady,
                         Integer readyConsumers) {
    }

    public record Snapshot(LocalDateTime collectedAt, OrderMessageDatabaseStats database, Queues queues,
                           List<String> alerts) {
    }
}
