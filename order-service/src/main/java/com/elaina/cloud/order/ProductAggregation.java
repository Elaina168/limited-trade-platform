package com.elaina.cloud.order;

import feign.FeignException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class ProductAggregation {
    private final ProductClient products;
    private final MarketingClient marketing;
    private final MarketingCircuit circuit;
    private final Environment config;
    private final ExecutorService aggregationExecutor;

    ProductAggregation(ProductClient products, MarketingClient marketing, MarketingCircuit circuit, Environment config, ExecutorService aggregationExecutor) {
        this.products = products;
        this.marketing = marketing;
        this.circuit = circuit;
        this.config = config;
        this.aggregationExecutor = aggregationExecutor;
    }

    Detail detail(long id, boolean serial) {
        boolean degraded = config.getProperty("lab.order.marketing-degraded", Boolean.class, false);
        if (serial) return combine(product(id), activities(id, degraded), coupons(id, degraded));
        String trace = MDC.get(TraceContextFilter.KEY);
        try {
            var product = submit(trace, () -> product(id));
            var activities = submit(trace, () -> activities(id, degraded));
            var coupons = submit(trace, () -> coupons(id, degraded));
            return combine(product.join(), activities.join(), coupons.join());
        } catch (RejectedExecutionException overload) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "聚合请求过多", overload);
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw failure;
        }
    }

    ProductClient.Product product(long id) {
        try { return products.product(id); }
        catch (FeignException failure) { throw MarketingClient.downstream(failure); }
    }

    private MarketingCircuit.MarketingResult<MarketingClient.Activity> activities(long id, boolean degraded) {
        return degraded ? new MarketingCircuit.MarketingResult<>(List.of(), true) : circuit.read("activities", () -> marketing.activities(id));
    }

    private MarketingCircuit.MarketingResult<MarketingClient.Coupon> coupons(long id, boolean degraded) {
        return degraded ? new MarketingCircuit.MarketingResult<>(List.of(), true) : circuit.read("coupons", () -> marketing.coupons(id));
    }

    private Detail combine(ProductClient.Product product, MarketingCircuit.MarketingResult<MarketingClient.Activity> activities,
                           MarketingCircuit.MarketingResult<MarketingClient.Coupon> coupons) {
        return new Detail(product, activities.items(), coupons.items(), activities.degraded() || coupons.degraded());
    }

    private <T> CompletableFuture<T> submit(String trace, Supplier<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            // 执行器线程重复使用，显式设置和清理请求链路标识。
            if (trace == null) return work.get();
            try (var ignored = MDC.putCloseable(TraceContextFilter.KEY, trace)) { return work.get(); }
        }, aggregationExecutor);
    }

    record Detail(ProductClient.Product product, List<MarketingClient.Activity> activities,
                  List<MarketingClient.Coupon> coupons, boolean marketingDegraded) {}
}

@Configuration(proxyBeanMethods = false)
class AggregationExecutorConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService aggregationExecutor() {
        return new ThreadPoolExecutor(12, 12, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(48),
            Thread.ofPlatform().name("lab-order-aggregate-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
}
