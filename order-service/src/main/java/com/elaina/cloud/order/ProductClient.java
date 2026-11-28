package com.elaina.cloud.order;

import java.math.BigDecimal;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "product-stock-service", fallbackFactory = ProductClientFallback.class)
interface ProductClient {
    @GetMapping("/internal/products/{id}")
    Product product(@PathVariable("id") long id);

    @PostMapping("/internal/stock/{id}/lock")
    void lock(@PathVariable("id") long orderId, @RequestBody StockRequest request);

    @PostMapping("/internal/stock/{id}/release")
    void release(@PathVariable("id") long orderId);

    record Product(long id, String name, BigDecimal price, int stock, String instance) {}
    record StockRequest(long productId, int quantity) {}
}

@Component
class ProductClientFallback implements FallbackFactory<ProductClient> {
    @Override public ProductClient create(Throwable cause) {
        return new ProductClient() {
            @Override public Product product(long id) { throw MarketingClient.downstream(cause); }
            @Override public void lock(long orderId, StockRequest request) { throw MarketingClient.downstream(cause); }
            @Override public void release(long orderId) { throw MarketingClient.downstream(cause); }
        };
    }
}
