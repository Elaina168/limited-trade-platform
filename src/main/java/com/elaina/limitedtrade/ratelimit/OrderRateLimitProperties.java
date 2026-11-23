package com.elaina.limitedtrade.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.order-rate-limit", ignoreUnknownFields = false)
public record OrderRateLimitProperties(
        boolean enabled,
        @Min(1) int maxRequests,
        @Min(1) int windowSeconds,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{1,100}") String keyPrefix,
        @NotNull @DefaultValue("fixed-window") Algorithm algorithm) {

    public enum Algorithm { FIXED_WINDOW, TOKEN_BUCKET }

    public OrderRateLimitProperties {
        // Lua 使用双精度数值。限制整桶计量单位，留出一次补充计算所需的精度。
        if (algorithm == Algorithm.TOKEN_BUCKET && (long) maxRequests * windowSeconds > 4_503_599_627_370L) {
            throw new IllegalArgumentException("令牌桶配置的容量与补充周期乘积过大");
        }
    }
}
