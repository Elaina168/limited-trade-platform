# 第七阶段：订单超时关闭与代码解析

## 目标与业务结果

下单时已经扣库存。订单到支付截止时间后，如果仍是 PENDING_PAYMENT，系统把它改为 CLOSED，归还购买数量，记录 endedAt。保留 totalAmount、unitPrice 等成交快照，paidAt 仍为空。

例如原库存5件，买2件：下单后金额40、可售库存3；超时关闭后金额仍40、可售库存5、状态CLOSED。关闭代表这笔交易没有完成，不能通过把金额改成0来表示。

支付要求 expires_at > 当前时间；关闭要求 expires_at <= 本轮截止时间。恰好相等时不能支付，可以关闭。到期与完成关闭之间可能有轮询延迟，但支付接口已经会拒绝过期支付。

## 新增和修改了哪些文件

| 类型 | 文件 | 作用 |
|---|---|---|
| 新增 | order/OrderTimeoutScheduler.java | 取得本轮时间，查最多100个候选编号，逐单调用业务方法 |
| 新增 | order/OrderSchedulingConfiguration.java | 开启定时调度，提供测试时关闭自动调度的开关 |
| 修改 | order/OrderService.java | 增加 closeExpiredOrder，组织单笔关闭与库存归还事务 |
| 修改 | order/OrderMapper.java | 声明候选查询、关闭更新、按编号查询三个方法 |
| 修改 | mapper/OrderMapper.xml | 实现对应 SQL |
| 新增 | sql/005_order_timeout.sql | 更新状态注释，增加扫描索引 |
| 修改 | src/test/resources/application-test.yml | 测试时关闭自动调度，手动触发以避免后台干扰 |
| 新增 | OrderTimeoutTest、OrderTimeoutConcurrencyTest | 验证批次、边界、竞争与事务回滚 |

Java 业务文件均在 src/main/java/com/elaina/limitedtrade/ 下，XML 在 src/main/resources/ 下，测试类在 src/test/java/com/elaina/limitedtrade/order/ 下。

本次没有增加 Request、Controller 或 Response。触发者是定时器，不是浏览器；既有本人订单查询仍通过 OrderResponse 返回状态和 endedAt。TradeOrder 和 OrderResponse 已在上阶段增加结束时间，status 目前是 String，因此不必增加字段。

## 先看这次的调用顺序

```text
Spring 定时器
  → OrderTimeoutScheduler.closeExpiredOrders()
  → OrderMapper.findExpiredOrderIds(cutoff, 100)
  → OrderMapper.xml 查询候选编号
  → 逐个调用 OrderService.closeExpiredOrder(id, cutoff)
      → 开启单笔事务
      → markClosed 条件更新订单
      → 成功时 findById 读取商品编号与购买数量
      → ProductMapper.restoreStock 归还库存
      → 提交事务；出错则回滚
```

用户稍后 GET /api/orders/{id}，仍走 Controller → Service → Mapper → OrderResponse → JSON，可看到 CLOSED。

## 调度配置：谁来自动调用

OrderSchedulingConfiguration 的完整代码：

```java
package com.elaina.limitedtrade.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.order-timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderSchedulingConfiguration {
}
```

- @Configuration：这是 Spring 配置类。proxyBeanMethods=false 表示这里不需要配置类方法间的代理调用。
- @EnableScheduling：启用对 @Scheduled 方法的自动调度。
- @ConditionalOnProperty：app.order-timeout.enabled 为 true 时配置生效；没有配置时，因为 matchIfMissing=true，也生效。
- 测试配置将 enabled 设为 false。扫描器和 Service 仍是 Bean，可以手动调用，只是不自动调度。

这个空类不是订单数据对象，也不是接口，只负责开启框架能力。

## 扫描器：如何找出候选订单

OrderTimeoutScheduler 的完整代码：

