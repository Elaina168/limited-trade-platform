package com.elaina.limitedtrade.product;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ProductCache {

    private static final Logger log = LoggerFactory.getLogger(ProductCache.class);
    private static final long REFRESH_AT_MS = 10_000;
    private static final long WAIT_MS = 2_000;
    private static final long MAX_LOAD_NS = TimeUnit.SECONDS.toNanos(5);

    private final ProductCacheStore store;
    private final boolean enabled;
    private final boolean bloomEnabled;
    private final Cache<String, LocalList> local;
    private final Object gate = new Object();
    private final Semaphore limitedDb = new Semaphore(1);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("product-cache-refresh").factory());

    private volatile long revision;
    private volatile boolean dirty;
    private long retryAfterNanos;
    private long invalidationRetryNanos;
    private long nextLimitedCall;
    private CompletableFuture<List<Product>> inFlight;

    public ProductCache(ProductCacheStore store,
                        @Value("${app.product-cache.enabled}") boolean enabled,
                        @Value("${app.product-cache.local-enabled:true}") boolean localEnabled,
                        @Value("${app.product-cache.bloom-enabled:false}") boolean bloomEnabled) {
        this.store = store;
        this.enabled = enabled;
        this.bloomEnabled = bloomEnabled;
        this.local = Caffeine.newBuilder().maximumSize(1)
                .expireAfterWrite(localEnabled ? Duration.ofSeconds(1) : Duration.ZERO).build();
    }

    public List<Product> getOrLoad(Supplier<List<Product>> load) {
        if (!enabled) return load.get();
        LocalList hit = local.getIfPresent("catalog");
        if (hit != null) return hit.products();
        long readRevision = revision;
        var shared = readShared(store::readList);
        if (fresh(shared)) {
            List<Product> products = store.decode(shared);
            synchronized (gate) {
                if (revision == readRevision && !dirty) remember(products);
            }
            return products;
        }

        CompletableFuture<List<Product>> pending;
        synchronized (gate) {
            // 冷读与软过期共用一个任务，避免在并发窗口重复回源。
            if (shared != null) shared = readShared(store::readList);
            if (fresh(shared)) {
                List<Product> products = store.decode(shared);
                remember(products);
                return products;
            }
            boolean stale = shared != null && shared.remainingMs() > 0;
            if (inFlight == null && (retryAfterNanos == 0 || System.nanoTime() - retryAfterNanos >= 0)) {
                var result = new CompletableFuture<List<Product>>();
                inFlight = result;
                long loadRevision = revision;
                String version = shared == null ? null : shared.version();
                String trigger = version == null ? "degraded" : (stale ? "refresh" : "cold");
                worker.execute(() -> loadList(load, result, loadRevision, version, trigger));
            }
            if (stale) return store.decode(shared);
            pending = inFlight;
        }
        if (pending == null) throw unavailable("商品列表正在等待重试");
        try {
            return pending.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "等待商品列表已中断", error);
        } catch (TimeoutException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "等待商品列表超时", error);
        } catch (ExecutionException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "商品列表回源失败", error.getCause());
        }
    }

    private boolean fresh(ProductCacheStore.Shared shared) {
        return shared != null && shared.remainingMs() > REFRESH_AT_MS;
    }

    private ProductCacheStore.Shared readShared(Supplier<ProductCacheStore.Shared> read) {
        if (dirty) return null;
        try {
            return read.get();
        } catch (RedisConnectionFailureException | QueryTimeoutException error) {
            log.atDebug().setCause(error).log("商品 Redis 读取不可用，进入限额回源");
            return null;
        }
    }

    private void loadList(Supplier<List<Product>> load, CompletableFuture<List<Product>> result,
                          long loadRevision, String version, String trigger) {
        String outcome;
        try {
            long started = System.nanoTime();
            List<Product> products = version == null ? loadLimited(load) : load.get();
            synchronized (gate) {
                boolean current = revision == loadRevision && System.nanoTime() - started < MAX_LOAD_NS;
                if (current && (version == null || store.writeList(version, products,
                        ThreadLocalRandom.current().nextLong(25_000, 30_001)))) {
                    remember(products);
                    outcome = "stored";
                    result.complete(products);
                } else {
                    outcome = "invalidated";
                    result.completeExceptionally(unavailable("回源已超期或商品列表已失效"));
                }
                inFlight = null;
            }
        } catch (RuntimeException error) {
            synchronized (gate) {
                if (revision == loadRevision) retryAfterNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                result.completeExceptionally(error);
                inFlight = null;
            }
            log.atWarn().addKeyValue("event", "product_cache_load").addKeyValue("trigger", trigger)
                    .addKeyValue("outcome", "failed").setCause(error).log("商品列表回源失败");
            return;
        }
        log.atInfo().addKeyValue("event", "product_cache_load").addKeyValue("trigger", trigger)
                .addKeyValue("outcome", outcome).log("商品列表回源完成");
    }

    private void remember(List<Product> products) {
        ProductIdBloom bloom = bloomEnabled && products.size() <= 4096 ? new ProductIdBloom(products) : null;
        local.put("catalog", new LocalList(products, bloom));
    }

    public Product findProduct(long id, Supplier<Product> load) {
        if (!enabled) return load.get();
        LocalList hit = local.getIfPresent("catalog");
        if (hit != null && hit.bloom() != null && hit.bloom().mightContain(id) && reserveDb()) {
            // 布隆命中只是跳过 Redis 的提示，最终结果始终来自数据库。
            try { return load.get(); }
            finally { limitedDb.release(); }
        }
        var shared = readShared(() -> store.readMissing(id));
        if (shared == null) return loadLimited(load);
        if (shared.remainingMs() > 0 && shared.value().equals(shared.version() + ":" + id)) return null;
        long started = System.nanoTime();
        Product product = load.get();
        if (product == null && System.nanoTime() - started < MAX_LOAD_NS) {
            try {
                store.writeMissing(shared.version(), id);
            } catch (RedisConnectionFailureException | QueryTimeoutException error) {
                log.atDebug().setCause(error).log("商品缺失标记写入失败");
            }
        }
        return product;
    }

    private <T> T loadLimited(Supplier<T> load) {
        if (!reserveDb()) throw unavailable("商品降级回源额度已满");
        try { return load.get(); }
        finally { limitedDb.release(); }
    }

    private boolean reserveDb() {
        if (!limitedDb.tryAcquire()) return false;
        long now = System.nanoTime();
        if (nextLimitedCall != 0 && now - nextLimitedCall < 0) {
            limitedDb.release();
            return false;
        }
        nextLimitedCall = now + TimeUnit.MILLISECONDS.toNanos(250);
        return true;
    }

    private ResponseStatusException unavailable(String reason) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason);
    }

    public void evictAfterCommit() { evictAfterCommit(null); }

    public void evictAfterCommit(Long publishedId) {
        if (enabled) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    synchronized (gate) {
                        invalidateLocal();
                        invalidateShared(publishedId);
                    }
                }
            });
        }
    }

    private void invalidateLocal() {
        revision++;
        retryAfterNanos = 0;
        local.invalidateAll();
    }

    private void invalidateShared(Long publishedId) {
        try {
            store.invalidate(publishedId);
            dirty = false;
        } catch (RedisConnectionFailureException | QueryTimeoutException error) {
            dirty = true;
            invalidationRetryNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            log.atWarn().addKeyValue("event", "product_cache_invalidation_failed").setCause(error)
                    .log("商品缓存提交后失效失败，将重试");
        }
    }

    @Scheduled(fixedDelay = 1000)
    void retryInvalidation() {
        if (!dirty) return;
        synchronized (gate) {
            if (dirty && System.nanoTime() - invalidationRetryNanos >= 0) {
                invalidateLocal();
                // 版本变化同时作废所有未清理的缺失槽。
                invalidateShared(null);
            }
        }
    }

    @PreDestroy
    void stopWorker() { worker.shutdownNow(); }

    private record LocalList(List<Product> products, ProductIdBloom bloom) {}
}
