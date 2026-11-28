package com.elaina.cloud.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductAggregationTest {
    @Test void parallelFanoutCarriesTraceAndPreservesPrimaryProductWhenMarketingFails() {
        var products = mock(ProductClient.class);
        var marketing = mock(MarketingClient.class);
        var circuit = new MarketingCircuit(200, 20, 1);
        var config = new MockEnvironment();
        var gate = new CountDownLatch(3);
        var product = new ProductClient.Product(5, "商品", BigDecimal.TEN, 9, "stock-b");
        when(products.product(5)).thenAnswer(call -> { rendezvous(gate); return product; });
        when(marketing.activities(5)).thenAnswer(call -> { rendezvous(gate); return List.of(); });
        when(marketing.coupons(5)).thenAnswer(call -> { rendezvous(gate); throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); });
        try (var executor = new AggregationExecutorConfiguration().aggregationExecutor();
             var ignored = MDC.putCloseable(TraceContextFilter.KEY, "parallel-test")) {
            var detail = new ProductAggregation(products, marketing, circuit, config, executor).detail(5, false);
            assertEquals(product, detail.product());
            assertEquals(List.of(), detail.coupons());
            assertTrue(detail.marketingDegraded());
        } finally { circuit.close(); }
    }

    @Test void sentinelOpensOnSlowCallsAndRecordsHalfOpenRecovery() throws InterruptedException {
        var circuit = new MarketingCircuit(30, 2, 1);
        try {
            for (int index = 0; index < 2; index++) circuit.read("activities", () -> {
                try { Thread.sleep(80); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                return List.of("活动");
            });
            assertEquals("OPEN", circuit.status().get("activities").get("slow"));
            assertTrue(circuit.read("activities", () -> { throw new AssertionError("熔断请求不应访问下游"); }).degraded());
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertFalse(circuit.read("activities", () -> List.of("恢复")).degraded()));
            assertEquals("CLOSED", circuit.status().get("activities").get("slow"));
            assertEquals(1, circuit.status().get("activities").get("slowHalfOpens"));
        } finally { circuit.close(); }
    }

    private void rendezvous(CountDownLatch gate) throws InterruptedException {
        assertEquals("parallel-test", MDC.get(TraceContextFilter.KEY));
        gate.countDown();
        assertTrue(gate.await(2, TimeUnit.SECONDS), "三个调用必须有重叠");
    }
}
