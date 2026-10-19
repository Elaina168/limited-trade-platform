package com.elaina.limitedtrade.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RedisOrderRateLimiter {
    private final StringRedisTemplate redis;
    private final OrderRateLimitProperties properties;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();

    public RedisOrderRateLimiter(StringRedisTemplate redis, OrderRateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
        script.setLocation(new ClassPathResource("redis/order-rate-limit.lua"));
        script.setResultType(Long.class);
    }

    public void check(long userId) {
        if (!properties.enabled()) {
            return;
        }
        String key = properties.keyPrefix() + ":user:" + userId;
        long retryAfter;
        try {
            retryAfter = redis.execute(script, List.of(key), Integer.toString(properties.maxRequests()),
                    Long.toString(Duration.ofSeconds(properties.windowSeconds()).toMillis()));
        } catch (RedisConnectionFailureException | QueryTimeoutException exception) {
            // 外部限流服务不可用时拒绝下单，数据库事务尚未开始。
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "下单限流服务暂不可用", exception);
        }
        if (retryAfter > 0) {
            throw new OrderRateLimitExceededException(retryAfter);
        }
    }
}
