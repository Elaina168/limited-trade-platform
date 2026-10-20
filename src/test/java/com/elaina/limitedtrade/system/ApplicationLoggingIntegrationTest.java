package com.elaina.limitedtrade.system;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.order.CreateOrderRequest;
import com.elaina.limitedtrade.order.OrderService;
import com.elaina.limitedtrade.user.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "logging.file.name=target/test-logs/application-logging.log")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApplicationLoggingIntegrationTest.FailureConfiguration.class)
class ApplicationLoggingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired OrderService orders;
    @Autowired PlatformTransactionManager transactions;
    final Logger logger = (Logger) LoggerFactory.getLogger("com.elaina.limitedtrade");
    final ListAppender<ILoggingEvent> capture = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach void captureLogs() { capture.start(); logger.addAppender(capture); }
    @AfterEach void detach() { logger.detachAppender(capture); capture.stop(); MDC.clear(); }

    @Test void requestTraceCoversPublicAndSecurityFailuresAndRestoresThreadContext() throws Exception {
        MDC.put("caller", "preserved");
        var first = mvc.perform(get("/api/ping").header("X-Trace-Id", "untrusted-client-value")
                .param("password", "private-query-value")).andExpect(status().isOk()).andReturn();
        String trace = first.getResponse().getHeader("X-Trace-Id");
        assertNotNull(trace);
        assertTrue(trace.matches("[0-9a-f]{32}"));
        var event = httpEvents().getFirst();
        assertEquals(trace, event.getMDCPropertyMap().get("traceId"));
        assertEquals("/api/ping", value(event, "route"));
        assertEquals(200, value(event, "status"));
        assertTrue(((Number)value(event, "durationMs")).longValue() >= 0);
        assertNull(MDC.get("traceId"));
        assertEquals("preserved", MDC.get("caller"));
        var denied = mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized()).andReturn();
        assertNotEquals(trace, denied.getResponse().getHeader("X-Trace-Id"));
        assertEquals(401, value(httpEvents().getLast(), "status"));
        mvc.perform(get("/api/admin/products").with(user("ordinary_user"))).andExpect(status().isForbidden());
        assertEquals(403, value(httpEvents().getLast(), "status"));
        mvc.perform(get("/api/orders/987654321").with(user(new PlatformUserDetails(new AppUser(22001L,"logging_buyer","unused","USER")))))
                .andExpect(status().isNotFound());
        assertEquals("/api/orders/{id}", value(httpEvents().getLast(), "route"));
        assertEquals(404, value(httpEvents().getLast(), "status"));
        mvc.perform(post("/api/auth/login").with(csrf()).param("username", "private-user")
                .param("password", "private-password")).andExpect(status().isUnauthorized());
        assertEquals("/api/auth/login", value(httpEvents().getLast(), "route"));
        assertFalse(json.writeValueAsString(httpEvents().stream().map(ILoggingEvent::getKeyValuePairs).toList()).contains("private-"));
    }

    @Test void unhandledExceptionIsRethrownAndLogsSafeRootStackWithoutSecretMessage() throws Exception {
        assertThrows(ServletException.class, () -> mvc.perform(get("/api/activities/logging-test-failure")));
        var event = httpEvents().getLast();
        assertEquals(500, value(event, "status"));
        assertEquals("java.lang.IllegalStateException", value(event, "exceptionType"));
        assertTrue(value(event, "exceptionStack").toString().contains("FailureEndpoint.fail"));
        assertFalse(json.writeValueAsString(event.getKeyValuePairs()).contains("private-exception-message"));
        assertNull(event.getThrowableProxy());
        assertNull(MDC.get("traceId"));
    }

    @Test void structuredFileContainsTraceAndRollingPolicyIsConfigured() throws Exception {
        var response = mvc.perform(get("/api/ping")).andExpect(status().isOk()).andReturn().getResponse();
        String trace = response.getHeader("X-Trace-Id");
        assertNotNull(trace);
        var records = Files.readAllLines(Path.of("target/test-logs/application-logging.log"));
        var record = records.stream().filter(line -> line.contains(trace)).map(line -> {
            try { return json.readTree(line); } catch (Exception e) { throw new AssertionError(e); }
        }).filter(node -> trace.equals(node.path("traceId").asText()) && "http_request".equals(node.path("event").asText())).findFirst().orElseThrow();
        assertEquals(200, record.path("status").asInt());
        assertEquals("/api/ping", record.path("route").asText());
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var file = (ch.qos.logback.core.rolling.RollingFileAppender<?>) root.getAppender("FILE");
        var policy = (ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy<?>) file.getRollingPolicy();
        assertEquals(7, policy.getMaxHistory());
        assertTrue(policy.getFileNamePattern().endsWith(".gz"));
    }

    @Test void orderEventsFollowRealCommitAndRetriesDoNotDuplicateTransitions() throws Exception {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=22001", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=22001", Integer.class));
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES(22001,'logging_buyer','unused','USER')");
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES(22001,'日志商品',20,10,'ON_SALE')");
        jdbc.update("INSERT INTO user_address(id,user_id,recipient_name,phone,province,city,district,detail_address) VALUES(22001,22001,'private-recipient','13800138000','省','市','区','private-address')");
        var buyer = user(new PlatformUserDetails(new AppUser(22001L, "logging_buyer", "unused", "USER")));
        var admin = user(new PlatformUserDetails(new AppUser(22002L, "logging_admin", "unused", "ADMIN")));
        try {
            var body = Map.of("productId",22001,"quantity",1,"addressId",22001,"requestId","private-purchase-key");
            var created = mvc.perform(post("/api/orders").with(buyer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body))).andExpect(status().isCreated()).andReturn().getResponse();
            long id = json.readTree(created.getContentAsString()).path("id").asLong();
            assertEquals(1, orderEvents().size());
            assertEquals(created.getHeader("X-Trace-Id"), orderEvents().getFirst().getMDCPropertyMap().get("traceId"));
            for (int i=0;i<2;i++) {
                mvc.perform(post("/api/orders").with(buyer).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body))).andExpect(status().isOk());
            }
            for (int i=0;i<2;i++) {
                mvc.perform(post("/api/orders/"+id+"/pay").with(buyer).with(csrf())).andExpect(status().isOk());
            }
            for (int i=0;i<2;i++) {
                mvc.perform(post("/api/admin/orders/"+id+"/ship").with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"private-carrier\",\"trackingNo\":\"private-tracking\"}")).andExpect(status().isOk());
            }
            for (int i=0;i<2;i++) {
                mvc.perform(post("/api/orders/"+id+"/receive").with(buyer).with(csrf())).andExpect(status().isOk());
            }
            assertEquals(List.of("order_created","order_paid","order_shipped","order_completed"), orderEvents().stream().map(e -> value(e,"event")).toList());
            var canceled = orders.createOrder(22001, new CreateOrderRequest(22001L,1,"logging-cancel",null,null,null)).order();
            orders.cancelOrder(22001,canceled.getId()); orders.cancelOrder(22001,canceled.getId());
            var expired = orders.createOrder(22001, new CreateOrderRequest(22001L,1,"logging-expire",null,null,null)).order();
            jdbc.update("UPDATE trade_order SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE id=?",expired.getId());
            assertTrue(orders.closeExpiredOrder(expired.getId(), LocalDateTime.now()));
            assertFalse(orders.closeExpiredOrder(expired.getId(), LocalDateTime.now()));
            int count = orderEvents().size();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                orders.createOrder(22001,new CreateOrderRequest(22001L,1,"logging-rollback",null,null,null));
                assertEquals(count,orderEvents().size(),"提交前不能宣称成功");
                status.setRollbackOnly();
            });
            assertEquals(count,orderEvents().size(),"回滚不能产生成交日志");
            assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                // 模拟已有缓存失效等提交后回调失败，数据库此时仍已提交。
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCommit() { throw new IllegalStateException("controlled-after-commit-failure"); }
                        });
                orders.createOrder(22001,new CreateOrderRequest(22001L,1,"logging-committed-callback-failure",null,null,null));
            }));
            assertEquals(count+1,orderEvents().size(),"其他提交后回调失败也应保留已成交日志");
            assertEquals(1,orderEvents().stream().filter(e -> "order_canceled".equals(value(e,"event"))).count());
            assertEquals(1,orderEvents().stream().filter(e -> "order_closed".equals(value(e,"event"))).count());
            assertFalse(json.writeValueAsString(capture.list.stream().map(ILoggingEvent::getKeyValuePairs).toList()).contains("private-"));
        } finally {
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN(SELECT id FROM trade_order WHERE user_id=22001)");
            jdbc.update("DELETE FROM trade_order WHERE user_id=22001");
            jdbc.update("DELETE FROM user_address WHERE user_id=22001");
            jdbc.update("DELETE FROM product WHERE id=22001");
            jdbc.update("DELETE FROM app_user WHERE id=22001");
        }
    }

    List<ILoggingEvent> httpEvents() { return capture.list.stream().filter(e -> "http_request".equals(value(e,"event"))).toList(); }
    List<ILoggingEvent> orderEvents() { return capture.list.stream().filter(e -> String.valueOf(value(e,"event")).startsWith("order_")).toList(); }
    Object value(ILoggingEvent event, String key) {
        return event.getKeyValuePairs() == null ? null : event.getKeyValuePairs().stream().filter(p -> p.key.equals(key)).map(p -> p.value).findFirst().orElse(null);
    }
    @TestConfiguration static class FailureConfiguration {
        @Bean FailureEndpoint failureEndpoint() { return new FailureEndpoint(); }
    }
    @RestController static class FailureEndpoint {
        @GetMapping("/api/activities/logging-test-failure")
        void fail() { throw new IllegalStateException("private-exception-message"); }
    }
}
