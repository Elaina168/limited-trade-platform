package com.elaina.limitedtrade.product;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ProductCache {

    private final RedisTemplate<String, List<Product>> redis;
    private final boolean enabled;
    private final String key;

    public ProductCache(RedisTemplate<String, List<Product>> redis,
                        @Value("${app.product-cache.enabled}") boolean enabled,
                        @Value("${app.product-cache.key}") String key) {
        this.redis = redis;
        this.enabled = enabled;
        this.key = key;
    }

    public List<Product> get() {
        return enabled ? redis.opsForValue().get(key) : null;
    }

    public void put(List<Product> products) {
        if (enabled) {
            // 值和有效期通过同一条 SET 命令写入，空列表也缓存。
            redis.opsForValue().set(key, products, Duration.ofSeconds(30));
        }
    }

    public void evictAfterCommit() {
        if (enabled) {
            // 调用方均处于数据库事务中；回滚不删缓存，提交后才执行删除。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redis.delete(key);
                }
            });
        }
    }
}
