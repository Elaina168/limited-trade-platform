package com.elaina.cloud.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

@Configuration(proxyBeanMethods = false)
public class GatewayConfiguration {
    @Bean
    RouteLocator labRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("aggregate", r -> r.method(HttpMethod.GET).and().path("/api/aggregate/products/{id}")
                        .uri("lb://order-service"))
                .route("orders", r -> r.path("/api/orders", "/api/orders/{id}", "/api/orders/{id}/cancel")
                        .uri("lb://order-service"))
                .route("products", r -> r.method(HttpMethod.GET).and().path("/api/products/{id}")
                        .filters(f -> f.setPath("/internal/products/{id}"))
                        .uri("lb://product-stock-service"))
                .build();
    }
}
