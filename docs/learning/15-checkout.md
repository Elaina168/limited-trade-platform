# 第十五阶段：多商品结算、订单明细和地址快照

本稿按实际生产源码整理；不展示测试代码。学习顺序：请求与Controller → 结算Service与幂等 → 锁和快照/事务 → Mapper/XML → 原业务调整及订单归还。每次只讲一个完整主题，理解后再继续。

## 1. 本阶段目标与原流程

原购物车只保存意向，地址簿独立保存本人地址；旧下单入口一次购买一个商品。本阶段把勾选的普通商品结算成一张订单，保存商品成交快照和收货地址快照。活动继续使用原单商品入口。

购物车不占库存；扣库存发生在结算。支付操作只改变整单状态，不再次扣库存。取消/超时关闭归还全部明细的库存，且只归还一次，不把商品重新加入购物车。

## 2. 新增及修改文件

| 文件 | 作用 |
|---|---|
| `order/CheckoutRequest.java` | 结算HTTP输入：商品集合、本人地址编号、请求标识 |
| `cart/CartSelection.java` | 购物车当前读取到的商品编号及购买数量 |
| `order/OrderItem.java`、`OrderAddress.java` | 内部成交商品和地址快照 |
| `order/OrderItemResponse.java`、`OrderAddressResponse.java` | 对外快照，不暴露内部订单/地址来源编号 |
| `OrderItemMapper`、`OrderAddressMapper`及对应XML | 快照写入、按订单编号集合批量读取 |
| `OrderController.java`、`OrderService.java` | 新入口、事务与幂等、金额计算及状态生命周期 |
| `OrderMapper.xml`、`TradeOrder.java` | 主信息与明细分离，主表新增内部订单类型 |
| `OrderResponse.java`、`AdminOrderResponse.java` | 所有订单改用items；本人可查看address，管理列表不公开地址 |
| `CartService.java`、`CartItemMapper`及XML | 购物车写入共用用户锁，读取及移除选中商品 |
| `ProductMapper`及XML | 下单在持有商品锁时读取成交价格和名称 |
| `sql/011_order_checkout.sql` | 历史明细迁移、主表调整和新增地址快照表 |

以上Java路径相对于 `src/main/java/com/elaina/limitedtrade/`；XML在 `src/main/resources/mapper/`。

## 3. 请求对象与Controller

例如购物车已有商品11数量2、商品12数量3，用户勾选两项，选择本人地址7：

```json
{"productIds":[11,12],"addressId":7,"requestId":"checkout-20261019-001"}
```

编号仅为请求格式示例，不代表本机现有数据。请求中不传数量、价格、总金额或userId。明确的地址编号便于用户确认本次收货地点；默认地址仍由调用方选择，不由服务器隐式代选。

实际请求代码：

```java
package com.elaina.limitedtrade.order;

import java.util.List;
import jakarta.validation.constraints.*;

public record CheckoutRequest(
        @NotEmpty(message = "请选择购物车商品") @Size(max = 50, message = "一次最多结算50种商品")
        List<@NotNull @Positive Long> productIds,
        @NotNull @Positive Long addressId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestId) {
}
```

`List<@NotNull @Positive Long>`检查集合里的每个元素；`@NotEmpty`检查整个集合不能为null或空，`@Size`把一次结算限制为50种商品。重复编号在Service作为业务输入错误拒绝，不把重复项悄悄合并。

实际Controller新增方法：

```java
    @PostMapping("/checkout")
    public ResponseEntity<OrderResponse> checkout(@AuthenticationPrincipal PlatformUserDetails user,
                                                  @Valid @RequestBody CheckoutRequest request) {
        CreateOrderResult result = orderService.checkout(user.getId(), request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(OrderResponse.from(result.order()));
    }
```

身份从现有Session认证对象取；`@Valid`触发请求校验。Service返回 `CreateOrderResult`，首次创建用201，同内容重试用200。Controller不扣库存、不查地址、不算金额。Session和CSRF沿用现有安全配置。

## 4. 结算Service和幂等

```java
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
                LocalDateTime.now().withNano(0));
        cartMapper.deleteSelected(userId, productIds);
        return new CreateOrderResult(order, true);
    }
```

先排序，然后锁用户行。`requestId`命中时先读首次成交结果，比较入口类型、商品集合和首次地址编号，再直接返回；只有首次请求才读取当前地址/购物车。因此成功后地址被删除、购物车已移除或重加，都不会让合法重试失败，也不会误删新意向。

两入口共用 `(user_id,request_id)` 唯一约束；同标识跨入口复用409。DIRECT仍比较商品、数量、活动；CART请求没有数量，数量来自首次成功结算时的购物车。购物车数量变化后想再购买，必须使用新的requestId。

购物车的选择查询是当前读，返回顺序为productId升序；数量缺少任何一项都整单失败。对每个商品再取得商品行锁，防止等待锁时仍使用旧快照价格。其他用户仍可竞争库存，数据库条件扣减阻止超卖。

## 5. 扣库存、金额与快照保存

