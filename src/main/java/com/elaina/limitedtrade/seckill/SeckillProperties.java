package com.elaina.limitedtrade.seckill;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.seckill")
public record SeckillProperties(
        boolean enabled,
        String keyPrefix,
        long requestTtlSeconds,
        long inflightStaleMillis,
        long reconcileDelayMs) {
    public SeckillProperties {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "limited-trade:seckill";
        }
        if (requestTtlSeconds <= 0) {
            requestTtlSeconds = 86_400;
        }
        if (inflightStaleMillis <= 0) {
            inflightStaleMillis = 3_000;
        }
        if (reconcileDelayMs <= 0) {
            reconcileDelayMs = 5_000;
        }
    }
}
