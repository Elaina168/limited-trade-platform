package com.elaina.cloud.gateway;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class GatewayConfiguration {
    @Bean RouteLocator labRoutes(RouteLocatorBuilder builder){
        return builder.routes()
            .route("preview",r->r.path("/api/previews").uri("lb://preview-service"))
            .route("products",r->r.path("/api/products/**").uri("lb://product-service"))
            .build();
    }
}
