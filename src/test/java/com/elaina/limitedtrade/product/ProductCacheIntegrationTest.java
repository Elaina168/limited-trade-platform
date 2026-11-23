package com.elaina.limitedtrade.product;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import com.elaina.limitedtrade.order.CreateOrderRequest;
import com.elaina.limitedtrade.order.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.product-cache.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductCacheIntegrationTest {

    // 每次运行只操作自己的键，不使用 FLUSHDB，也不触碰开发缓存。
    private static final String CACHE_KEY = "limited-trade:test:product-cache:" + UUID.randomUUID();

    @DynamicPropertySource
    static void cacheProperties(DynamicPropertyRegistry registry) {
        registry.add("app.product-cache.key", () -> CACHE_KEY);
        registry.add("app.product-cache.local-enabled", () -> false);
    }

    @Autowired private ProductService productService;
    @Autowired private ProductCacheStore store;
    @MockitoSpyBean private ProductMapper productMapper;
    @Autowired private OrderService orderService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformTransactionManager transactionManager;

    private boolean fixtureOwned;
    private boolean categoryOwned;

    @BeforeEach
    void createCommittedFixtures() {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id IN (9701,9702,9703)", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id=7701", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM activity WHERE id=9701", Integer.class));
        fixtureOwned = true;
        redis.delete(CACHE_KEY);
        jdbc.update("INSERT INTO app_user (id,username,password_hash,role) VALUES (7701,'cache_buyer','unused-test-hash','USER')");
        jdbc.update("INSERT INTO product (id,name,price,stock,status) VALUES (9701,'缓存测试键盘',20.00,10,'ON_SALE'),(9702,'下架商品',30.00,5,'OFF_SALE')");
        jdbc.update("""
                INSERT INTO activity (id,product_id,name,total_quantity,remaining_quantity,starts_at,ends_at)
                VALUES (9701,9701,'缓存测试活动',5,5,DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 HOUR),
                        DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 HOUR))
                """);
    }

    @AfterEach
    void removeOwnedFixtures() {
        redis.delete(List.of(CACHE_KEY, CACHE_KEY + ":version", CACHE_KEY + ":missing:" + (9703 % 256)));
        if (fixtureOwned) {
            jdbc.update("DELETE FROM order_timeout_outbox WHERE order_id IN (SELECT order_id FROM order_item WHERE product_id IN (9701,9702))");
            jdbc.update("DELETE FROM trade_order WHERE id IN (SELECT order_id FROM order_item WHERE product_id IN (9701,9702))");
            jdbc.update("DELETE FROM activity WHERE id=9701");
            jdbc.update("DELETE FROM product WHERE id IN (9701,9702,9703)");
            jdbc.update("DELETE FROM app_user WHERE id=7701");
            if (categoryOwned) {
                jdbc.update("DELETE FROM product_category WHERE id=12003");
            }
        }
    }

    @Test
    void oldFiveFieldCacheStillDeserializesAndPreservesPublicResponse() throws Exception {
        redis.opsForValue().set(CACHE_KEY,
                "[{\"id\":9701,\"name\":\"旧缓存商品\",\"price\":20.00,\"stock\":4,\"status\":\"ON_SALE\"}]",
                Duration.ofSeconds(30));
        var result = mockMvc.perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("旧缓存商品"))
                .andExpect(jsonPath("$[0].stock").value(4))
                .andReturn().getResponse().getContentAsString();
        assertEquals(5, objectMapper.readTree(result).get(0).size());
    }

    @Test
    void searchReadsDatabaseEvenWhenOriginalListCacheContainsOlderValues() throws Exception {
        productService.listOnSaleProducts();
        jdbc.update("UPDATE product SET name='缓存后新名称',stock=7 WHERE id=9701");
        mockMvc.perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("缓存测试键盘"))
                .andExpect(jsonPath("$[0].stock").value(10));
        mockMvc.perform(get("/api/products/search").param("keyword", "缓存后新名称"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(9701))
                .andExpect(jsonPath("$.items[0].stock").value(7));
    }

    @Test
    void categoryMetadataInCacheDoesNotLeakIntoOriginalPublicList() throws Exception {
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product_category WHERE id=12003", Integer.class));
        jdbc.update("INSERT INTO product_category(id,name) VALUES (12003,'缓存集成分类')");
        categoryOwned = true;
        jdbc.update("UPDATE product SET category_id=12003 WHERE id=9701");
        var result = mockMvc.perform(get("/api/products")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(5, objectMapper.readTree(result).get(0).size());
        assertEquals(12003L, objectMapper.readTree(redis.opsForValue().get(CACHE_KEY)).get(0).get("categoryId").asLong());
        mockMvc.perform(get("/api/products/search").param("categoryId", "12003"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].categoryId").value(12003))
                .andExpect(jsonPath("$.items[0].categoryName").value("缓存集成分类"));
    }

    @Test
    void firstHttpQueryCachesJsonWithExpirationAndSecondQueryUsesIt() throws Exception {
        mockMvc.perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(9701))
                .andExpect(jsonPath("$[0].stock").value(10));
        String cached = redis.opsForValue().get(CACHE_KEY);
        assertNotNull(cached);
        assertTrue(objectMapper.readTree(cached).isArray());
        assertEquals("缓存测试键盘", objectMapper.readTree(cached).get(0).get("name").asText());
        assertTrue(redis.getExpire(CACHE_KEY, TimeUnit.MILLISECONDS) > 0);
        assertTrue(redis.getExpire(CACHE_KEY, TimeUnit.MILLISECONDS) <= 30_000);

        // 绕过业务直接改隔离夹具，用返回值证明第二次读取没有回源。
        jdbc.update("UPDATE product SET stock=7 WHERE id=9701");
        mockMvc.perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stock").value(10))
                .andExpect(jsonPath("$[0].price").value(20.00));
    }

    @Test
    void expiredCacheReloadsCurrentDatabaseValue() {
        productService.listOnSaleProducts();
        assertTrue(redis.hasKey(CACHE_KEY));
        jdbc.update("UPDATE product SET stock=7 WHERE id=9701");
        // 缩短本测试键的剩余时间，实际等待 Redis 过期，避免每次测试等30秒。
        assertTrue(redis.expire(CACHE_KEY, Duration.ofMillis(100)));
        await().atMost(Duration.ofSeconds(3)).until(() -> !redis.hasKey(CACHE_KEY));
        assertEquals(7, productService.listOnSaleProducts().get(0).getStock());
        assertTrue(redis.hasKey(CACHE_KEY));
    }

    @Test
    void emptyOnSaleListIsCachedToo() throws Exception {
        jdbc.update("UPDATE product SET status='OFF_SALE' WHERE id=9701");
        assertTrue(productService.listOnSaleProducts().isEmpty());
        assertEquals("[]", redis.opsForValue().get(CACHE_KEY));
        jdbc.update("UPDATE product SET status='ON_SALE' WHERE id=9701");
        mockMvc.perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void nameAndPriceUpdateEvictsOnlyAfterCommit() {
        productService.listOnSaleProducts();
        String cached = redis.opsForValue().get(CACHE_KEY);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            productService.updateProduct(9701, new UpdateProductRequest("新名称", new BigDecimal("25.50"), null));
            assertEquals(cached, redis.opsForValue().get(CACHE_KEY));
        });
        assertFalse(redis.hasKey(CACHE_KEY));
        Product result = productService.listOnSaleProducts().get(0);
        assertEquals("新名称", result.getName());
        assertEquals(0, new BigDecimal("25.50").compareTo(result.getPrice()));
    }

    @Test
    void rolledBackProductUpdateKeepsOriginalCacheAndDatabase() {
        productService.listOnSaleProducts();
        String cached = redis.opsForValue().get(CACHE_KEY);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            productService.updateProduct(9701, new UpdateProductRequest("应回滚", new BigDecimal("99.00"), null));
            tx.setRollbackOnly();
        });
        assertEquals(cached, redis.opsForValue().get(CACHE_KEY));
        assertEquals("缓存测试键盘", jdbc.queryForObject("SELECT name FROM product WHERE id=9701", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ON_SALE", "OFF_SALE"})
    void statusChangeEvictsListIncludingNewlyVisibleProducts(String status) {
        productService.listOnSaleProducts();
        long id = "ON_SALE".equals(status) ? 9702 : 9701;
        productService.updateProductStatus(id, new UpdateProductStatusRequest(status));
        assertFalse(redis.hasKey(CACHE_KEY));
        assertEquals("ON_SALE".equals(status) ? 2 : 0, productService.listOnSaleProducts().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void committedOrderEvictsStockAndIdempotentRetryKeepsCache(boolean activity) {
        productService.listOnSaleProducts();
        var request = new CreateOrderRequest(9701L, 2, "cache-create", activity ? 9701L : null);
        var created = orderService.createOrder(7701, request);
        assertTrue(created.created());
        assertFalse(redis.hasKey(CACHE_KEY));
        assertEquals(8, productService.listOnSaleProducts().get(0).getStock());
        String cached = redis.opsForValue().get(CACHE_KEY);
        var retry = orderService.createOrder(7701, request);
        assertFalse(retry.created());
        assertEquals(created.order().getId(), retry.order().getId());
        assertEquals(cached, redis.opsForValue().get(CACHE_KEY));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationEvictsRestoredStockAndRepeatedCancelKeepsCache(boolean activity) {
        long id = orderService.createOrder(7701,
                new CreateOrderRequest(9701L, 2, "cache-cancel", activity ? 9701L : null)).order().getId();
        productService.listOnSaleProducts();
        orderService.cancelOrder(7701, id);
        assertFalse(redis.hasKey(CACHE_KEY));
        assertEquals(10, productService.listOnSaleProducts().get(0).getStock());
        String cached = redis.opsForValue().get(CACHE_KEY);
        orderService.cancelOrder(7701, id);
        assertEquals(cached, redis.opsForValue().get(CACHE_KEY));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeoutCloseEvictsRestoredStockAndRepeatedCloseKeepsCache(boolean activity) {
        long id = orderService.createOrder(7701,
                new CreateOrderRequest(9701L, 2, "cache-close", activity ? 9701L : null)).order().getId();
        jdbc.update("UPDATE trade_order SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 MINUTE) WHERE id=?", id);
        productService.listOnSaleProducts();
        assertTrue(orderService.closeExpiredOrder(id, LocalDateTime.now().withNano(0)));
        assertFalse(redis.hasKey(CACHE_KEY));
        assertEquals(10, productService.listOnSaleProducts().get(0).getStock());
        String cached = redis.opsForValue().get(CACHE_KEY);
        assertFalse(orderService.closeExpiredOrder(id, LocalDateTime.now().withNano(0)));
        assertEquals(cached, redis.opsForValue().get(CACHE_KEY));
    }

    @Test
    void paymentDoesNotEvictBecauseItDoesNotChangeStock() {
        long id = orderService.createOrder(7701,
                new CreateOrderRequest(9701L, 2, "cache-pay", 9701L)).order().getId();
        productService.listOnSaleProducts();
        String cached = redis.opsForValue().get(CACHE_KEY);
        assertEquals("PAID", orderService.payOrder(7701, id).getStatus());
        assertEquals(cached, redis.opsForValue().get(CACHE_KEY));
    }

    @Test
    void orderingUsesDatabasePriceAndStockEvenWhenCacheIsStale() {
        productService.listOnSaleProducts();
        jdbc.update("UPDATE product SET price=25.50,stock=2 WHERE id=9701");
        var order = orderService.createOrder(7701, new CreateOrderRequest(9701L, 2, "real-price", null)).order();
        assertEquals(0, new BigDecimal("51.00").compareTo(order.getTotalAmount()));
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM product WHERE id=9701", Integer.class));
        assertFalse(redis.hasKey(CACHE_KEY));
    }

    @Test
    void insufficientDatabaseStockRejectsOrderDespiteCachedStock() {
        productService.listOnSaleProducts();
        String cached = redis.opsForValue().get(CACHE_KEY);
        jdbc.update("UPDATE product SET stock=0 WHERE id=9701");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> orderService.createOrder(
                7701, new CreateOrderRequest(9701L, 1, "no-stock", null))).getStatusCode().value());
        assertEquals(cached, redis.opsForValue().get(CACHE_KEY));
    }

    @Test
    void failedActivityOrderRollsBackStockAndPreservesCache() {
        productService.listOnSaleProducts();
        String cached = redis.opsForValue().get(CACHE_KEY);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> orderService.createOrder(
                7701, new CreateOrderRequest(9701L, 6, "no-quota", 9701L))).getStatusCode().value());
        assertEquals(10, jdbc.queryForObject("SELECT stock FROM product WHERE id=9701", Integer.class));
        assertEquals(cached, redis.opsForValue().get(CACHE_KEY));
    }

    @Test
    void realStockOverflowRollsBackCancellationWithoutEvictingCache() {
        long id = orderService.createOrder(7701,
                new CreateOrderRequest(9701L, 2, "rollback-cancel", 9701L)).order().getId();
        jdbc.update("UPDATE product SET stock=2147483647 WHERE id=9701");
        productService.listOnSaleProducts();
        String cached = redis.opsForValue().get(CACHE_KEY);
        assertThrows(DataAccessException.class, () -> orderService.cancelOrder(7701, id));
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM trade_order WHERE id=?", String.class, id));
        assertEquals(3, jdbc.queryForObject("SELECT remaining_quantity FROM activity WHERE id=9701", Integer.class));
        assertEquals(cached, redis.opsForValue().get(CACHE_KEY));
    }

    @Test
    void coldBurstSharesOneDatabaseLoad() throws Exception {
        var starts = new CountDownLatch(12);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var fixture = new Product(9701L, "冷启动商品", BigDecimal.TEN, 10, "ON_SALE");
        doAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(fixture);
        }).when(productMapper).findOnSaleProducts();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<List<Product>>>();
            try {
                for (int index = 0; index < 12; index++) {
                    results.add(pool.submit(() -> {
                        starts.countDown();
                        assertTrue(starts.await(5, TimeUnit.SECONDS));
                        return productService.listOnSaleProducts();
                    }));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                await().pollInterval(Duration.ofMillis(20)).during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                        .untilAsserted(() -> assertEquals(1, calls.get()));
            } finally {
                release.countDown();
            }
            for (var result : results) assertEquals(9701L, result.get(5, TimeUnit.SECONDS).getFirst().getId());
        }
        assertEquals(1, calls.get());
    }

    @Test
    void softExpiryReturnsOldValueAndRefreshesInBackground() throws Exception {
        productService.listOnSaleProducts();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(new Product(9701L, "刷新成功", BigDecimal.TEN, 7, "ON_SALE"));
        }).when(productMapper).findOnSaleProducts();
        try {
            assertTrue(redis.expire(CACHE_KEY, Duration.ofSeconds(8)));
            assertEquals("缓存测试键盘", productService.listOnSaleProducts().getFirst().getName());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals("缓存测试键盘", productService.listOnSaleProducts().getFirst().getName());
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertEquals("刷新成功", productService.listOnSaleProducts().getFirst().getName()));
    }

    @Test
    void hardInvalidationRejectsAnOlderRefresh() throws Exception {
        productService.listOnSaleProducts();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(new Product(9701L, "已下架的旧值", BigDecimal.TEN, 7, "ON_SALE"));
        }).doReturn(List.of()).when(productMapper).findOnSaleProducts();
        try {
            assertTrue(redis.expire(CACHE_KEY, Duration.ofSeconds(8)));
            assertEquals("缓存测试键盘", productService.listOnSaleProducts().getFirst().getName());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            productService.updateProductStatus(9701, new UpdateProductStatusRequest("OFF_SALE"));
            assertFalse(redis.hasKey(CACHE_KEY));
        } finally {
            release.countDown();
        }
        await().atMost(Duration.ofSeconds(4)).ignoreException(ResponseStatusException.class).untilAsserted(() ->
                assertTrue(productService.listOnSaleProducts().isEmpty()));
        assertEquals("[]", redis.opsForValue().get(CACHE_KEY));
    }

    @Test
    void refreshFailureKeepsOriginalHardExpiryAndColdFailureReturnsUnavailable() {
        productService.listOnSaleProducts();
        doThrow(new DataAccessResourceFailureException("测试回源失败")).when(productMapper).findOnSaleProducts();
        assertTrue(redis.expire(CACHE_KEY, Duration.ofSeconds(7)));
        assertEquals("缓存测试键盘", productService.listOnSaleProducts().getFirst().getName());
        verify(productMapper, timeout(2000).times(2)).findOnSaleProducts();
        long remaining = redis.getExpire(CACHE_KEY, TimeUnit.MILLISECONDS);
        assertTrue(remaining > 0 && remaining <= 7000);
        redis.delete(CACHE_KEY);
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> productService.listOnSaleProducts()).getStatusCode().value());
        assertFalse(redis.hasKey(CACHE_KEY));

        doReturn(List.of(new Product(9701L, "恢复商品", BigDecimal.TEN, 7, "ON_SALE")))
                .when(productMapper).findOnSaleProducts();
        await().atMost(Duration.ofSeconds(4)).ignoreException(ResponseStatusException.class).untilAsserted(() ->
                assertEquals("恢复商品", productService.listOnSaleProducts().getFirst().getName()));
    }

    @Test
    void coldWaitTimesOutWithoutStartingASecondLoad() {
        var release = new CountDownLatch(1);
        var fixture = new Product(9701L, "慢加载商品", BigDecimal.TEN, 10, "ON_SALE");
        doAnswer(invocation -> {
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(fixture);
        }).when(productMapper).findOnSaleProducts();
        try {
            var timedOut = assertThrows(ResponseStatusException.class, () -> productService.listOnSaleProducts());
            assertEquals(503, timedOut.getStatusCode().value());
            assertEquals("等待商品列表超时", timedOut.getReason());
        } finally {
            release.countDown();
        }
        assertEquals("慢加载商品", productService.listOnSaleProducts().getFirst().getName());
        verify(productMapper, times(1)).findOnSaleProducts();
    }

    @Test
    void wholeListTtlStaysWithinJitterWindow() {
        var observed = new java.util.HashSet<Long>();
        for (int run = 0; run < 8; run++) {
            redis.delete(CACHE_KEY);
            productService.listOnSaleProducts();
            long remaining = redis.getExpire(CACHE_KEY, TimeUnit.MILLISECONDS);
            assertTrue(remaining >= 24_000 && remaining <= 30_000);
            observed.add(remaining / 250);
        }
        assertTrue(observed.size() > 1);
    }

    @Test
    void missingProductUsesNegativeSlotUntilPublication() throws Exception {
        mockMvc.perform(get("/api/products/9703")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/products/9703")).andExpect(status().isNotFound());
        verify(productMapper, times(1)).findById(9703);
        String slot = CACHE_KEY + ":missing:" + (9703 % 256);
        assertEquals(redis.opsForValue().get(CACHE_KEY + ":version") + ":9703", redis.opsForValue().get(slot));
        assertTrue(redis.getExpire(slot, TimeUnit.MILLISECONDS) > 0);
        mockMvc.perform(get("/api/products/9959")).andExpect(status().isNotFound());
        verify(productMapper).findById(9959);
        // 同一个槽替换后，确切 ID 重新查询并覆盖标记。
        mockMvc.perform(get("/api/products/9703")).andExpect(status().isNotFound());
        assertEquals(redis.opsForValue().get(CACHE_KEY + ":version") + ":9703", redis.opsForValue().get(slot));
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (9703,'待上架商品',12.00,7,'OFF_SALE')");
        mockMvc.perform(get("/api/products/9703")).andExpect(status().isNotFound());
        productService.updateProductStatus(9703, new UpdateProductStatusRequest("ON_SALE"));
        assertFalse(redis.hasKey(slot));
        mockMvc.perform(get("/api/products/9703")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("待上架商品")).andExpect(jsonPath("$.stock").value(7));
        mockMvc.perform(get("/api/products/9702")).andExpect(status().isNotFound());
        assertFalse(redis.hasKey(CACHE_KEY + ":missing:" + (9702 % 256)));
    }


    @Test
    void remoteInvalidationRejectsInFlightList() throws Exception {
        var reader = new ProductCache(store, true, false, false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> reader.getOrLoad(() -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                return List.of(new Product(9701L, "跨实例旧值", BigDecimal.TEN, 10, "ON_SALE"));
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                productService.updateProductStatus(9701, new UpdateProductStatusRequest("OFF_SALE"));
            } finally {
                release.countDown();
            }
            var rejected = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> pending.get(5, TimeUnit.SECONDS));
            assertEquals(503, ((ResponseStatusException) rejected.getCause()).getStatusCode().value());
            assertFalse(redis.hasKey(CACHE_KEY));
        } finally {
            reader.stopWorker();
        }
    }

    @Test
    void anotherInstancesLocalSnapshotExpiresWithoutNotification() {
        var reader = new ProductCache(store, true, true, false);
        try {
            var fixture = new Product(9701L, "旧本地快照", BigDecimal.TEN, 10, "ON_SALE");
            assertEquals("旧本地快照", reader.getOrLoad(() -> List.of(fixture)).getFirst().getName());
            productService.updateProductStatus(9701, new UpdateProductStatusRequest("OFF_SALE"));
            assertFalse(redis.hasKey(CACHE_KEY));
            await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                    assertTrue(reader.getOrLoad(List::of).isEmpty()));
        } finally {
            reader.stopWorker();
        }
    }

    @Test
    void failedSharedInvalidationKeepsLocalReadsSafeAndRetriesAfterRecovery() {
        var writerStore = spy(store);
        var writer = new ProductCache(writerStore, true, true, false);
        try {
            var fixture = new Product(9701L, "失效前快照", BigDecimal.TEN, 10, "ON_SALE");
            writer.getOrLoad(() -> List.of(fixture));
            doThrow(new org.springframework.data.redis.RedisConnectionFailureException("模拟失效失败"))
                    .doCallRealMethod().when(writerStore).invalidate(null);
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> writer.evictAfterCommit());

            assertTrue(redis.hasKey(CACHE_KEY));
            assertTrue(writer.getOrLoad(List::of).isEmpty());
            assertTrue(redis.hasKey(CACHE_KEY), "降级回源不应回填 Redis");
            await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
                writer.retryInvalidation();
                assertFalse(redis.hasKey(CACHE_KEY));
            });
            assertTrue(productService.listOnSaleProducts().stream().anyMatch(item -> item.getId() == 9701L));
        } finally {
            writer.stopWorker();
        }
    }

    @Test
    void remotePublicationRejectsInFlightNegativeMarker() throws Exception {
        var reader = new ProductCache(store, true, false, false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = pool.submit(() -> reader.findProduct(9703, () -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                return null;
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES (9703,'跨实例上架',12,7,'OFF_SALE')");
                productService.updateProductStatus(9703, new UpdateProductStatusRequest("ON_SALE"));
            } finally {
                release.countDown();
            }
            assertNull(pending.get(5, TimeUnit.SECONDS));
            assertFalse(redis.hasKey(CACHE_KEY + ":missing:" + (9703 % 256)));
            assertEquals("跨实例上架", productService.getOnSaleProduct(9703).getName());
        } finally {
            reader.stopWorker();
        }
    }

}
