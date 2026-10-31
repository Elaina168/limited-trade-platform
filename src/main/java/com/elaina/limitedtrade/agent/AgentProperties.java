package com.elaina.limitedtrade.agent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.agent", ignoreUnknownFields = false)
public record AgentProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") @NotBlank String apiKey,
        @DefaultValue("https://api.deepseek.com") @NotBlank String baseUrl,
        @DefaultValue("deepseek-flash") @NotBlank String model,
        @DefaultValue("10s") Duration modelTimeout,
        @DefaultValue("45s") Duration requestTimeout,
        @DefaultValue("4") @Min(1) @Max(10) int maxModelCalls,
        @DefaultValue("4") @Min(1) @Max(10) int maxToolCalls,
        @DefaultValue("1024") @Min(1) @Max(4096) int maxOutputTokens) {
    public AgentProperties {
        Assert.isTrue(modelTimeout.isPositive() && modelTimeout.compareTo(Duration.ofMinutes(5)) <= 0,
                "模型超时必须大于零且不超过五分钟");
        Assert.isTrue(requestTimeout.isPositive() && requestTimeout.compareTo(Duration.ofMinutes(5)) <= 0,
                "请求截止时间必须大于零且不超过五分钟");
    }
}
