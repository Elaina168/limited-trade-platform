package com.elaina.cloud.gateway;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GatewayOrderFilterTest {
    @Test
    void validatesCredentialAndReplacesSuppliedUserId() {
        var filter = new GatewayOrderFilter("lab-buyer-1", () -> 1000);
        var denied = exchange("orders", MockServerHttpRequest.get("/api/orders/1").header("X-User-Id", "1"));
        filter.filter(denied, value -> Mono.error(new AssertionError("未经认证不能转发"))).block();
        assertEquals(401, denied.getResponse().getStatusCode().value());

        var supplied = exchange("orders", MockServerHttpRequest.get("/api/orders/1")
                .header("X-User-Id", "99").header("Authorization", "Bearer lab-buyer-1"));
        var user = new AtomicReference<String>();
        filter.filter(supplied, value -> {
            user.set(value.getRequest().getHeaders().getFirst("X-User-Id"));
            assertNull(value.getRequest().getHeaders().getFirst("Authorization"));
            return Mono.empty();
        }).block();
        assertEquals("1", user.get());
    }

    @Test
    void publicRouteCannotForwardASuppliedIdentity() {
        var filter = new GatewayOrderFilter("lab-buyer-1", () -> 1000);
        var publicRequest = exchange("aggregate", MockServerHttpRequest.get("/api/aggregate/products/1").header("X-User-Id", "42"));
        filter.filter(publicRequest, value -> {
            assertNull(value.getRequest().getHeaders().getFirst("X-User-Id"));
            return Mono.empty();
        }).block();
    }

    private MockServerWebExchange exchange(String routeId, MockServerHttpRequest.BaseBuilder<?> request) {
        var exchange = MockServerWebExchange.from(request.build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id(routeId).uri("http://127.0.0.1").predicate(value -> true).build());
        return exchange;
    }
}
