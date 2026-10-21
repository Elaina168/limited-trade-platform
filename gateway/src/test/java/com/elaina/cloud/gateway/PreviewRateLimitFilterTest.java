package com.elaina.cloud.gateway;

import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class PreviewRateLimitFilterTest {
    private MockServerWebExchange request(String method,String path,String forwarded) {
        var exchange=MockServerWebExchange.from(MockServerHttpRequest.method(method,path)
            .remoteAddress(new InetSocketAddress("127.0.0.1",60000)).header("X-Forwarded-For",forwarded).build());
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
            Route.async().id(path.equals("/api/else") ? "other" : "preview").uri("http://127.0.0.1").predicate(e->true).build());
        return exchange;
    }
    @Test void forgedForwardedIpCannotObtainAnotherBudget() {
        var filter=new PreviewRateLimitFilter(10,1,Duration.ofMinutes(1));
        var first=request("POST","/api/previews","1.1.1.1");
        filter.filter(first,e->Mono.empty()).block();
        var second=request("POST","/api/previews","2.2.2.2");
        filter.filter(second,e->Mono.empty()).block();
        assertEquals(429,second.getResponse().getStatusCode().value());
        assertEquals("ip",second.getResponse().getHeaders().getFirst("X-RateLimit-Scope"));
    }
    @Test void unrelatedMethodAndRouteDoNotConsumeBudget() {
        var filter=new PreviewRateLimitFilter(1,1,Duration.ofMinutes(1));
        filter.filter(request("GET","/api/previews","ignored"),e->Mono.empty()).block();
        filter.filter(request("POST","/api/else","ignored"),e->Mono.empty()).block();
        var actual=request("POST","/api/previews","ignored");
        filter.filter(actual,e->Mono.empty()).block();
        assertNull(actual.getResponse().getStatusCode());
    }
    @Test void totalBudgetIsSharedAcrossSourceAddresses() {
        var filter=new PreviewRateLimitFilter(1,10,Duration.ofMinutes(1));
        filter.filter(request("POST","/api/previews","ignored"),e->Mono.empty()).block();
        var second=MockServerWebExchange.from(MockServerHttpRequest.post("/api/previews")
            .remoteAddress(new InetSocketAddress("127.0.0.2",60000)).build());
        second.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
            Route.async().id("preview").uri("http://127.0.0.1").predicate(e->true).build());
        filter.filter(second,e->Mono.empty()).block();
        assertEquals("total",second.getResponse().getHeaders().getFirst("X-RateLimit-Scope"));
    }
    @Test void matrixParametersOnSameRouteCannotBypassBudget() {
        var filter=new PreviewRateLimitFilter(1,1,Duration.ofMinutes(1));
        filter.filter(request("POST","/api/previews","ignored"),e->Mono.empty()).block();
        var alias=request("POST","/api/previews;x=1","ignored");
        filter.filter(alias,e->Mono.empty()).block();
        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,alias.getResponse().getStatusCode());
    }
}
