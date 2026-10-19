# 第十六阶段：订单履约新增源码

第十五阶段已理解完成。本阶段按实际新类、字段、方法、参数和SQL讲解，不重复全部旧业务，不展示测试代码。

## 1. 本阶段目标

原订单支付后停在PAID；这次新增整单发货登记及买家确认收货：PAID→SHIPPED→COMPLETED。一张订单只有一份发货记录；管理员先看订单详情中的地址快照，再登记承运方和运单号，买家查看并确认。此处登记不代表调用了真实物流服务或验证了实体签收。

只有PAID且已有地址快照可首次发货；无地址旧单/DIRECT返回409，不从当前地址簿猜地址。本阶段不改变旧创建请求，不新增退款、拆包、自动收货或外部服务。

## 2. 新增类

Java位于 `src/main/java/com/elaina/limitedtrade/order/`。

### ShipOrderRequest.java

接住管理员JSON。carrier是承运方文字，trackingNo是运单号。HTTP边界要求非空白、最长50/64字符，Service保存前strip；没有承运方枚举或虚构真实物流格式。

```java
package com.elaina.limitedtrade.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipOrderRequest(
        @NotBlank(message = "承运方不能为空") @Size(max = 50, message = "承运方最多50字符") String carrier,
        @NotBlank(message = "运单号不能为空") @Size(max = 64, message = "运单号最多64字符") String trackingNo) {
}
```

### OrderShipment.java

内部数据库记录，orderId关联订单，shippedAt是本系统登记发货时间，receivedAt是买家确认时间。record访问器为carrier()/trackingNo()等，没有setter；收货时间由SQL更新后重新查询对象。

```java
package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;

public record OrderShipment(Long orderId, String carrier, String trackingNo,
                            LocalDateTime shippedAt, LocalDateTime receivedAt) {
}
```

### OrderShipmentResponse.java

对外发货内容，from是自己定义的静态转换方法；移除内部orderId。尚未发货的订单shipment为空。

```java
package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;

public record OrderShipmentResponse(String carrier, String trackingNo,
                                    LocalDateTime shippedAt, LocalDateTime receivedAt) {
    public static OrderShipmentResponse from(OrderShipment shipment) {
        return new OrderShipmentResponse(shipment.carrier(), shipment.trackingNo(),
                shipment.shippedAt(), shipment.receivedAt());
    }
}
```

### OrderShipmentMapper.java

声明保存一条发货记录、更新收货时间、批量按订单编号读取。多个参数用@Param给XML绑定明确名字；返回int为受影响行数，不是订单编号。

```java
package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderShipmentMapper {
    int insertShipment(OrderShipment shipment);

    int markReceived(@Param("orderId") long orderId, @Param("now") LocalDateTime now);

    List<OrderShipment> findByOrderIds(@Param("orderIds") List<Long> orderIds);
}
```

## 3. 发货Mapper XML与迁移

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.elaina.limitedtrade.order.OrderShipmentMapper">
    <insert id="insertShipment">
        INSERT INTO order_shipment(order_id,carrier,tracking_no,shipped_at)
        VALUES (#{orderId},#{carrier},#{trackingNo},#{shippedAt})
    </insert>

    <update id="markReceived">
        UPDATE order_shipment SET received_at=#{now} WHERE order_id=#{orderId}
    </update>

    <select id="findByOrderIds" resultType="com.elaina.limitedtrade.order.OrderShipment">
        SELECT order_id,carrier,tracking_no,shipped_at,received_at FROM order_shipment
        WHERE order_id IN
        <foreach collection="orderIds" item="id" open="(" close=")" separator=",">#{id}</foreach>
        ORDER BY order_id
    </select>
</mapper>
```

insertShipment只插入首次发货内容，received_at默认空。markReceived只修改该订单的received_at；调用前Service已经锁本人订单并验证SHIPPED，重复COMPLETED提前返回，因此不会覆盖第一次时间。findByOrderIds的foreach生成IN参数列表，按订单编号排序批量装配。

012创建order_shipment，以order_id为主键保证整单只有一行，并用外键约束所属订单；物理删除订单才级联删除，取消只是改状态不会触发级联。CHECK只约束received_at为空或不早于shipped_at。主表状态列更新注释，不改变已有业务值；没有历史发货回填，两库已经各执行一次，禁止重复执行。

## 4. 管理员详情与角色边界

```java
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TradeOrder getAdminOrder(long id) {
        TradeOrder order = orderMapper.findById(id);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        loadDetails(List.of(order));
        return order;
    }
```

新增AdminOrderController的GET /{id}调用该方法。沿用SecurityConfig的/api/admin/**仅ADMIN规则；Service查询所有者订单并装配快照，响应复用OrderResponse，地址仅在详情展示。原管理列表仍不返回地址。

## 5. 发货Service

```java
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
        return getAdminOrder(id);
    }
