package com.elaina.limitedtrade.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.elaina.limitedtrade.activity.Activity;
import com.elaina.limitedtrade.activity.ActivityMapper;
import com.elaina.limitedtrade.address.AddressMapper;
import com.elaina.limitedtrade.address.UserAddress;
import com.elaina.limitedtrade.cart.CartItemMapper;
import com.elaina.limitedtrade.coupon.CouponMapper;
import com.elaina.limitedtrade.coupon.UserCoupon;
import com.elaina.limitedtrade.product.Product;
import com.elaina.limitedtrade.product.ProductCache;
import com.elaina.limitedtrade.product.ProductMapper;
import com.elaina.limitedtrade.order.message.OrderTimeoutOutbox;
import com.elaina.limitedtrade.order.message.OrderTimeoutOutboxMapper;
import com.elaina.limitedtrade.user.AppUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final AppUserMapper appUserMapper;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final ActivityMapper activityMapper;
    private final ProductCache productCache;
    private final OrderTimeoutOutboxMapper outboxMapper;
    private final OrderItemMapper itemMapper;
    private final OrderAddressMapper orderAddressMapper;
    private final AddressMapper addressMapper;
    private final CartItemMapper cartMapper;
    private final OrderShipmentMapper shipmentMapper;
    private final CouponMapper couponMapper;

    public OrderService(AppUserMapper appUserMapper, ProductMapper productMapper, OrderMapper orderMapper,
                        ActivityMapper activityMapper, ProductCache productCache,
                        OrderTimeoutOutboxMapper outboxMapper, OrderItemMapper itemMapper,
                        OrderAddressMapper orderAddressMapper, AddressMapper addressMapper, CartItemMapper cartMapper,
                        OrderShipmentMapper shipmentMapper, CouponMapper couponMapper) {
        this.appUserMapper = appUserMapper;
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.activityMapper = activityMapper;
        this.productCache = productCache;
        this.outboxMapper = outboxMapper;
        this.itemMapper = itemMapper;
        this.orderAddressMapper = orderAddressMapper;
        this.addressMapper = addressMapper;
        this.cartMapper = cartMapper;
        this.shipmentMapper = shipmentMapper;
        this.couponMapper = couponMapper;
    }

    @Transactional
    public CreateOrderResult createOrder(long userId, CreateOrderRequest request) {
        appUserMapper.lockById(userId);
        TradeOrder existing = orderMapper.findByUserAndRequest(userId, request.requestId());
        if (existing != null) {
            loadDetails(List.of(existing));
            if (!"DIRECT".equals(existing.getOrderType())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请求标识已用于另一笔购买");
            }
            OrderItem item = existing.getItems().getFirst();
            if (!item.productId().equals(request.productId()) || !item.quantity().equals(request.quantity())
                    || !Objects.equals(item.activityId(), request.activityId())
                    || !Objects.equals(existing.getCoupon() == null ? null : existing.getCoupon().userCouponId(), request.userCouponId())
                    || !Objects.equals(existing.getAddress() == null ? null : existing.getAddress().sourceAddressId(), request.addressId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请求标识已用于另一笔购买");
            }
            return new CreateOrderResult(existing, false);
        }

        UserAddress address = null;
        if (request.addressId() != null) {
            // 与地址簿写入共用用户锁，成交快照读取期间地址不会被本人并发修改。
            address = addressMapper.findOwnedAddress(request.addressId(), userId);
            if (address == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地址不存在");
            }
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
        OrderItem item = deductItem(request.productId(), request.quantity(), request.activityId());
        UserCoupon owned = null;
        if (request.userCouponId() != null) {
            owned = couponMapper.findOwnedForUpdate(request.userCouponId(), userId);
            if (owned == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本人优惠券不存在");
            }
        }
        // 所有可能的行锁等待结束后采样，避免跨过券或活动截止时间仍下单。
        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 活动锁先于商品锁；等待结束后再判断活动时间。
        if (request.activityId() != null
                && activityMapper.deductQuantity(request.activityId(), request.quantity(), now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "活动未开始、已结束或名额不足");
        }
        OrderCoupon coupon = null;
        if (owned != null) {
            var rule = couponMapper.findCouponById(owned.getCouponId());
            if (!"AVAILABLE".equals(owned.getStatus()) || !rule.getActivityId().equals(request.activityId())
                    || now.isBefore(rule.getUseStartsAt()) || !now.isBefore(rule.getUseEndsAt())
                    || item.lineAmount().compareTo(rule.getThresholdAmount()) < 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "优惠券状态、活动、使用时间或满减门槛不满足");
            }
            coupon = new OrderCoupon(null, owned.getId(), rule.getId(), rule.getActivityId(), rule.getName(),
                    rule.getThresholdAmount(), rule.getDiscountAmount(), rule.getUseStartsAt(), rule.getUseEndsAt());
        }
        return new CreateOrderResult(saveOrder(userId, request.requestId(), "DIRECT", List.of(item), address, now, coupon), true);
    }

    @Transactional
    public CreateOrderResult checkout(long userId, CheckoutRequest request) {
        // 重复编号属于HTTP业务输入错误，不合并成隐含的另一种请求。
        if (new HashSet<>(request.productIds()).size() != request.productIds().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品编号不能重复");
        }
        List<Long> productIds = request.productIds().stream().sorted().toList();
        appUserMapper.lockById(userId);
        TradeOrder existing = orderMapper.findByUserAndRequest(userId, request.requestId());
        if (existing != null) {
            loadDetails(List.of(existing));
            if (!"CART".equals(existing.getOrderType())
                    || !existing.getAddress().sourceAddressId().equals(request.addressId())
                    || !existing.getItems().stream().map(OrderItem::productId).toList().equals(productIds)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请求标识已用于另一笔购买");
            }
            // 成功后购物车可能已被删除或重新加入，重试只返回首次成交结果。
            return new CreateOrderResult(existing, false);
        }
        UserAddress address = addressMapper.findOwnedAddress(request.addressId(), userId);
        if (address == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地址不存在");
        }
        var selected = cartMapper.findSelectedForUpdate(userId, productIds);
        if (selected.size() != productIds.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "所选商品已不在购物车中");
        }
        List<OrderItem> items = new ArrayList<>();
        // Mapper按商品编号升序返回，所有结算以相同顺序锁定商品。
        for (var selection : selected) {
            items.add(deductItem(selection.productId(), selection.quantity(), null));
        }
        TradeOrder order = saveOrder(userId, request.requestId(), "CART", items, address,
                LocalDateTime.now().withNano(0), null);
        cartMapper.deleteSelected(userId, productIds);
        return new CreateOrderResult(order, true);
    }

    private OrderItem deductItem(long productId, int quantity, Long activityId) {
        // 当前读并持有商品锁，名称/单价快照与本次扣库存使用同一商品版本。
        Product product = productMapper.findByIdForUpdate(productId);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        if (productMapper.deductStock(productId, quantity) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已下架或库存不足");
        }
        return new OrderItem(null, productId, product.getName(), product.getPrice(), quantity,
                product.getPrice().multiply(BigDecimal.valueOf(quantity)), activityId);
    }

    private TradeOrder saveOrder(long userId, String requestId, String orderType, List<OrderItem> items,
                                 UserAddress address, LocalDateTime now, OrderCoupon coupon) {
        TradeOrder order = new TradeOrder();
        order.setUserId(userId);
        order.setOrderType(orderType);
        BigDecimal total = items.stream().map(OrderItem::lineAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 多商品合计可能超出主表DECIMAL(20,2)，外部购买组合在入库前明确拒绝。
        if (total.compareTo(new BigDecimal("999999999999999999.99")) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单金额超过支持范围");
        }
        order.setOriginalAmount(total);
        order.setDiscountAmount(coupon == null ? BigDecimal.ZERO : coupon.discountAmount());
        order.setTotalAmount(total.subtract(order.getDiscountAmount()));
        order.setStatus("PENDING_PAYMENT");
        order.setRequestId(requestId);
        order.setCreatedAt(now);
        order.setExpiresAt(now.plusMinutes(15));
        orderMapper.insertOrder(order);
        if (coupon != null) {
            couponMapper.reserve(coupon.userCouponId(), order.getId());
            couponMapper.insertSnapshot(order.getId(), coupon);
        }
        itemMapper.insertItems(order.getId(), items);
        if (address != null) {
            orderAddressMapper.insertAddress(new OrderAddress(order.getId(), address.getId(), address.getRecipientName(),
                    address.getPhone(), address.getProvince(), address.getCity(), address.getDistrict(), address.getDetailAddress()));
        }
        // 一张订单只登记一个超时事件，与明细和地址同事务提交。
        OrderTimeoutOutbox event = new OrderTimeoutOutbox();
        event.setOrderId(order.getId());
        event.setExpiresAt(order.getExpiresAt());
        event.setStatus("PENDING");
        event.setAttemptCount(0L);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        outboxMapper.insertEvent(event);
        productCache.evictAfterCommit();
        loadDetails(List.of(order));
        logAfterCommit("order_created", order.getId());
        return order;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<TradeOrder> listUserOrders(long userId) {
        List<TradeOrder> orders = orderMapper.findUserOrders(userId);
        loadDetails(orders);
        return orders;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TradeOrder getUserOrder(long userId, long id) {
        TradeOrder order = orderMapper.findOwnedOrder(id, userId);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        loadDetails(List.of(order));
        return order;
    }

    @Transactional
    public TradeOrder payOrder(long userId, long id) {
        TradeOrder order = lockUserOrder(userId, id);
        if ("PAID".equals(order.getStatus())) {
            return order;
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (orderMapper.markPaid(id, userId, now) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单已过支付截止时间或状态不允许支付");
        }
        couponMapper.useForOrder(id);
        logAfterCommit("order_paid", id);
        return getUserOrder(userId, id);
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
        restoreItems(order.getItems());
        couponMapper.releaseForOrder(id);
        logAfterCommit("order_canceled", id);
        return getUserOrder(userId, id);
    }

    @Transactional
    public boolean closeExpiredOrder(long id, LocalDateTime cutoff) {
        // 条件更新取得订单锁，只有成功转换状态的事务才能归还库存。
        if (orderMapper.markClosed(id, cutoff) == 0) {
            return false;
        }
        restoreItems(itemMapper.findByOrderIds(List.of(id)));
        couponMapper.releaseForOrder(id);
        logAfterCommit("order_closed", id);
        return true;
    }

    private void restoreItems(List<OrderItem> items) {
        // 活动锁始终先于商品写锁；当前活动入口只生成一条明细。
        for (OrderItem item : items) {
            if (item.activityId() != null) {
                activityMapper.restoreQuantity(item.activityId(), item.quantity());
            }
        }
        for (OrderItem item : items) {
            productMapper.restoreStock(item.productId(), item.quantity());
        }
        productCache.evictAfterCommit();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminOrderPageResponse listAdminOrders(int page, int size, String status, Long userId) {
        long offset = (long) (page - 1) * size;
        long total = orderMapper.countAdminOrders(status, userId);
        List<TradeOrder> orders = orderMapper.findAdminOrders(status, userId, offset, size);
        loadDetails(orders);
        return new AdminOrderPageResponse(page, size, total, orders.stream().map(AdminOrderResponse::from).toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TradeOrder getAdminOrder(long id) {
        TradeOrder order = orderMapper.findById(id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        loadDetails(List.of(order));
        return order;
    }

    @Transactional
    public TradeOrder shipOrder(long id, ShipOrderRequest request) {
        TradeOrder order = orderMapper.findByIdForUpdate(id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        loadDetails(List.of(order));
        String carrier = request.carrier().strip();
        String trackingNo = request.trackingNo().strip();
        // 校验归一化后的外部输入，拒绝被strip清空的Unicode空白文本。
        if (carrier.isEmpty() || trackingNo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "承运方和运单号不能为空白");
        }
        if ("SHIPPED".equals(order.getStatus()) || "COMPLETED".equals(order.getStatus())) {
            OrderShipment shipment = order.getShipment();
            if (!shipment.carrier().equals(carrier) || !shipment.trackingNo().equals(trackingNo)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "订单已使用其他发货信息");
            }
            return order;
        }
        if (!"PAID".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅已支付订单可以发货");
        }
        if (order.getAddress() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单缺少收货地址快照，不能发货");
        }
        orderMapper.markShipped(id);
        // 主状态与发货记录同事务；任何插入失败都会恢复PAID状态。
        shipmentMapper.insertShipment(new OrderShipment(id, carrier, trackingNo,
                LocalDateTime.now().withNano(0), null));
        logAfterCommit("order_shipped", id);
        return getAdminOrder(id);
    }

    @Transactional
    public TradeOrder receiveOrder(long userId, long id) {
        TradeOrder order = lockUserOrder(userId, id);
        if ("COMPLETED".equals(order.getStatus())) {
            return order;
        }
        if (!"SHIPPED".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅已发货订单可以确认收货");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        orderMapper.markCompleted(id, userId);
        // 已取得本人订单行锁，主状态和收货时间一起提交；重试不会覆盖原时间。
        shipmentMapper.markReceived(id, now);
        logAfterCommit("order_completed", id);
        return getUserOrder(userId, id);
    }

    private void logAfterCommit(String event, long orderId) {
        // 只在真实状态转换的事务提交后记录，不记录重试或回滚为成功。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            // 先于缓存失效等提交后操作，避免它们失败时丢掉已经成交的记录。
            @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
            @Override public void afterCommit() {
                log.atInfo().addKeyValue("event", event).addKeyValue("orderId", orderId).log("订单状态已提交");
            }
        });
    }

    private TradeOrder lockUserOrder(long userId, long id) {
        TradeOrder order = orderMapper.findOwnedOrderForUpdate(id, userId);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        loadDetails(List.of(order));
        return order;
    }

    private void loadDetails(List<TradeOrder> orders) {
        if (orders.isEmpty()) {
            return;
        }
        List<Long> ids = orders.stream().map(TradeOrder::getId).toList();
        var items = itemMapper.findByOrderIds(ids).stream().collect(Collectors.groupingBy(OrderItem::orderId));
        var addresses = orderAddressMapper.findByOrderIds(ids).stream()
                .collect(Collectors.toMap(OrderAddress::orderId, Function.identity()));
        var shipments = shipmentMapper.findByOrderIds(ids).stream()
                .collect(Collectors.toMap(OrderShipment::orderId, Function.identity()));
        var coupons = couponMapper.findByOrderIds(ids).stream()
                .collect(Collectors.toMap(OrderCoupon::orderId, Function.identity()));
        for (TradeOrder order : orders) {
            order.setItems(items.get(order.getId()));
            order.setAddress(addresses.get(order.getId()));
            order.setShipment(shipments.get(order.getId()));
            order.setCoupon(coupons.get(order.getId()));
        }
    }
}
