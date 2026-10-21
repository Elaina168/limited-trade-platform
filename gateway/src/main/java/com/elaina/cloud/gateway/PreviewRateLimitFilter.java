package com.elaina.cloud.gateway;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class PreviewRateLimitFilter implements GlobalFilter,Ordered {
    private final RateLimiter total;
    private final RateLimiterRegistry sources;
    private final long retrySeconds;
    public PreviewRateLimitFilter(@Value("${lab.limit.total}") int total,
            @Value("${lab.limit.per-ip}") int perIp,@Value("${lab.limit.period}") Duration period){
        this.total=RateLimiter.of("preview-total",RateLimiterConfig.custom().limitForPeriod(total)
            .limitRefreshPeriod(period).timeoutDuration(Duration.ZERO).build());
        sources=RateLimiterRegistry.of(RateLimiterConfig.custom().limitForPeriod(perIp)
            .limitRefreshPeriod(period).timeoutDuration(Duration.ZERO).build());
        retrySeconds=Math.max(1,(period.toMillis()+999)/1000);
    }
    @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain){
        var request=exchange.getRequest();
        // 使用网关已匹配的路由，避免矩阵参数等路径写法绕过相同业务的额度。
        Route route=exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if(request.getMethod()!=HttpMethod.POST || !route.getId().equals("preview"))
            return chain.filter(exchange);
        // 本机实验直接取TCP对端，客户端转发头不能换取新额度。
        String ip=request.getRemoteAddress().getAddress().getHostAddress();
        if(!sources.rateLimiter(ip).acquirePermission()) return reject(exchange,"ip");
        if(!total.acquirePermission()) return reject(exchange,"total");
        return chain.filter(exchange);
    }
    private Mono<Void> reject(ServerWebExchange exchange,String scope){
        var response=exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After",Long.toString(retrySeconds));
        response.getHeaders().set("X-RateLimit-Scope",scope);
        byte[] body=("{\"error\":\"RATE_LIMITED\",\"scope\":\""+scope+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
    @Override public int getOrder(){return -90;}
}
