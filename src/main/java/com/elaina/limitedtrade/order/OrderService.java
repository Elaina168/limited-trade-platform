package com.elaina.limitedtrade.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import com.elaina.limitedtrade.activity.Activity;
import com.elaina.limitedtrade.activity.ActivityMapper;
import com.elaina.limitedtrade.product.Product;
import com.elaina.limitedtrade.product.ProductCache;
import com.elaina.limitedtrade.product.ProductMapper;
import com.elaina.limitedtrade.order.message.OrderTimeoutOutbox;
import com.elaina.limitedtrade.order.message.OrderTimeoutOutboxMapper;
import com.elaina.limitedtrade.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

    private final AppUserMapper appUserMapper;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final ActivityMapper activityMapper;
    private final ProductCache productCache;
    private final OrderTimeoutOutboxMapper outboxMapper;

    public OrderService(AppUserMapper appUserMapper, ProductMapper productMapper, OrderMapper orderMapper,
                        ActivityMapper activityMapper, ProductCache productCache,
                        OrderTimeoutOutboxMapper outboxMapper) {
        this.appUserMapper = appUserMapper;
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.activityMapper = activityMapper;
        this.productCache = productCache;
        this.outboxMapper = outboxMapper;
    }

    @Transactional
    public CreateOrderResult createOrder(long userId, CreateOrderRequest request) {
        // 同一用户的下单串行执行，锁在事务提交或回滚时释放。
        appUserMapper.lockById(userId);
        TradeOrder existing = orderMapper.findByUserAndRequest(userId, request.requestId());
        if (existing != null) {
            if (!existing.getProductId().equals(request.productId())
                    || !existing.getQuantity().equals(request.quantity())
                    || !Objects.equals(existing.getActivityId(), request.activityId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请求标识已用于另一笔购买");
            }
            return new CreateOrderResult(existing, false);
        }

        if (request.activityId() != null) {
            Activity activity = activityMapper.findByIdForUpdate(request.activityId());
            if (activity == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在");
            }
            if (!activity.getProductId().equals(request.productId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "活动与商品不匹配");
            }
        }

        Product product = productMapper.findById(request.productId());
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        if (productMapper.deductStock(request.productId(), request.quantity()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已下架或库存不足");
        }

        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 活动行锁先于商品写锁；时间在两者锁等待结束后取得，防止接受已结束活动的新订单。
        if (request.activityId() != null
                && activityMapper.deductQuantity(request.activityId(), request.quantity(), now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "活动未开始、已结束或名额不足");
        }
        TradeOrder order = new TradeOrder();
        order.setUserId(userId);
        order.setProductId(product.getId());
        order.setActivityId(request.activityId());
        order.setProductName(product.getName());
        order.setUnitPrice(product.getPrice());
        order.setQuantity(request.quantity());
        order.setTotalAmount(product.getPrice().multiply(BigDecimal.valueOf(request.quantity())));
        order.setStatus("PENDING_PAYMENT");
        order.setRequestId(request.requestId());
        order.setCreatedAt(now);
        order.setExpiresAt(now.plusMinutes(15));
        orderMapper.insertOrder(order);
        // 订单与发送意图同事务保存；幂等提前返回不会再登记事件。
        OrderTimeoutOutbox event = new OrderTimeoutOutbox();
        event.setOrderId(order.getId());
        event.setExpiresAt(order.getExpiresAt());
        event.setStatus("PENDING");
        event.setAttemptCount(0L);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        outboxMapper.insertEvent(event);
        productCache.evictAfterCommit();
        return new CreateOrderResult(order, true);
    }

    public List<TradeOrder> listUserOrders(long userId) {
        return orderMapper.findUserOrders(userId);
    }

    public TradeOrder getUserOrder(long userId, long id) {
        TradeOrder order = orderMapper.findOwnedOrder(id, userId);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    @Transactional
    public TradeOrder payOrder(long userId, long id) {
        TradeOrder order = lockUserOrder(userId, id);
        if ("PAID".equals(order.getStatus())) {
            return order;
        }
        // 先取得订单行锁，再取当前时间，避免锁等待导致截止时间判断使用过早的时间。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (orderMapper.markPaid(id, userId, now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单已过支付截止时间或状态不允许支付");
        }
        return orderMapper.findOwnedOrder(id, userId);
    }

    @Transactional
    public TradeOrder cancelOrder(long userId, long id) {
        TradeOrder order = lockUserOrder(userId, id);
        if ("CANCELED".equals(order.getStatus())) {
            return order;
        }
        if (orderMapper.markCanceled(id, userId, LocalDateTime.now().withNano(0)) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前订单状态不允许取消");
        }
        if (order.getActivityId() != null) {
            activityMapper.restoreQuantity(order.getActivityId(), order.getQuantity());
        }
        productMapper.restoreStock(order.getProductId(), order.getQuantity());
        productCache.evictAfterCommit();
        return orderMapper.findOwnedOrder(id, userId);
    }

    @Transactional
    public boolean closeExpiredOrder(long id, LocalDateTime cutoff) {
        // 条件更新本身会取得行锁；只有成功改变状态的事务才归还库存。
        if (orderMapper.markClosed(id, cutoff) == 0) {
            return false;
        }
        TradeOrder order = orderMapper.findById(id);
        if (order.getActivityId() != null) {
            activityMapper.restoreQuantity(order.getActivityId(), order.getQuantity());
        }
        productMapper.restoreStock(order.getProductId(), order.getQuantity());
        productCache.evictAfterCommit();
        return true;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminOrderPageResponse listAdminOrders(int page, int size, String status, Long userId) {
        // 先转换为 long 再乘，避免外部输入的大页码造成 int 溢出。
        long offset = (long) (page - 1) * size;
        long total = orderMapper.countAdminOrders(status, userId);
        List<AdminOrderResponse> items = orderMapper.findAdminOrders(status, userId, offset, size)
                .stream().map(AdminOrderResponse::from).toList();
        return new AdminOrderPageResponse(page, size, total, items);
    }

    private TradeOrder lockUserOrder(long userId, long id) {
        TradeOrder order = orderMapper.findOwnedOrderForUpdate(id, userId);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        return order;
    }
}
