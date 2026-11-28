package com.elaina.cloud.order;

import feign.FeignException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ResponseStatusException;

@FeignClient(name = "marketing-service", fallbackFactory = MarketingClientFallback.class)
interface MarketingClient {
    @GetMapping("/internal/marketing/{id}/activities")
    List<Activity> activities(@PathVariable("id") long id);

    @GetMapping("/internal/marketing/{id}/coupons")
    List<Coupon> coupons(@PathVariable("id") long id);

    @PostMapping("/internal/coupons/{id}/reserve")
    Reservation reserve(@PathVariable("id") long orderId, @RequestBody CouponRequest request);

    @PostMapping("/internal/coupons/{id}/release")
    void release(@PathVariable("id") long orderId);

    record Activity(long id, long productId, String name, String startsAt, String endsAt) {}
    record Coupon(long id, long productId, String name, BigDecimal threshold, BigDecimal discount, int remaining) {}
    record Reservation(BigDecimal threshold, BigDecimal discount) {}
    record CouponRequest(long productId, long couponId) {}

    static ResponseStatusException downstream(Throwable cause) {
        if (cause instanceof FeignException failure && failure.status() >= 400 && failure.status() < 500) {
            return new ResponseStatusException(HttpStatusCode.valueOf(failure.status()), "下游拒绝请求", cause);
        }
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "下游服务暂不可用", cause);
    }
}

@Component
class MarketingClientFallback implements FallbackFactory<MarketingClient> {
    @Override public MarketingClient create(Throwable cause) {
        return new MarketingClient() {
            @Override public List<Activity> activities(long id) { throw MarketingClient.downstream(cause); }
            @Override public List<Coupon> coupons(long id) { throw MarketingClient.downstream(cause); }
            @Override public Reservation reserve(long orderId, CouponRequest request) { throw MarketingClient.downstream(cause); }
            @Override public void release(long orderId) { throw MarketingClient.downstream(cause); }
        };
    }
}
