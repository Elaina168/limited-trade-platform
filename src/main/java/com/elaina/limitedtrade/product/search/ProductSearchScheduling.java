package com.elaina.limitedtrade.product.search;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class ProductSearchScheduling {
    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler productSearchTaskScheduler() {
        // 索引网络重试使用独立线程，避免拖慢关单和抢购对账任务。
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("product-search-sync-");
        return scheduler;
    }
}
