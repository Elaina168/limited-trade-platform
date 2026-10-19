package com.elaina.limitedtrade.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OrderRateLimitProperties.class)
public class OrderRateLimitConfiguration {
}
