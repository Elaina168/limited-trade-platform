package com.elaina.limitedtrade.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.stereotype.Component;

@Component
class ProductCacheStore {

    private static final DefaultRedisScript<List> READ = new DefaultRedisScript<>("""
            local version = redis.call('GET', KEYS[1])
            if not version then
                version = ARGV[1]
                redis.call('SET', KEYS[1], version)
            end
            return {version, redis.call('GET', KEYS[2]) or '', redis.call('PTTL', KEYS[2])}
            """, List.class);
    private static final DefaultRedisScript<Long> WRITE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> INVALIDATE = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1])
            for i = 2, #KEYS do redis.call('DEL', KEYS[i]) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Jackson2JsonRedisSerializer<List<Product>> codec;
    private final String key;
    private final String versionKey;

    ProductCacheStore(StringRedisTemplate redis, ObjectMapper mapper, @Value("${app.product-cache.key}") String key) {
        this.redis = redis;
        this.key = key;
        this.versionKey = key + ":version";
        var listType = mapper.getTypeFactory().constructCollectionType(List.class, Product.class);
        this.codec = new Jackson2JsonRedisSerializer<>(mapper, listType);
    }

    Shared readList() { return read(key); }

    Shared readMissing(long id) { return read(slot(id)); }

    private Shared read(String dataKey) {
        var result = execute(READ, List.of(versionKey, dataKey), UUID.randomUUID().toString());
        return new Shared((String) result.get(0), (String) result.get(1), (Long) result.get(2));
    }

    List<Product> decode(Shared data) {
        return codec.deserialize(data.value().getBytes(StandardCharsets.UTF_8));
    }

    boolean writeList(String version, List<Product> products, long ttl) {
        // 沿用已有的商品数组格式，版本独立存放。
        return write(key, version, new String(codec.serialize(products), StandardCharsets.UTF_8), ttl);
    }

    void writeMissing(String version, long id) {
        write(slot(id), version, version + ":" + id, 5_000);
    }

    private boolean write(String dataKey, String version, String value, long ttl) {
        return execute(WRITE, List.of(versionKey, dataKey), version, value, Long.toString(ttl)) == 1L;
    }

    void invalidate(Long publishedId) {
        var keys = new ArrayList<>(List.of(versionKey, key));
        if (publishedId != null) keys.add(slot(publishedId));
        execute(INVALIDATE, keys, UUID.randomUUID().toString());
    }

    private <T> T execute(DefaultRedisScript<T> script, List<String> keys, Object... arguments) {
        try {
            return redis.execute(script, keys, arguments);
        } catch (RedisSystemException error) {
            // Lettuce 已建立的连接被重置时，底层 I/O 异常有时未被归类为连接错误。
            if (error.getMostSpecificCause() instanceof IOException) {
                throw new RedisConnectionFailureException("商品缓存连接已中断", error);
            }
            throw error;
        }
    }

    private String slot(long id) { return key + ":missing:" + (id % 256); }

    record Shared(String version, String value, long remainingMs) {}
}
