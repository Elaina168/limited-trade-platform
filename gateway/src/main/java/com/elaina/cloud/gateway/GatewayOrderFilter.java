package com.elaina.cloud.gateway;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.IntSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayOrderFilter implements GlobalFilter, Ordered {
    private static final String ORDER_WRITES = "gateway-order-writes";
    private final byte[] bearer;
    private final IntSupplier quota;
    private volatile int appliedQuota;

    @Autowired
    public GatewayOrderFilter(@Value("${lab.buyer-token}") String token, Environment environment) {
        this(token, () -> environment.getRequiredProperty("lab.gateway.qps", Integer.class));
    }

    GatewayOrderFilter(String token, IntSupplier quota) {
        this.bearer = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.quota = quota;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        var route = exchange.<Route>getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        boolean orderRoute = "orders".equals(route.getId());
        if (orderRoute) {
            String credential = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (credential == null || !MessageDigest.isEqual(bearer, credential.getBytes(StandardCharsets.UTF_8))) {
                return reject(exchange, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
            }
            if (HttpMethod.POST.equals(request.getMethod())) {
                refreshRule(quota.getAsInt());
                try {
                    // Sentinel 在网关线程上只核发入场许可，转发由 Reactor 执行。
                    Entry entry = SphU.entry(ORDER_WRITES, EntryType.IN);
                    entry.exit();
                } catch (BlockException blocked) {
                    exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
                    return reject(exchange, HttpStatus.TOO_MANY_REQUESTS, "ORDER_RATE_LIMITED");
                }
            }
        }
        return chain.filter(exchange.mutate().request(request.mutate().headers(headers -> {
            headers.remove("X-User-Id");
            headers.remove(HttpHeaders.AUTHORIZATION);
            if (orderRoute) {
                // 隔离实验使用单一演示账户，入口只信任已核验的凭据。
                headers.set("X-User-Id", "1");
            }
        }).build()).build());
    }

    private void refreshRule(int qps) {
        if (appliedQuota != qps) {
            synchronized (this) {
                if (appliedQuota != qps) {
                    var rule = new FlowRule(ORDER_WRITES).setGrade(RuleConstant.FLOW_GRADE_QPS).setCount(qps);
                    FlowRuleManager.loadRules(List.of(rule));
                    appliedQuota = qps;
                }
            }
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
