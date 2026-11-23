package com.elaina.limitedtrade.product;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductCachePolicyTest {

    @Test
    void unavailableRedisBoundsConcurrentAndImmediateFallbackButCanRecover() throws Exception {
        var store = mock(ProductCacheStore.class);
        var error = new RedisConnectionFailureException("模拟连接失败");
        when(store.readMissing(1)).thenThrow(error);
        when(store.readList()).thenThrow(error);
        var cache = new ProductCache(store, true, true, false);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var product = new Product(1L, "实际商品", BigDecimal.TEN, 3, "ON_SALE");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var fallback = pool.submit(() -> cache.findProduct(1, () -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return product;
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(503, assertThrows(ResponseStatusException.class,
                        () -> cache.getOrLoad(() -> fail("列表不应越过共享降级额度"))).getStatusCode().value());
            } finally {
                release.countDown();
            }
            assertEquals(product, fallback.get(5, TimeUnit.SECONDS));
            // 连接恢复后走正常 Redis 路径，降级的时间额度不限制正常查询。
            doReturn(new ProductCacheStore.Shared("current", "", -2)).when(store).readMissing(1);
            assertEquals(product, cache.findProduct(1, () -> product));
        } finally {
            cache.stopWorker();
        }
    }

    @Test
    void bloomChecksTheDatabaseForFalsePositivesAndDoesNotRejectNewIds() {
        var store = mock(ProductCacheStore.class);
        var cache = new ProductCache(store, true, true, true);
        var products = LongStream.rangeClosed(1, 4096).mapToObj(id ->
                new Product(id, "商品", BigDecimal.TEN, 3, "ON_SALE")).toList();
        var bloom = new ProductIdBloom(products);
        long falsePositive = LongStream.range(4097, 100_000).filter(bloom::mightContain).findFirst().orElseThrow();
        long newId = LongStream.range(4097, 100_000).filter(id -> !bloom.mightContain(id)).findFirst().orElseThrow();
        var newProduct = new Product(newId, "新上架", BigDecimal.TEN, 1, "ON_SALE");
        var miss = new ProductCacheStore.Shared("current", "", -2);
        var data = new ProductCacheStore.Shared("current", "列表", 20_000);
        when(store.readMissing(newId)).thenReturn(miss);
        when(store.readList()).thenReturn(data);
        when(store.decode(data)).thenReturn(products);
        try {
            assertEquals(newProduct, cache.findProduct(newId, () -> newProduct));
            assertEquals(products, cache.getOrLoad(() -> fail("已有列表")));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            assertNull(cache.findProduct(falsePositive, () -> { calls.incrementAndGet(); return null; }));
            assertEquals(1, calls.get());
            verify(store, never()).readMissing(falsePositive);
            assertEquals(newProduct, cache.findProduct(newId, () -> newProduct));
            verify(store, times(2)).readMissing(newId);
        } finally {
            cache.stopWorker();
        }
    }

    @Test
    void hotLocalHitsReplaceRepeatedRedisReads() {
        var store = mock(ProductCacheStore.class);
        var data = new ProductCacheStore.Shared("current", "列表", 25_000);
        var products = List.of(new Product(1L, "热商品", BigDecimal.TEN, 3, "ON_SALE"));
        when(store.readList()).thenReturn(data);
        when(store.decode(data)).thenReturn(products);
        var redisOnly = new ProductCache(store, true, false, false);
        var near = new ProductCache(store, true, true, false);
        try {
            for (int n = 0; n < 12; n++) assertSame(products, redisOnly.getOrLoad(() -> fail("已有 Redis 列表")));
            verify(store, times(12)).readList();
            clearInvocations(store);
            for (int n = 0; n < 12; n++) assertSame(products, near.getOrLoad(() -> fail("已有 Redis 列表")));
            verify(store, times(1)).readList();
        } finally {
            redisOnly.stopWorker();
            near.stopWorker();
        }
    }

}
