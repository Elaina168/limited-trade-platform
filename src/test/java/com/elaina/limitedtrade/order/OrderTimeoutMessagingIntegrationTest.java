package com.elaina.limitedtrade.order;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.elaina.limitedtrade.order.message.*;
import com.elaina.limitedtrade.product.ProductCache;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.ListenerContainerIdleEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderTimeoutMessagingIntegrationTest {
    private static final String PREFIX = "limited-trade.test." + UUID.randomUUID();
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.order-message.enabled", () -> true);
        registry.add("app.order-message.name-prefix", () -> PREFIX);
        registry.add("app.order-message.wait-ttl-ms", () -> 200);
        registry.add("spring.rabbitmq.listener.simple.idle-event-interval", () -> "100ms");
    }

    @Autowired private OrderService orders;
    @Autowired private OrderTimeoutOutboxService publisher;
    @Autowired private OrderTimeoutOutboxScheduler relay;
    @Autowired private OrderTimeoutScheduler scanner;
    @Autowired private OrderTimeoutMessageSender sender;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private CachingConnectionFactory connection;
    @Autowired private RabbitListenerEndpointRegistry listeners;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderTimeoutOutboxMapper outbox;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private ProductCache cache;
    @Autowired private RabbitAdmin admin;
    @Autowired private AtomicLong idleAt;
    private boolean owned;

    @TestConfiguration(proxyBeanMethods = false)
    static class IdleConfiguration {
        @Bean AtomicLong idleAt() { return new AtomicLong(); }
        @Bean ApplicationListener<ListenerContainerIdleEvent> trackIdle(AtomicLong idleAt) {
            return event -> idleAt.set(System.nanoTime());
        }
    }

    @BeforeEach
    void prepare() {
        reset(cache);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=7803", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=9803", Integer.class));
        owned = true;
        jdbc.update("INSERT INTO app_user (id,username,password_hash,role) VALUES (7803,'messaging_buyer','unused','USER')");
        jdbc.update("INSERT INTO product (id,name,price,stock,status) VALUES (9803,'队列测试商品',20,10,'ON_SALE')");
        jdbc.update("""
                INSERT INTO activity (id,product_id,name,total_quantity,remaining_quantity,starts_at,ends_at)
                VALUES (9803,9803,'队列测试活动',5,5,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 HOUR))
                """);
        for (String suffix : new String[]{"wait", "ready", "failed"}) {
            admin.purgeQueue(queue(suffix), false);
        }
    }

    @AfterEach
    void cleanup() {
        reset(cache);
        if (owned) {
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT id FROM trade_order WHERE product_id=9803)");
            jdbc.update("DELETE FROM trade_order WHERE product_id=9803");
            jdbc.update("DELETE FROM activity WHERE id=9803");
            jdbc.update("DELETE FROM product WHERE id=9803");
            jdbc.update("DELETE FROM app_user WHERE id=7803");
        }
    }

    @AfterAll
    void removeTopology() {
        listeners.stop();
        for (String suffix : new String[]{"wait", "ready", "failed"}) {
            admin.deleteQueue(queue(suffix));
        }
        for (String suffix : new String[]{"exchange", "dlx", "failed.exchange"}) {
            admin.deleteExchange(PREFIX + ".order-timeout." + suffix);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ttlMessageClosesExpiredOrderAndDuplicateRestoresOnlyOnce(boolean activity) {
        long id = create(activity);
        expire(id);
        long event = event(id);
        relay.publishPending();
        assertEquals("SENT", eventStatus(event));
        awaitStatus(id, "CLOSED");
        sender.send(message(id));
        awaitDrained();
        assertStock(10, 5);
    }

    @Test
    void earlyMessageDoesNotExtendOrOverrideDatabaseDeadline() {
        long id = create(false);
        assertTrue(publisher.publishOne(event(id)));
        awaitDrained();
        assertEquals("PENDING_PAYMENT", status(id));
        assertStock(9, 5);
        expire(id);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> orders.payOrder(7803, id));
        scanner.closeExpiredOrders();
        assertEquals("CLOSED", status(id));
        assertStock(10, 5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "CANCELED", "CLOSED"})
    void terminalOrderIsUnchangedWhenMessageArrives(String terminal) {
        long id = create(true);
        if (terminal.equals("PAID")) {
            orders.payOrder(7803, id);
        } else if (terminal.equals("CANCELED")) {
            orders.cancelOrder(7803, id);
        } else {
            expire(id);
            scanner.closeExpiredOrders();
        }
        expire(id);
        assertTrue(publisher.publishOne(event(id)));
        awaitDrained();
        assertEquals(terminal, status(id));
        assertStock(terminal.equals("PAID") ? 9 : 10, terminal.equals("PAID") ? 4 : 5);
    }

    @Test
    void failedRestoreRollsBackThenDeadLettersAndCanReplayAfterRepair() {
        long id = create(true);
        expire(id);
        jdbc.update("UPDATE product SET stock=2147483647 WHERE id=9803");
        publisher.publishOne(event(id));
        await().atMost(Duration.ofSeconds(12)).until(() -> admin.getQueueInfo(queue("failed")).getMessageCount() == 1);
        Message failed = rabbit.receive(queue("failed"), 2000);
        assertNotNull(failed);
        assertEquals("rejected", failed.getMessageProperties().getXDeathHeader().getFirst().get("reason"));
        assertEquals("PENDING_PAYMENT", status(id));
        assertEquals(4, jdbc.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9803", Integer.class));
        jdbc.update("UPDATE product SET stock=9 WHERE id=9803");
        rabbit.send(PREFIX + ".order-timeout.dlx", "order-timeout.ready", failed);
        awaitStatus(id, "CLOSED");
        sender.send(message(id));
        awaitDrained();
        assertStock(10, 5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"json", "missing", "type"})
    void invalidExternalMessagesAreArchivedWithoutChangingOrder(String invalid) {
        long id = create(false);
        expire(id);
        var properties = new MessageProperties();
        properties.setContentType("application/json");
        String body = switch (invalid) {
            case "json" -> "{bad-json";
            case "missing" -> "{\"eventId\":1}";
            default -> {
                properties.setHeader("__TypeId__", "java.io.File");
                yield "{\"eventId\":1,\"orderId\":" + id + ",\"expiresAt\":\"2026-10-18T12:00:00\"}";
            }
        };
        rabbit.send(PREFIX + ".order-timeout.dlx", "order-timeout.ready",
                new Message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), properties));
        await().atMost(Duration.ofSeconds(12)).until(() -> admin.getQueueInfo(queue("failed")).getMessageCount() == 1);
        assertEquals("PENDING_PAYMENT", status(id));
        assertStock(9, 5);
    }

    @Test
    void stoppedListenerAllowsScannerToCloseAndLateMessageDoesNotRestoreAgain() {
        long id = create(true);
        expire(id);
        listeners.stop();
        try {
            publisher.publishOne(event(id));
            await().atMost(Duration.ofSeconds(5)).until(() -> admin.getQueueInfo(queue("ready")).getMessageCount() == 1);
            scanner.closeExpiredOrders();
            assertEquals("CLOSED", status(id));
        } finally {
            listeners.start();
        }
        awaitDrained();
        assertStock(10, 5);
    }

    @Test
    void afterCommitCacheFailureStillAllowsRetryToAcknowledgeWithoutDoubleRestore() {
        long id = create(true);
        expire(id);
        AtomicInteger failures = new AtomicInteger();
        doAnswer(call -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    failures.incrementAndGet();
                    throw new IllegalStateException("模拟缓存提交后失败");
                }
            });
            return null;
        }).when(cache).evictAfterCommit();
        publisher.publishOne(event(id));
        awaitStatus(id, "CLOSED");
        awaitDrained();
        assertEquals(1, failures.get());
        assertStock(10, 5);
        assertEquals(0, admin.getQueueInfo(queue("failed")).getMessageCount());
    }

    @Test
    void realUnroutableReturnDoesNotCountAsSuccessfulDelivery() {
        long id = create(false);
        admin.deleteQueue(queue("wait"));
        try {
            assertFalse(publisher.publishOne(event(id)));
            assertEquals("PENDING", eventStatus(event(id)));
            assertTrue(jdbc.queryForObject("SELECT last_error FROM order_timeout_outbox WHERE id=?", String.class, event(id)).contains("NO_ROUTE"));
        } finally {
            // 只重新声明本类持有的拓扑。
            admin.initialize();
        }
        jdbc.update("UPDATE order_timeout_outbox SET next_attempt_at=NOW() WHERE id=?", event(id));
        assertTrue(publisher.publishOne(event(id)));
        awaitDrained();
    }

    @Test
    void realConnectionRefusalKeepsPendingAndRestoredConnectionCanRetry() throws Exception {
        int closedPort;
        try (var reserved = new ServerSocket(0)) { closedPort = reserved.getLocalPort(); }
        long id = create(false);
        var unavailable = new CachingConnectionFactory("127.0.0.1", closedPort);
        unavailable.setConnectionTimeout(250);
        var disconnectedTemplate = new RabbitTemplate(unavailable);
        disconnectedTemplate.setMessageConverter(rabbit.getMessageConverter());
        var disconnectedPublisher = new OrderTimeoutOutboxService(outbox,
                new OrderTimeoutMessageSender(disconnectedTemplate, PREFIX));
        try {
            boolean delivered = new TransactionTemplate(transactions).execute(tx -> disconnectedPublisher.publishOne(event(id)));
            assertFalse(delivered);
            assertEquals("PENDING", eventStatus(event(id)));
        } finally {
            unavailable.destroy();
        }
        jdbc.update("UPDATE order_timeout_outbox SET next_attempt_at=NOW() WHERE id=?", event(id));
        assertTrue(publisher.publishOne(event(id)));
        awaitDrained();
    }

    private long create(boolean activity) {
        return orders.createOrder(7803, new CreateOrderRequest(9803L, 1, "mq-" + UUID.randomUUID(), activity ? 9803L : null)).order().getId();
    }
    private long event(long id) {
        return jdbc.queryForObject("SELECT id FROM order_timeout_outbox WHERE order_id=?", Long.class, id);
    }
    private OrderTimeoutMessage message(long id) {
        return new OrderTimeoutMessage(event(id), id, jdbc.queryForObject("SELECT expires_at FROM trade_order WHERE id=?", LocalDateTime.class, id));
    }
    private void expire(long id) {
        jdbc.update("UPDATE trade_order SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?", id);
    }
    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, id);
    }
    private String eventStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM order_timeout_outbox WHERE id=?", String.class, id);
    }
    private String queue(String suffix) { return PREFIX + ".order-timeout." + suffix; }
    private void awaitStatus(long id, String status) {
        await().atMost(Duration.ofSeconds(10)).until(() -> status.equals(status(id)));
    }
    private void awaitDrained() {
        // 等等待队列到期、处理队列取空，再等本轮业务回调结束后的空闲事件。
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(10)).until(() -> rabbit.execute(channel ->
                channel.queueDeclarePassive(queue("wait")).getMessageCount() == 0
                && channel.queueDeclarePassive(queue("ready")).getMessageCount() == 0));
        long drainedAt = System.nanoTime();
        await().atMost(Duration.ofSeconds(10)).until(() -> idleAt.get() > drainedAt);
    }
    private void assertStock(int stock, int quota) {
        assertEquals(stock, jdbc.queryForObject("SELECT stock FROM product WHERE id=9803", Integer.class));
        assertEquals(quota, jdbc.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9803", Integer.class));
    }
}
