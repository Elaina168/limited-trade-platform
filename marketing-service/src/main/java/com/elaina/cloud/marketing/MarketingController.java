package com.elaina.cloud.marketing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class MarketingController {
    private final JdbcTemplate jdbc;
    private final CouponTasks tasks;
    private final MarketingBehavior behavior;

    MarketingController(JdbcTemplate jdbc, CouponTasks tasks, MarketingBehavior behavior) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.behavior = behavior;
    }

    @GetMapping("/internal/marketing/{id}/activities")
    List<Activity> activities(@PathVariable long id) throws InterruptedException {
        String name = behavior.availableCampaign();
        return jdbc.query("""
                SELECT id,product_id,starts_at,ends_at FROM marketing_activity
                WHERE product_id=? AND starts_at<=CURRENT_TIMESTAMP AND ends_at>CURRENT_TIMESTAMP ORDER BY id
                """, (rs, row) -> new Activity(rs.getLong(1), rs.getLong(2), name,
                rs.getObject(3, LocalDateTime.class), rs.getObject(4, LocalDateTime.class)), id);
    }

    @GetMapping("/internal/marketing/{id}/coupons")
    List<Coupon> coupons(@PathVariable long id) throws InterruptedException {
        behavior.availableCampaign();
        return jdbc.query("""
                SELECT id,product_id,name,threshold_amount,discount,remaining_quantity FROM marketing_coupon
                WHERE product_id=? AND remaining_quantity>0
                  AND starts_at<=CURRENT_TIMESTAMP AND ends_at>CURRENT_TIMESTAMP ORDER BY id
                """, (rs, row) -> new Coupon(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getInt(6)), id);
    }

    @PostMapping("/internal/coupons/{orderId}/reserve")
    CouponTasks.Reservation reserve(@PathVariable long orderId, @RequestBody CouponRequest request)
            throws InterruptedException {
        if (orderId <= 0 || request.couponId() <= 0 || request.productId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单、商品和券编号必须为正数");
        }
        behavior.availableCampaign();
        return tasks.reserve(orderId, request.productId(), request.couponId());
    }

    @PostMapping("/internal/coupons/{orderId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable long orderId) {
        if (orderId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单编号必须为正数");
        // 演练营销查询故障时，事务清理入口仍可正常归还优惠券。
        tasks.release(orderId);
    }

    record Activity(long id, long productId, String name, LocalDateTime startsAt, LocalDateTime endsAt) {}
    record Coupon(long id, long productId, String name, BigDecimal threshold, BigDecimal discount, int remaining) {}
    record CouponRequest(long productId, long couponId) {}
}

@Component
@RefreshScope
class MarketingBehavior {
    private final String campaign;
    private final boolean degraded;
    private final long delayMs;

    MarketingBehavior(@Value("${lab.campaign}") String campaign,
                      @Value("${lab.marketing.degraded}") boolean degraded,
                      @Value("${lab.marketing.delay-ms}") long delayMs) {
        this.campaign = campaign;
        this.degraded = degraded;
        this.delayMs = delayMs;
    }

    public String availableCampaign() throws InterruptedException {
        if (degraded) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "营销服务暂时不可用");
        Thread.sleep(delayMs);
        return campaign;
    }
}
