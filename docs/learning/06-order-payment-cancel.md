# 第六阶段：模拟支付与主动取消的代码解析

## 本轮新增与修改

- 新增 `sql/004_order_payment_cancel.sql`：增加 paid_at、ended_at，不重建订单表。
- 修改 order 包的 TradeOrder 和 OrderResponse：增加 paidAt、endedAt；响应转换 from 方法增加两个取值。
- 修改 OrderController：增加 payOrder、cancelOrder 两个 POST 方法。
- 修改 OrderService：增加 payOrder、cancelOrder，以及两个操作共用的私有 lockUserOrder 方法。
- 修改 OrderMapper 与 XML：增加带锁归属查询、支付状态更新、取消状态更新；原查询补齐两个新列。
- 修改 ProductMapper 与 XML：增加 restoreStock。
- 新增两个测试类与一份 SQL 夹具。

Java 文件位于 `src/main/java/com/elaina/limitedtrade/` 下，Mapper XML 位于 `src/main/resources/mapper/` 下。

## 为什么没有新的 Request

POST `/api/orders/{id}/pay` 和 POST `/api/orders/{id}/cancel` 的输入已经由路径订单编号与登录身份提供，不需要再增加只有重复信息的请求对象。客户端也不能为模拟支付指定金额。

Controller 以 payOrder 为例：

```java
@PostMapping("/{id}/pay")
public OrderResponse payOrder(@AuthenticationPrincipal PlatformUserDetails user,
                               @PathVariable @Positive long id) {
    return OrderResponse.from(orderService.payOrder(user.getId(), id));
}
```

user 是框架确认的身份，id 来自 URL。orderService 是注入的实例，调用实例方法；OrderResponse.from 是类的静态转换方法。成功直接返回对象，Spring MVC 默认使用200并序列化为 JSON。

## 两种操作共用的锁定查询

```java
private TradeOrder lockUserOrder(long userId, long id) {
    TradeOrder order = orderMapper.findOwnedOrderForUpdate(id, userId);
    if (order == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
    }
    return order;
}
```

对应 SQL 同时包含 `id`、`user_id` 条件与 `FOR UPDATE`。既筛选本人订单，又锁定该订单行。查不到统一404，不区分不存在与属于别人。

private 表示这个方法只在 OrderService 内部使用。它不单独开启事务，而是在两个带 @Transactional 的公共方法中执行，使用调用方已经开启的事务。锁持有到事务提交或回滚。

这与“普通 SELECT 后根据旧状态盲目 UPDATE”不同：当前读取时持有行锁，并且后续 UPDATE 仍明确要求原状态是待支付。

## payOrder 逐段解释

```java
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
    return orderMapper.findOwnedOrder(id, userId);
}
```

1. 进入事务，读取并锁定本人订单。
2. 如果已经 PAID，直接返回，不重新写支付时间。这是当前操作的幂等处理。
3. 取得秒精度当前时间。取时发生在获得锁之后，避免等待锁时仍使用过早的时间做截止判断。
4. markPaid 在 SQL 中要求待支付且 expires_at > now。影响0行返回409。
5. 更新成功后再次查询，以数据库新状态和支付时间构造响应。

SQL 核心：

```sql
UPDATE trade_order
SET status = 'PAID', paid_at = #{now}
WHERE id = #{id} AND user_id = #{userId}
  AND status = 'PENDING_PAYMENT' AND expires_at > #{now}
```

没有调用扣库存方法，因为库存已经在创建订单时扣过。模拟支付不接入银行或支付平台，也不产生实际资金流转。

## cancelOrder 逐段解释

```java
@Transactional
public TradeOrder cancelOrder(long userId, long id) {
    TradeOrder order = lockUserOrder(userId, id);
    if ("CANCELED".equals(order.getStatus())) {
        return order;
    }
    if (orderMapper.markCanceled(id, userId, LocalDateTime.now().withNano(0)) == 0) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "当前订单状态不允许取消");
    }
    productMapper.restoreStock(order.getProductId(), order.getQuantity());
    return orderMapper.findOwnedOrder(id, userId);
}
```

1. 读取并锁定本人订单。
2. 已取消直接返回，跳过后面的库存归还。
3. SQL 只允许待支付转为 CANCELED，并记录 ended_at。已支付等其他状态不能转换。
4. 只有转换成功，才把订单购买数量归还到原商品库存。
5. 返回数据库中更新后的订单；金额、成交名称和价格保留原值。

库存 SQL 只做加法：

```sql
UPDATE product SET stock = stock + #{quantity} WHERE id = #{id}
```

“只加一次”的保证来自调用链：订单行锁、重复取消提前返回、原状态条件更新，以及整个方法的事务边界。不能脱离这个流程随意调用库存归还方法。

取消状态修改和库存归还在同一个事务里。若库存 SQL 抛出异常，取消状态也回滚；当前没有做数据库故障注入，不把该故障情况称为已实测。

## 字段和响应怎样变化

| 状态 | paidAt | endedAt | 库存 |
|---|---|---|---|
| PENDING_PAYMENT | null | null | 已扣除本单购买数量 |
| PAID | 支付时间 | null | 保持不变 |
| CANCELED | null | 取消时间 | 归还本单购买数量 |

OrderMapper 查询新增 paid_at、ended_at；下划线转驼峰配置将它们映射到实体属性。OrderResponse.from 再把它们复制到响应对象，最后序列化为 JSON。只修改数据库或实体而遗漏查询、转换，会造成响应缺少值，因此本轮同步修改了这些位置。

## 如何验证代码

新增17个接口测试，事务回滚；新增3个无测试外层事务的并发用例，使用已提交夹具并定向清理。

- 支付后库存不再减少，取消后库存恢复，订单金额保持。
- 对已支付或已取消订单重试，原支付或结束时间不被覆盖。
- 过期支付、已取消后支付、已支付后取消返回409。
- 他人订单和不存在的订单返回404；未登录、无 CSRF 和非法编号也覆盖。
- 两个线程竞争支付与取消，一个200、一个409；最终 PAID 对应不归还库存，CANCELED 对应归还库存。
- 并发重复取消10次，只归还一次；并发重复支付10次，不追加扣库存。

全量77个测试通过。没有执行开发库 HTTP 支付或取消，原商品和订单行与本轮增量脚本前的基线一致；测试库三张表最终均0行。

## 当前边界与集中复述

过期订单支付返回409，但当前没有自动超时关闭任务；待支付订单仍可以主动取消。超时扫描在下一阶段实现。

请沿着 Controller → Service → Mapper XML → OrderResponse 阅读，重点解释：第二次取消为什么不会执行 restoreStock？如果支付和取消同时到达，哪几行代码阻止两者都成功？

理解验收后再等待“继续”。
