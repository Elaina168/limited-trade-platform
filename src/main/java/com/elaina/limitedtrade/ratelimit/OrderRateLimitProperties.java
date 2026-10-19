package com.elaina.limitedtrade.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.order-rate-limit", ignoreUnknownFields = false)
public record OrderRateLimitProperties(
        boolean enabled,
        @Min(1) int maxRequests,
        @Min(1) int windowSeconds,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{1,100}") String keyPrefix) {
}