```java
package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderTimeoutScheduler {

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    public OrderTimeoutScheduler(OrderMapper orderMapper, OrderService orderService) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${app.order-timeout.scan-delay-ms:30000}")
    public void closeExpiredOrders() {
        LocalDateTime cutoff = LocalDateTime.now().withNano(0);
        // 每轮只取一批；下一轮继续处理剩余到期订单。
        for (Long id : orderMapper.findExpiredOrderIds(cutoff, 100)) {
            orderService.closeExpiredOrder(id, cutoff);
        }
    }
}
```

1. @Component 让 Spring 创建和管理这个对象。构造器注入 Mapper 与 Service；小写 orderService 是实例变量。
2. @Scheduled 标记要自动调用的方法。${配置名:默认值} 是配置占位符；30000是毫秒，即30秒。
3. fixedDelay 表示上一轮方法执行结束后，再等待指定时间，之后开始下一轮；不是每个整点秒数触发。
4. cutoff 是这轮扫描固定使用的时间，withNano(0) 与数据库 DATETIME 的秒精度一致。所有候选使用同一截止点。
5. findExpiredOrderIds 返回 List<Long>。这里只需要编号，暂时不加载整份订单。
6. for (Long id : 列表) 是增强 for 循环：每次把列表中的一个编号赋给 id，执行一次大括号中的代码。
7. closeExpiredOrder 返回 boolean。扫描器不需要统计结果，因此没有接收返回值；其他扫描器抢先处理时，返回 false 属于正常结果。

扫描器没有 @Transactional，不会把100笔订单放进同一个长事务。

## Mapper：Java 参数怎样进入 SQL

OrderMapper 新增：

```java
List<Long> findExpiredOrderIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
int markClosed(@Param("id") long id, @Param("cutoff") LocalDateTime cutoff);
TradeOrder findById(long id);
```

@Param("cutoff") 指定 SQL 绑定名，对应 #{cutoff}。方法名与 XML 标签 id 对应；返回类型分别是编号列表、影响行数和订单对象。

候选查询：

```sql
SELECT id FROM trade_order
WHERE status = 'PENDING_PAYMENT' AND expires_at <= #{cutoff}
ORDER BY expires_at, id
LIMIT #{limit}
```

先筛选到期未支付订单，按到期时间、编号从小到大，最多100条。XML 中 <= 写成 &lt;=，避免被当成 XML 标签。

增量脚本增加 (status, expires_at) 索引，对应状态等值条件与到期时间范围。InnoDB 二级索引还带有主键 id。索引帮助定位候选范围；这里没有进行大数据量性能测试，不能据此宣称高吞吐。

查到的名单可能过时：在查询之后、真正处理之前，另一事务可能已经支付、取消或关闭。因此候选查询不决定业务是否成功。

## Service：真正决定关闭和还库存

OrderService 新增方法：

```java
@Transactional
public boolean closeExpiredOrder(long id, LocalDateTime cutoff) {
    // 条件更新本身会取得行锁；只有成功改变状态的事务才归还库存。
    if (orderMapper.markClosed(id, cutoff) == 0) {
        return false;
    }
    TradeOrder order = orderMapper.findById(id);
    productMapper.restoreStock(order.getProductId(), order.getQuantity());
    return true;
}
```

对应更新 SQL：

```sql
UPDATE trade_order
SET status = 'CLOSED', ended_at = #{cutoff}
WHERE id = #{id} AND status = 'PENDING_PAYMENT' AND expires_at <= #{cutoff}
```

1. @Transactional 将本方法内的订单更新、查询和库存更新放在同一事务。
2. markClosed 执行 UPDATE，这一步才真正尝试写数据库。MySQL 会对更新行加锁，等待另一个事务结束后仍需满足条件才会更新。
3. 返回0，说明当前没有符合条件的订单，本次没有获得关闭资格；直接 false，不能归还库存。已关闭的订单再次处理，也走这里。
4. 返回1，说明本事务成功完成 PENDING_PAYMENT → CLOSED。行锁继续持有到事务结束。
5. findById 读取刚才成功更新的订单，得到 productId、quantity。这是内部业务查询，不提供给外部用户；现有用户查询依然带 userId。
6. restoreStock 执行已有的 stock = stock + quantity。归还数量来自订单快照。
7. return true 后由事务代理提交；执行中出现数据库异常，则异常向外传播，事务回滚，不能留下 CLOSED 但库存未归还的半成品。

