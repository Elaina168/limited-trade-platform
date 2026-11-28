package com.elaina.cloud.order;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.EventObserverRegistry;
import feign.FeignException;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class MarketingCircuit {
    private static final String OBSERVER = "lab-order-marketing";
    private static final Logger LOG = LoggerFactory.getLogger(MarketingCircuit.class);
    private final Map<RuleKey, String> states = new ConcurrentHashMap<>();
    private final Map<RuleKey, AtomicInteger> halfOpens = new ConcurrentHashMap<>();

    MarketingCircuit(@Value("${lab.order.slow-ms:120}") long slowMs,
                     @Value("${lab.order.circuit-min-requests:4}") int minRequests,
                     @Value("${lab.order.circuit-open-seconds:2}") int openSeconds) {
        EventObserverRegistry.getInstance().addStateChangeObserver(OBSERVER, (before, after, rule, snapshot) -> {
            var key = new RuleKey(rule.getResource(), rule.getGrade());
            states.put(key, after.name());
            if ("HALF_OPEN".equals(after.name())) halfOpens.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
            LOG.info("resource={} grade={} state={} -> {} metric={}", rule.getResource(), rule.getGrade(), before, after, snapshot);
        });
        DegradeRuleManager.loadRules(List.of(
            rule("activities", CircuitBreakerStrategy.SLOW_REQUEST_RATIO, slowMs, minRequests, openSeconds),
            rule("activities", CircuitBreakerStrategy.ERROR_RATIO, 0.5, minRequests, openSeconds),
            rule("coupons", CircuitBreakerStrategy.SLOW_REQUEST_RATIO, slowMs, minRequests, openSeconds),
            rule("coupons", CircuitBreakerStrategy.ERROR_RATIO, 0.5, minRequests, openSeconds)
        ));
    }

    private DegradeRule rule(String resource, CircuitBreakerStrategy strategy, double threshold, int minimum, int openSeconds) {
        return new DegradeRule(resource).setGrade(strategy.getType()).setCount(threshold).setMinRequestAmount(minimum)
            .setSlowRatioThreshold(0.5).setTimeWindow(openSeconds).setStatIntervalMs(10_000);
    }

    <T> MarketingResult<T> read(String resource, Supplier<List<T>> request) {
        try (Entry entry = SphU.entry(resource)) {
            try {
                return new MarketingResult<>(request.get(), false);
            } catch (FeignException | ResponseStatusException failure) {
                Tracer.traceEntry(failure, entry);
                return new MarketingResult<>(List.of(), true);
            }
        } catch (BlockException blocked) {
            return new MarketingResult<>(List.of(), true);
        }
    }

    Map<String, Map<String, Object>> status() {
        return Map.of("activities", status("activities"), "coupons", status("coupons"));
    }

    private Map<String, Object> status(String resource) {
        Map<String, Object> result = new HashMap<>();
        var rules = Map.of("slow", CircuitBreakerStrategy.SLOW_REQUEST_RATIO, "error", CircuitBreakerStrategy.ERROR_RATIO);
        rules.forEach((name, strategy) -> {
            var key = new RuleKey(resource, strategy.getType());
            result.put(name, states.getOrDefault(key, "CLOSED"));
            AtomicInteger count = halfOpens.get(key);
            result.put(name + "HalfOpens", count == null ? 0 : count.get());
        });
        return result;
    }

    @PreDestroy
    void close() { EventObserverRegistry.getInstance().removeStateChangeObserver(OBSERVER); }

    private record RuleKey(String resource, int grade) {}
    record MarketingResult<T>(List<T> items, boolean degraded) {}
}
