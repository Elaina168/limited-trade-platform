package com.elaina.limitedtrade.system;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.elaina.limitedtrade.order.OrderTimeoutScheduler;
import com.elaina.limitedtrade.order.message.OrderTimeoutMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "logging.file.name=tools/trade-drill/controller.log")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// 故障场景会暂停整个本机Broker应用，必须由调用者显式选择执行。
@EnabledIfSystemProperty(named = "native.trade.drill", matches = "true")
class NativeTradeDrillIntegrationTest {
    static final String TOKEN = UUID.randomUUID().toString().replace("-", "");
    static final String PREFIX = "limited-trade.drill." + TOKEN;
    static final String REDIS = "limited-trade:drill:" + TOKEN;
    static final Path ROOT = Path.of("").toAbsolutePath();
    static final Path RUNTIME = ROOT.resolve("tools/trade-drill");
    static final String EDGE = "http://127.0.0.1:18082";
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final List<Process> nodes = new ArrayList<>();
    final Map<String, Long> before = new LinkedHashMap<>();
    final String buyerName = "drill_b_" + TOKEN;
    final String adminName = "drill_a_" + TOKEN;
    final String password = UUID.randomUUID().toString();
    long buyerId;
    long adminId;
    long productId;
    long addressId;
    boolean topologyOwned;
    boolean databaseOwned;
    boolean nginxOwned;
    boolean brokerStopped;
    Session buyer;
    Session admin;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @Autowired Environment environment;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired CachingConnectionFactory connection;
    @Autowired StringRedisTemplate redis;
    @Autowired OrderTimeoutScheduler scanner;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("app.order-message.name-prefix", () -> PREFIX);
        properties.add("app.order-message.wait-ttl-ms", () -> 1000);
    }

    @BeforeAll void startIsolatedEnvironment() throws Exception {
        Files.createDirectories(RUNTIME);
        assertTrue(Files.exists(ROOT.resolve("deploy/nginx-trade-drill/nginx.conf")), "缺少独立交易入口配置");
        Path nginxPid = ROOT.resolve("deploy/nginx-trade-drill/logs/nginx.pid");
        if (Files.exists(nginxPid)) {
            assertTrue(Files.readString(nginxPid).isBlank(), "交易入口已有PID记录，拒绝接管已有Nginx");
        }
        assertEquals("limited_trade_platform_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertNotEquals(environment.getRequiredProperty("mq.virtual-host"), environment.getRequiredProperty("mq.test-virtual-host"));
        for (String table : jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'", String.class)) {
            long count = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Long.class);
            before.put(table, count);
            assertEquals(0, count, "测试库非空，拒绝开启自动发布及清理：" + table);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()", Integer.class));
        databaseOwned = true;
        for (int port : new int[]{8083, 8084, 18082}) {
            try (var socket = new ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1", port)); }
        }
        topologyOwned = true;
        rabbitAdmin.initialize();
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,?,'USER')", buyerName, encoder.encode(password));
        buyerId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, buyerName);
        jdbc.update("INSERT INTO app_user(username,password_hash,role) VALUES (?,?,'ADMIN')", adminName, encoder.encode(password));
        adminId = jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, adminName);
        jdbc.update("INSERT INTO product(name,price,stock,status) VALUES (?,20,10,'ON_SALE')", "原生交易夹具_" + TOKEN);
        productId = jdbc.queryForObject("SELECT id FROM product WHERE name=?", Long.class, "原生交易夹具_" + TOKEN);
        jdbc.update("INSERT INTO user_address(user_id,recipient_name,phone,province,city,district,detail_address) VALUES (?,'测试收货人','13800138000','浙江','杭州','西湖','测试地址')", buyerId);
        addressId = jdbc.queryForObject("SELECT id FROM user_address WHERE user_id=?", Long.class, buyerId);
        startNode("trade-drill-a", 8083);
        startNode("trade-drill-b", 8084);
        nginxOwned = true;
        runScript("start-nginx.ps1", "-Mode", "trade-drill");
    }

    void startNode(String name, int port) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var builder = new ProcessBuilder(java.toString(), "-Xms64m", "-Xmx256m", "-jar",
                ROOT.resolve("target/limited-trade-platform-0.0.1-SNAPSHOT.jar").toString(),
                "--spring.profiles.active=shared-session", "--server.address=127.0.0.1", "--server.port=" + port,
                "--app.scheduling.enabled=true", "--app.order-message.enabled=true", "--app.order-timeout.enabled=false",
                "--app.order-message.publish-delay-ms=500", "--app.order-message.wait-ttl-ms=1000");
        builder.directory(ROOT.toFile());
        builder.environment().putAll(Map.of(
                "SPRING_DATASOURCE_URL", environment.getRequiredProperty("spring.datasource.url"),
                "SPRING_RABBITMQ_VIRTUAL_HOST", environment.getRequiredProperty("mq.test-virtual-host"),
                "SPRING_SESSION_REDIS_NAMESPACE", REDIS + ":sessions",
                "APP_ORDER_MESSAGE_NAME_PREFIX", PREFIX,
                "APP_PRODUCT_CACHE_KEY", REDIS + ":products",
                "APP_ORDER_RATE_LIMIT_KEY_PREFIX", REDIS + ":rate",
                "APP_INSTANCE_ID", name,
                "APP_LOG_FILE", ROOT.resolve("logs/" + name + ".log").toString()));
        builder.redirectOutput(RUNTIME.resolve(name + ".stdout.log").toFile());
        builder.redirectError(RUNTIME.resolve(name + ".stderr.log").toFile());
        Process process = builder.start();
        nodes.add(process);
        await().atMost(Duration.ofSeconds(40)).ignoreExceptionsMatching(error -> error instanceof java.io.IOException)
                .until(() -> {
                    assertTrue(process.isAlive(), "JVM提前退出，请检查tools/trade-drill组件日志");
                    return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/ping"))
                            .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                });
    }

    @BeforeEach void login() throws Exception {
        redis.delete(REDIS + ":rate:user:" + buyerId);
        buyer = new Session();
        admin = new Session();
        buyer.login(buyerName);
        admin.login(adminName);
    }

    @AfterEach void removeOrders() throws Exception {
        assertAll("每个场景都执行完整清理并保留所有失败", () -> { if (buyer != null) buyer.logout(); },
                () -> { if (admin != null) admin.logout(); }, () -> {
                    if (buyerId != 0) {
                        jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT id FROM trade_order WHERE user_id=?)", buyerId);
                        jdbc.update("DELETE FROM trade_order WHERE user_id=?", buyerId);
                    }
                    if (productId != 0) jdbc.update("UPDATE product SET stock=10 WHERE id=?", productId);
                });
    }

    @Test @Order(1) void purchasePaymentShipmentAndReceiptAcrossProcesses() throws Exception {
        var created = createOrder("normal", 2);
        long id = json.readTree(created.body()).path("id").asLong();
        for (String node : List.of("http://127.0.0.1:8083", "http://127.0.0.1:8084")) {
            var replay = buyer.post(node, "/api/orders", orderBody("normal", 2), 200);
            assertEquals(id, json.readTree(replay.body()).path("id").asLong());
        }
        assertEquals(8, stock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_timeout_outbox WHERE order_id=?", Integer.class, id));
        var paid = buyer.post(EDGE, "/api/orders/" + id + "/pay", null, 200);
        assertEquals("PAID", json.readTree(paid.body()).path("status").asText());
        var shipped = admin.post(EDGE, "/api/admin/orders/" + id + "/ship",
                json.writeValueAsString(Map.of("carrier", "测试承运方", "trackingNo", "DRILL-" + TOKEN)), 200);
        assertEquals("SHIPPED", json.readTree(shipped.body()).path("status").asText());
        var received = buyer.post(EDGE, "/api/orders/" + id + "/receive", null, 200);
        JsonNode body = json.readTree(received.body());
        assertEquals("COMPLETED", body.path("status").asText());
        assertTrue(body.path("shipment").path("receivedAt").isTextual());
        assertEquals(8, stock());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM order_shipment WHERE order_id=?", Integer.class, id));
        String trace = paid.headers().firstValue("X-Trace-Id").orElseThrow();
        awaitCentral("{app=\"limited-trade\"} | json | traceId=\"" + trace + "\"",
                rows -> rows.stream().anyMatch(row -> "nginx".equals(row.path("source").asText()))
                        && rows.stream().anyMatch(row -> "order_paid".equals(row.path("event").asText())));
        awaitAcks(1);
    }

    @Test @Order(2) void realTtlConsumerAndDuplicateRestoreInventoryOnce() throws Exception {
        long baseline = queueAcks();
        var created = createOrder("ttl", 3);
        long id = json.readTree(created.body()).path("id").asLong();
        assertEquals(7, stock());
        expire(id);
        awaitStatus(id, "CLOSED");
        assertEquals("SENT", outboxStatus(id));
        assertEquals(10, stock());
        awaitAcks(baseline + 1);
        long eventId = jdbc.queryForObject("SELECT id FROM order_timeout_outbox WHERE order_id=?", Long.class, id);
        var correlation = new CorrelationData();
        rabbit.convertAndSend(PREFIX + ".order-timeout.dlx", "order-timeout.ready",
                new OrderTimeoutMessage(eventId, id, LocalDateTime.now().minusSeconds(1)), correlation);
        assertTrue(correlation.getFuture().get(3, TimeUnit.SECONDS).isAck());
        awaitAcks(baseline + 2);
        assertEquals(10, stock());
        assertEquals("CLOSED", status(id));
        awaitCentral("{app=\"limited-trade\",source=\"java\",instance=~\"trade-drill-.*\"} | json | event=\"order_closed\" | orderId=" + id,
                rows -> rows.size() == 1);
    }

    @Test @Order(3) void brokerOutagePersistsIntentThenRetriesWithoutDoubleRestoration() throws Exception {
        long pending;
        long scanned;
        try {
            brokerStopped = true;
            controlBroker("stop_app");
            pending = json.readTree(createOrder("offline-pending", 2).body()).path("id").asLong();
            scanned = json.readTree(createOrder("offline-scan", 1).body()).path("id").asLong();
            assertEquals(7, stock());
            for (long id : new long[]{pending, scanned}) {
                await().atMost(Duration.ofSeconds(15)).until(() -> jdbc.queryForObject(
                        "SELECT attempt_count FROM order_timeout_outbox WHERE order_id=?", Long.class, id) > 0);
                assertEquals("PENDING", outboxStatus(id));
                assertNotNull(jdbc.queryForObject("SELECT last_error FROM order_timeout_outbox WHERE order_id=?", String.class, id));
            }
            expire(scanned);
            scanner.closeExpiredOrders();
            assertEquals("CLOSED", status(scanned));
            assertEquals("PENDING_PAYMENT", status(pending));
            assertEquals(8, stock());
            expire(pending);
        } finally {
            if (brokerStopped) {
                controlBroker("start_app");
                brokerStopped = false;
                connection.resetConnection();
            }
        }
        awaitStatus(pending, "CLOSED");
        for (long id : new long[]{pending, scanned}) {
            await().atMost(Duration.ofSeconds(35)).until(() -> "SENT".equals(outboxStatus(id)));
            assertNull(jdbc.queryForObject("SELECT last_error FROM order_timeout_outbox WHERE order_id=?", String.class, id));
            assertTrue(jdbc.queryForObject("SELECT attempt_count FROM order_timeout_outbox WHERE order_id=?", Long.class, id) >= 2);
        }
        awaitAcks(2);
        assertEquals(10, stock());
        awaitCentral("{app=\"limited-trade\",source=\"java\",instance=~\"trade-drill-.*\"} | json | event=\"order_closed\" | orderId=" + pending,
                rows -> rows.size() == 1);
    }

    HttpResponse<String> createOrder(String requestId, int quantity) throws Exception {
        return buyer.post(EDGE, "/api/orders", orderBody(requestId, quantity), 201);
    }

    String orderBody(String requestId, int quantity) throws Exception {
        return json.writeValueAsString(Map.of("productId", productId, "quantity", quantity,
                "requestId", "drill-" + TOKEN + "-" + requestId, "addressId", addressId));
    }

    int stock() { return jdbc.queryForObject("SELECT stock FROM product WHERE id=?", Integer.class, productId); }
    String status(long id) { return jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, id); }
    String outboxStatus(long id) { return jdbc.queryForObject("SELECT status FROM order_timeout_outbox WHERE order_id=?", String.class, id); }
    void expire(long id) { jdbc.update("UPDATE trade_order SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=? AND user_id=?", id, buyerId); }
    void awaitStatus(long id, String expected) {
        await().atMost(Duration.ofSeconds(40)).until(() -> expected.equals(status(id)));
    }

    JsonNode queueInfo() throws Exception {
        String credentials = environment.getRequiredProperty("mq.username") + ":" + environment.getRequiredProperty("mq.password");
        String url = "http://127.0.0.1:15672/api/queues/" + encode(environment.getRequiredProperty("mq.test-virtual-host"))
                + "/" + encode(PREFIX + ".order-timeout.ready");
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "读取本次MQ队列状态失败");
        return json.readTree(response.body());
    }

    long queueAcks() throws Exception { return queueInfo().path("message_stats").path("ack").asLong(); }
    void awaitAcks(long minimum) {
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            JsonNode queue = queueInfo();
            return queue.path("message_stats").path("ack").asLong() >= minimum
                    && queue.path("messages_ready").asInt() == 0 && queue.path("messages_unacknowledged").asInt() == 0;
        });
    }

    void awaitCentral(String expression, java.util.function.Predicate<List<JsonNode>> check) throws Exception {
        JsonNode credentials = json.readTree(ROOT.resolve("tools/observability-runtime/grafana-credentials.json").toFile());
        String authorization = Base64.getEncoder().encodeToString((credentials.path("username").asText() + ":"
                + credentials.path("password").asText()).getBytes(StandardCharsets.UTF_8));
        String url = "http://127.0.0.1:3000/api/datasources/proxy/uid/limited-trade-loki/loki/api/v1/query_range?query="
                + encode(expression) + "&since=1h&limit=100";
        await().atMost(Duration.ofSeconds(25)).until(() -> {
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Basic " + authorization).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            List<JsonNode> rows = new ArrayList<>();
            for (JsonNode stream : json.readTree(response.body()).path("data").path("result")) {
                for (JsonNode value : stream.path("values")) {
                    var body = json.readTree(value.get(1).asText()).deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) body).set("source", stream.path("stream").path("source"));
                    rows.add(body);
                }
            }
            return check.test(rows);
        });
    }

    void runScript(String script, String... args) throws Exception {
        var command = new ArrayList<>(List.of("pwsh.exe", "-NoProfile", "-File", ROOT.resolve("scripts/" + script).toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(RUNTIME.resolve("control.log").toFile())).start();
        if (!process.waitFor(40, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            fail("控制脚本超时，请检查tools/trade-drill/control.log");
        }
        assertEquals(0, process.exitValue(), "控制脚本失败，请检查tools/trade-drill/control.log");
    }

    void controlBroker(String action) throws Exception {
        var builder = new ProcessBuilder("pwsh.exe", "-NoProfile", "-Command",
                "& $env:DRILL_RABBIT_CTL $env:DRILL_RABBIT_ACTION; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }");
        builder.environment().putAll(Map.of("ERLANG_HOME", ROOT.resolve("tools/Erlang OTP").toString(),
                "RABBITMQ_BASE", ROOT.resolve("tools/rabbitmq-runtime").toString(),
                "RABBITMQ_NODENAME", "limited_trade@localhost", "ERL_EPMD_ADDRESS", "127.0.0.1",
                "RABBITMQ_CTL_ERL_ARGS", "+S 2:2", "DRILL_RABBIT_CTL", ROOT.resolve("tools/rabbitmq_server-4.3.6/sbin/rabbitmqctl.bat").toString(),
                "DRILL_RABBIT_ACTION", action));
        Process process = builder.directory(ROOT.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(RUNTIME.resolve("rabbitmq-control.log").toFile())).start();
        if (!process.waitFor(35, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            fail("RabbitMQ控制超时，恢复路径仍将尝试启动Broker应用");
        }
        assertEquals(0, process.exitValue(), "RabbitMQ控制失败，请检查tools/trade-drill/rabbitmq-control.log");
    }

    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    final class Session {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(3)).build();
        boolean loggedIn;

        JsonNode csrf(String base) throws Exception {
            var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            return json.readTree(response.body());
        }

        void login(String username) throws Exception {
            JsonNode token = csrf("http://127.0.0.1:8083");
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8084/api/auth/login"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/x-www-form-urlencoded")
                    .header(token.path("headerName").asText(), token.path("token").asText())
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + encode(username) + "&password=" + encode(password))).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(204, response.statusCode());
            loggedIn = true;
        }

        HttpResponse<String> post(String base, String path, String body, int expected) throws Exception {
            JsonNode token = csrf(base);
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8))
                    .header(token.path("headerName").asText(), token.path("token").asText());
            if (body != null) request.header("Content-Type", "application/json");
            var response = client.send(request.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(expected, response.statusCode(), "HTTP操作失败：" + path);
            return response;
        }

        void logout() throws Exception {
            if (loggedIn) {
                post("http://127.0.0.1:8083", "/api/auth/logout", null, 204);
                loggedIn = false;
            }
        }
    }

    @AfterAll void cleanup() throws Exception {
        assertAll("只清理本轮资源，任何步骤失败仍执行后续步骤并报告", () -> {
                    if (brokerStopped) { controlBroker("start_app"); brokerStopped = false; }
                }, () -> { if (nginxOwned) runScript("stop-nginx.ps1", "-Mode", "trade-drill"); }, () -> {
                    assertAll("逐个尝试停止全部本轮JVM", nodes.stream().map(process -> (Executable) () -> {
                        process.destroy();
                        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "本轮JVM未停止，PID=" + process.pid());
                    }));
                }, () -> {
                    if (topologyOwned) {
                        assertTrue(nodes.stream().noneMatch(Process::isAlive), "仍有本轮JVM运行，拒绝删除MQ拓扑");
                        connection.resetConnection();
                        for (String suffix : List.of("wait", "ready", "failed")) rabbitAdmin.deleteQueue(PREFIX + ".order-timeout." + suffix);
                        for (String suffix : List.of("exchange", "dlx", "failed.exchange")) rabbitAdmin.deleteExchange(PREFIX + ".order-timeout." + suffix);
                    }
                }, () -> {
                    if (databaseOwned) {
                        assertTrue(nodes.stream().noneMatch(Process::isAlive), "仍有本轮JVM运行，拒绝删除数据库夹具");
                        if (buyerId != 0) {
                            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT id FROM trade_order WHERE user_id=?)", buyerId);
                            jdbc.update("DELETE FROM trade_order WHERE user_id=?", buyerId);
                            jdbc.update("DELETE FROM user_address WHERE user_id=?", buyerId);
                        }
                        if (productId != 0) jdbc.update("DELETE FROM product WHERE id=?", productId);
                        jdbc.update("DELETE FROM app_user WHERE username IN (?,?)", buyerName, adminName);
                    }
                }, () -> {
                    assertTrue(nodes.stream().noneMatch(Process::isAlive), "仍有本轮JVM运行，拒绝删除Redis键");
                    try (var keys = redis.scan(ScanOptions.scanOptions().match(REDIS + ":*").count(100).build())) {
                        var owned = new ArrayList<String>();
                        keys.forEachRemaining(owned::add);
                        if (!owned.isEmpty()) redis.delete(owned);
                    }
                }, () -> {
                    if (databaseOwned) for (var entry : before.entrySet()) {
                        assertEquals(entry.getValue(), jdbc.queryForObject("SELECT COUNT(*) FROM `" + entry.getKey() + "`", Long.class), "测试夹具未清理：" + entry.getKey());
                    }
                });
    }
}