ended_at 使用的是本轮 cutoff，不是数据库提交瞬间。订单金额、名称、价格快照均没有出现在 SET 中，所以不会被抹掉。

## 为什么扫描器和 Service 分开

Spring 的声明式事务通常通过代理生效。扫描器调用另一个 Bean 的公共 @Transactional 方法，会经过事务代理。

如果把扫描和关闭都写进同一个类，再用 this.closeExpiredOrder(...) 调用，通常会绕开代理，不能因为方法上有注解就认定开启了事务。这里通过已有 OrderService 承接事务，不额外引入事务工具类。

一笔关闭完成后立即提交。后续另一笔失败时，不会把之前已经提交的订单一起回滚。

## 与支付、取消竞争时的结果

| 情况 | 结果 |
|---|---|
| 截止前支付成功，之后扫描到旧候选编号 | 状态已是 PAID，关闭更新0行，不归还库存 |
| 已经到期，支付和关闭同时执行 | 支付409，关闭成功，库存归还一次 |
| 取消先成功 | CLOSED 更新0行，保留 CANCELED，不重复还库存 |
| 关闭先成功 | 后续取消409，保留 CLOSED |
| 多个扫描同时处理同一单 | 一个更新成功，其他更新0行，库存只归还一次 |

支付和关闭都对同一订单行进行加锁操作。支付使用此前的 SELECT FOR UPDATE；关闭由条件 UPDATE 取得锁，不必再重复做一次加锁查询。

## 验证与当前边界

离线执行 Maven package，全量88个测试通过，0失败、0错误；新增11个测试。真实 MySQL 用例验证：

- 到期扫描后状态CLOSED、金额40、库存从3回5，paid_at为空、ended_at有值。
- 截止前一秒不关闭，恰好截止时可关闭。
- 重复关闭不改原结束时间，不重复归还。
- 已支付、已取消的陈旧候选不会被关闭。
- 未到期订单保持待支付。
- 101个候选第一轮处理100个，第二轮处理剩余订单。
- 10次并发扫描只归还一次；过期支付与关闭竞争、取消与关闭竞争均保持合法状态和库存。
- 故意把测试库存设为 INT 最大值，让归还 SQL 溢出：确认状态及 ended_at 回滚；修复这份测试数据后下一轮重试成功。

测试关闭了后台自动调度，通过真实 Spring Bean 手动调用扫描入口；验证了扫描业务与事务，不是墙钟调度频率、实际 HTTP 或生产环境时效验证。开发库数据行与迁移前基线一致，测试库订单、商品、用户最终均0行。

本阶段只用现有 Spring 调度，无新增依赖，无 MQ。轮询和积压会带来延迟，不保证到期30秒内全部关闭。业务代码不捕获并吞掉异常；发生单笔异常时本轮停止后续处理，Spring 定时任务框架会记录错误，后续轮次仍可能再次触发。持续异常需要排查，不承诺自动解决。

本机日志在 tools/order-timeout-red.log、tools/order-timeout-green.log，开发库基线在 tools/order-timeout-baseline.tsv，均被 Git 忽略。未提交或推送本阶段代码。

## 阅读后的复述

1. 扫描查到了一个订单编号，为什么还必须用带状态条件的 UPDATE？
2. 两个扫描同时关同一单，为什么 stock = stock + quantity 没有执行两次？
3. 为什么 @Transactional 写在 OrderService 的单笔方法上，而不是扫描器的整个循环上？