```java
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
                                 UserAddress address, LocalDateTime now) {
        TradeOrder order = new TradeOrder();
        order.setUserId(userId);
        order.setOrderType(orderType);
        BigDecimal total = items.stream().map(OrderItem::lineAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 多商品合计可能超出主表DECIMAL(20,2)，外部购买组合在入库前明确拒绝。
        if (total.compareTo(new BigDecimal("999999999999999999.99")) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单金额超过支持范围");
        }
        order.setTotalAmount(total);
        order.setStatus("PENDING_PAYMENT");
        order.setRequestId(requestId);
        order.setCreatedAt(now);
        order.setExpiresAt(now.plusMinutes(15));
        orderMapper.insertOrder(order);
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
        return order;
    }
```

`OrderItem`创建时尚无orderId，插入明细时统一使用主表回填的编号；随后批量读取真实保存结果作为响应。金额用BigDecimal计算，不使用double。每个明细小计为单价乘数量，总金额是小计之和；多商品合计超出DECIMAL(20,2)范围明确409，前面扣减的库存随事务回滚。

主表、明细、地址、Outbox与调用方随后执行的购物车删除都处于checkout的同一事务。方法名中有save并不会自动新开事务，private方法由外层公开方法的事务覆盖。任何SQL失败继续传播，事务回滚；缓存失效只在提交后执行。

原单商品创建也调用这些方法，但只有一条明细、没有地址快照；活动先锁活动行，再锁商品，等待结束后判定活动时间，保持既有规则。

## 6. Mapper/XML和迁移

`CartItemMapper.findSelectedForUpdate`限定本人userId及所选productIds，`ORDER BY product_id FOR UPDATE`返回当前购买数量。`deleteSelected`只删这些本人项目，不清空整个购物车。CartService的设置数量、删除也先锁同一用户行：结算先完成时，之后的新意向会等待提交后写入并保留；数量修改先完成时，结算使用其提交后的数量。

`OrderItemMapper.insertItems`通过foreach批量保存明细；单价/名称都是商品数据库快照。`OrderAddressMapper.insertAddress`复制六个地址内容字段，sourceAddressId仅供幂等比较，不建立指向user_address的外键，也不公开在响应中。

011先创建order_item/order_address，复制历史单商品明细，再移除trade_order旧商品列及外键/索引，新增DIRECT/CART类型；订单编号及原幂等约束保持。历史地址不补造。MySQL DDL不能整套事务回滚，必须停止应用、事先验证结构并备份，在隔离schema演练后再执行；本机两库已各执行一次，禁止重复执行。

## 7. 订单查询与响应

```java
    private void loadDetails(List<TradeOrder> orders) {
        if (orders.isEmpty()) {
            return;
        }
        List<Long> ids = orders.stream().map(TradeOrder::getId).toList();
        var items = itemMapper.findByOrderIds(ids).stream().collect(Collectors.groupingBy(OrderItem::orderId));
        var addresses = orderAddressMapper.findByOrderIds(ids).stream()
                .collect(Collectors.toMap(OrderAddress::orderId, Function.identity()));
        for (TradeOrder order : orders) {
            order.setItems(items.get(order.getId()));
            order.setAddress(addresses.get(order.getId()));
        }
    }
```

一次主表查询后，用订单编号集合分别查询明细与地址，再按orderId装配；不对每张订单逐个查询。空列表直接返回，避免SQL的IN空集合。本人列表/详情和管理员分页使用只读REPEATABLE_READ事务，主表及快照在一致性视图中读取。

所有本人订单响应保留id、totalAmount、status、requestId和四个时间字段，商品信息统一进入items；address可以为null。内部userId、orderType、orderId、sourceAddressId不公开。管理列表保留用户编号，但不返回收货地址。

## 8. 取消与超时归还

原来归还主表中的一个商品；现在成功转换状态后，调用如下实际方法：

```java
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
```

只有成功将待支付状态变为CANCELED或CLOSED的事务执行归还。重复消息、重复取消、扫描与消息竞争都不能重复归还。活动名额先于商品库存恢复；商品按Mapper返回的编号顺序处理，保持一致的锁顺序。任一明细恢复失败，前面恢复的库存和本次状态转换一起回滚。

## 9. 验收证据边界

2026-10-19完整离线package381项通过，0失败/错误/跳过。本阶段新增43项：结算API24项，真实事务/Redis/Session11项，并发7项，真实RabbitMQ多商品超时关闭1项。独立只读复核无额外重要发现。

开发公开字段规范化快照SHA256前后一致；测试十表为空、测试触发器已清理，随机Redis测试键和RabbitMQ测试拓扑无残留；JAR凭据/测试/工具边界检查通过。MySQL迁移先隔离演练，再两库011各一次。用户已有数据未被测试更改。

验证过程中两条回滚检查最初放在测试外层事务内，业务代理只标记rollback-only，物理回滚尚未发生；改由真实事务边界验证并通过，没有修改生产事务传播配置。target中有一个不属于本轮的历史测试XML，统计按本轮日志列出的33个测试类与各自XML交叉核对，为381项，不累计历史报告。接口证据来自MockMvc与真实MySQL，缓存与消息另有真实Redis/RabbitMQ集成。没有新增真实HTTP监听、公网访问或生产压力测试证据。源码讲解不展示测试代码。
