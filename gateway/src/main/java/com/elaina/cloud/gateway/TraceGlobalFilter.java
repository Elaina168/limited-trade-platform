package com.elaina.cloud.gateway;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class TraceGlobalFilter implements GlobalFilter,Ordered {
    private final String instance;
    public TraceGlobalFilter(@Value("${lab.instance}") String instance){this.instance=instance;}
    @Override public Mono<Void> filter(ServerWebExchange exchange,GatewayFilterChain chain){
        String trace=UUID.randomUUID().toString().replace("-", "");
        var response=exchange.getResponse();
        response.getHeaders().set("X-Trace-Id",trace);
        response.getHeaders().set("X-Gateway-Instance",instance);
        var traced=exchange.mutate().request(exchange.getRequest().mutate().headers(h->h.set("X-Trace-Id",trace)).build()).build();
        return chain.filter(traced).doFinally(signal->LoggerFactory.getLogger(TraceGlobalFilter.class)
            .info("traceId={} instance={} method={} path={} status={}",trace,instance,exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath(),response.getStatusCode()));
    }
    @Override public int getOrder(){return -100;}
}
