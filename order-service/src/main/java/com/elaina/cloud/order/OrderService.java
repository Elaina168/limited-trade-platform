package com.elaina.cloud.order;

import feign.FeignException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class OrderService {
    private static final Logger LOG = LoggerFactory.getLogger(OrderService.class);
    private final OrderRepository orders;
    private final OrderOutbox outbox;
    private final ProductClient products;
    private final MarketingClient marketing;
    private final long timeoutMs;

    OrderService(OrderRepository orders, OrderOutbox outbox, ProductClient products, MarketingClient marketing,
                 @Value("${lab.order.timeout-ms:4000}") long timeoutMs) {
        this.orders = orders;
        this.outbox = outbox;
        this.products = products;
        this.marketing = marketing;
        this.timeoutMs = timeoutMs;
    }

    @Transactional(timeout = 8)
    public Creation create(long userId, Request request) {
        var existing = orders.request(userId, request.requestId(), false);
        if (existing.isPresent()) return replay(existing.get(), request);

        Instant openedAt = Instant.now();
        long id;
        try {
            id = orders.insert(userId, request, openedAt.plusMillis(timeoutMs));
        } catch (DuplicateKeyException concurrent) {
            // 同一用户重复请求等到先行事务完成，再读取其确切成交参数。
            return replay(orders.request(userId, request.requestId(), true).orElseThrow(), request);
        }
        outbox.watchRollback(id, openedAt);
        BigDecimal total;
        try {
            var product = products.product(request.productId());
            total = product.price().multiply(BigDecimal.valueOf(request.quantity()));
            products.lock(id, new ProductClient.StockRequest(request.productId(), request.quantity()));
            if (request.couponId() != null) {
                var coupon = marketing.reserve(id, new MarketingClient.CouponRequest(request.productId(), request.couponId()));
                if (total.compareTo(coupon.threshold()) < 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "订单金额未达到优惠券使用门槛");
                }
                total = total.subtract(coupon.discount()).max(BigDecimal.ZERO);
            }
        } catch (FeignException failure) {
            throw MarketingClient.downstream(failure);
        }
        Instant expiresAt = Instant.now().plusMillis(timeoutMs);
        orders.finish(id, total, expiresAt);
        outbox.enqueue(id, OrderOutbox.Event.TIMEOUT, expiresAt, Instant.now());
        LOG.info("event=order_created orderId={} userId={}", id, userId);
        return new Creation(new OrderRepository.Order(id, userId, request.requestId(), request.productId(), request.quantity(),
            request.couponId(), total, OrderRepository.PENDING, expiresAt), true);
    }

    private Creation replay(OrderRepository.Order row, Request request) {
        if (row.productId() != request.productId() || row.quantity() != request.quantity()
                || !Objects.equals(row.couponId(), request.couponId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同一请求标识不能修改购买参数");
        }
        return new Creation(row, false);
    }

    OrderRepository.Order owned(long userId, long id) {
        return orders.owned(userId, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
    }

    @Transactional
    public OrderRepository.Order cancel(long userId, long id) {
        if (orders.cancel(id, userId)) {
            Instant now = Instant.now();
            outbox.enqueue(id, OrderOutbox.Event.RELEASE, now, now);
        }
        var order = owned(userId, id);
        if (!OrderRepository.CANCELED.equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前状态不可取消");
        }
        return order;
    }

    @Transactional
    public void expire(long id, String trigger) {
        Instant now = Instant.now();
        if (orders.expire(id, now)) {
            outbox.enqueue(id, OrderOutbox.Event.RELEASE, now, now);
            LOG.info("event=order_timeout orderId={} trigger={}", id, trigger);
        }
    }

    record Request(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestId, @Positive long productId,
                   @Min(1) @Max(100) int quantity, @Positive Long couponId) {}
    record Creation(OrderRepository.Order order, boolean created) {}
}
