package com.elaina.limitedtrade.observability;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.order-message.monitor", ignoreUnknownFields = false)
public record OrderMessageMonitorProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30000") @Min(1000) long intervalMs,
        @DefaultValue("60") @Min(1) long pendingAgeSeconds,
        @DefaultValue("100") @Min(1) long readyMessages,
        @DefaultValue("60") @Min(1) long overdueSeconds) {
}