```

findByIdForUpdate锁订单主行，发货、收货、支付、取消和关闭对同一订单都被该行锁协调；不存在404。loadDetails补齐地址及已有shipment。carrier/trackingNo用strip去首尾空白，使同内容重试的比较与保存值一致。当前@NotBlank与strip的Unicode空白语义不同，因此对strip结果再次检查isEmpty；全角空格等被清空时400，不把空字段保存为发货记录。这是外部输入归一化后的校验。

SHIPPED或COMPLETED表示首次发货已经提交，直接比较已保存的两个字符串；同值返回原订单，异值409，不覆盖记录。只有首次PAID才继续，缺快照409。markShipped是带PAID条件的UPDATE；在同一事务持有订单锁且已验证原状态，后续插入发货记录不会和另一个同单发货并行。INSERT失败异常传播，状态一起回滚。此方法不访问商品或活动库存，不登记缓存失效，不发MQ。

getAdminOrder在写事务内部调用时复用外层事务；该内部调用不会另行开启只读事务，避免把方法上的注解误解成每次内部调用都会生效。重新查询是为了将刚保存的状态和shipment装入响应。

## 6. 本人收货Service

```java
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
        return getUserOrder(userId, id);
    }
```

lockUserOrder查询同时带id/userId并锁订单，非本人和不存在404；ADMIN调用买家接口也不能替别人确认。COMPLETED提前返回保持首次receivedAt。SHIPPED之外409，禁止跳过发货。取得锁之后再取当前时间，markCompleted更新主状态，markReceived更新关联记录；两条SQL同事务。第二条失败时第一条COMPLETED也回滚。

## 7. OrderMapper新增SQL

```sql
SELECT id,user_id,order_type,total_amount,status,request_id,
       created_at,expires_at,paid_at,ended_at
FROM trade_order WHERE id=#{id} FOR UPDATE;

UPDATE trade_order SET status='SHIPPED'
WHERE id=#{id} AND status='PAID';

UPDATE trade_order SET status='COMPLETED'
WHERE id=#{id} AND user_id=#{userId} AND status='SHIPPED';
```

首次发货和收货必须从对应前态转换。旧markCanceled/markClosed仍只更新PENDING_PAYMENT，因此迟到超时消息无法关闭SHIPPED/COMPLETED，也无法重复归还库存。ended_at语义不改，收货时间保存在发货表。

## 8. Controller、装配与响应接入

AdminOrderController新增GET /{id}与POST /{id}/ship；前者只读，后者@Valid @RequestBody接收ShipOrderRequest。OrderController新增POST /{id}/receive，从@AuthenticationPrincipal取userId，无正文。路径均@Positive；写入继续要求CSRF。

TradeOrder新增OrderShipment shipment及getter/setter，是Java装配字段，不在主表增加shipment列。loadDetails在原明细/地址查询之外增加：

```java
var shipments = shipmentMapper.findByOrderIds(ids).stream()
        .collect(Collectors.toMap(OrderShipment::orderId, Function.identity()));
```

一张订单最多一份记录，所以使用toMap而非groupingBy。在原循环增加order.setShipment(shipments.get(order.getId()))；没记录时为null。整批订单多一次批量查询，不逐订单查询。

OrderResponse和AdminOrderResponse追加OrderShipmentResponse shipment；from中有快照则转换，无则返回null。管理员列表仍无address，详情通过有角色权限的新接口查看。管理状态筛选正则补SHIPPED|COMPLETED，分页计数/SQL过滤沿用原实现。

## 9. 验收与边界

2026-10-19完整离线package417项通过，0失败/错误/跳过；新增36项（API25、真实事务/Redis/Session4、并发5、RabbitMQ新状态2）。独立只读复核发现Unicode空白语义差异，两个用例先预期400实际200，补strip后非空检查后随全量通过。

开发原十表公开字段快照SHA256前后一致，新发货表0；测试十一表为空、无触发器，Redis随机测试键和MQ随机测试拓扑无残留；JAR无本机密码/配置、测试与工具，包含新Mapper。012两库各一次，未重跑既有迁移。

所有接口证据来自MockMvc及真实MySQL，缓存/消息使用真实Redis/RabbitMQ回归；没有新增真实HTTP、公网、实体发货或物流签收证明。没有新增依赖、提交、推送或工作树。仅结束本轮专用保活进程，保留共享服务。
