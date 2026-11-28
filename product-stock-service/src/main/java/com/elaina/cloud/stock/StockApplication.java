package com.elaina.cloud.stock;

import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@EnableFeignClients
@SpringBootApplication
public class StockApplication {
    public static void main(String[] args) {
        SpringApplication.run(StockApplication.class, args);
    }

    @Bean
    RequestInterceptor traceHeader() {
        return request -> {
            String traceId = MDC.get("traceId");
            if (traceId != null) request.header("X-Trace-Id", traceId);
        };
    }
}
