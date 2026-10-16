package com.elaina.limitedtrade.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.order-timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderSchedulingConfiguration {
}
