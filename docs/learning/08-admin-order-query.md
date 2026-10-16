# 第八阶段：管理员订单分页查询与代码解析

## 目标与本轮范围

用户查看自己的订单；管理员需要查看不同用户、不同状态的订单，并且每次只取一页。本轮补齐学习大纲中的管理端订单查询，再进入限量活动。

接口是 GET /api/admin/orders。它只查询，不修改订单、库存和支付状态。沿用 SecurityConfig 中 /api/admin/** 的 ADMIN 权限规则，不增加新的认证逻辑或依赖。

| 参数 | 默认值 | 规则与含义 |
|---|---|---|
| page | 1 | 正整数，Java int，页码从1开始 |
| size | 20 | 1到100，每页最多返回多少条 |
| status | 不筛选 | 可选，精确匹配 PENDING_PAYMENT、PAID、CANCELED、CLOSED；空字符串或其他值400 |
| userId | 不筛选 | 可选正 Long，购买用户编号，不要求这个编号的用户一定存在 |

未传或空的 page、size 使用 @RequestParam 的默认值；未传或空的 userId 绑定为 null，表示不筛选用户。GET 参数在 URL 中，不使用 JSON 请求正文。

示例：

```text
GET /api/admin/orders?page=2&size=1&status=PENDING_PAYMENT&userId=7301
```

表示查询7301号用户的待支付订单，每页1条，取第2页。示例编号来自隔离测试夹具，开发库并未新增这些订单。

## 新增和修改的文件

| 类型 | 文件 | 作用 |
|---|---|---|
| 新增 | order/AdminOrderController.java | 接收并校验 URL 查询参数 |
| 新增 | order/AdminOrderResponse.java | 管理员看到的一条订单，包含购买用户编号 |
| 新增 | order/AdminOrderPageResponse.java | 整页响应，包含页码、每页大小、总数、当前页列表 |
| 修改 | order/OrderService.java | 增加 listAdminOrders，计算偏移、计数、查页、转换响应 |
| 修改 | order/OrderMapper.java | 增加 countAdminOrders、findAdminOrders |
| 修改 | mapper/OrderMapper.xml | 共用筛选片段、总数查询与分页查询 |
| 新增 | src/test/java/com/elaina/limitedtrade/order/AdminOrderApiTest.java | 接口、参数、权限和真实数据库查询验证 |
| 新增 | src/test/resources/sql/admin-order-test-data.sql | 两个用户、一个商品和五笔不同状态订单的查询夹具 |

业务 Java 文件在 src/main/java/com/elaina/limitedtrade/ 下；XML 在 src/main/resources/ 下。

SecurityConfig、本人订单 Controller、原 OrderResponse 和数据库表结构没有变化。这次不需要006之类的数据库迁移脚本，因为只是增加查询，没有变更表结构。Mapper XML 内的查询 SQL 与 sql/ 下的建表、升级脚本承担不同任务。

## 整条调用链

```text
浏览器发送 GET 和 URL 查询参数
  → Spring Security 确认管理员权限
  → AdminOrderController 绑定并校验 page、size、status、userId
  → OrderService.listAdminOrders
      → countAdminOrders 查询全部匹配条数
      → findAdminOrders 查询这一页的 TradeOrder 列表
      → 转成 AdminOrderResponse 列表
      → 组装 AdminOrderPageResponse
  → Controller 返回对象
  → Spring MVC 序列化为 JSON
```

## Controller 的完整代码

```java
package com.elaina.limitedtrade.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public AdminOrderPageResponse listOrders(
            @RequestParam(defaultValue = "1") @Positive int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size,
            @RequestParam(required = false)
            @Pattern(regexp = "PENDING_PAYMENT|PAID|CANCELED|CLOSED") String status,
            @RequestParam(required = false) @Positive Long userId) {
        return orderService.listAdminOrders(page, size, status, userId);
    }
}
```

逐段理解：

1. 类上的 @RequestMapping 确定路径，方法上的 @GetMapping 确定 HTTP 方法为 GET。
2. @RequestParam 读取 URL 的 ? 后参数，与之前接收 JSON 正文的 @RequestBody 不同。这里只接收四个简单参数，没有额外创建 Request 类。
3. defaultValue 指定默认值；@Positive 要求大于0；@Max(100) 限制每页条数，避免单个请求无限取数据。
4. required=false 表示参数可省略。Long 是包装类型，可以为 null；primitive long 不能表达“未传”。
5. @Pattern 中的竖线表示“或者”，只接受列出的四个完整状态值。null 表示不筛选，空字符串并不符合这个状态规则。
6. 校验发生在 HTTP 输入边界；Service 不重复写相同的校验。
7. return 调用实例 orderService 的方法，直接返回一整页响应对象。

这里允许管理员提供 userId，是因为管理员有跨用户查询权限。普通用户不能靠传 userId 获得该权限；Security 在进入 Controller 前就返回403。用户端原有查询仍从登录身份取 userId。

## Service：分页参数如何被使用

OrderService 新增：

```java
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public AdminOrderPageResponse listAdminOrders(int page, int size, String status, Long userId) {
    // 先转换为 long 再乘，避免外部输入的大页码造成 int 溢出。
    long offset = (long) (page - 1) * size;
    long total = orderMapper.countAdminOrders(status, userId);
    List<AdminOrderResponse> items = orderMapper.findAdminOrders(status, userId, offset, size)
            .stream().map(AdminOrderResponse::from).toList();
    return new AdminOrderPageResponse(page, size, total, items);
}
```

### offset 为什么这样算

offset 是跳过多少条，不是页码。

| page | size | offset | 含义 |
|---|---|---|---|
| 1 | 2 | 0 | 不跳过，取前2条 |
| 2 | 2 | 2 | 跳过前2条，再取2条 |
| 3 | 2 | 4 | 跳过前4条，再取2条 |

公式是 (page - 1) * size。先转换为 long，再乘，保证很大的合法 int 页码也不会在 int 乘法中溢出。不能先用 int 乘完，再赋值给 long，因为那时可能已经溢出。

### total 与 items 为什么分别查询

比如共有5条匹配订单，page=2、size=2：

- total=5，表示一共5条。
- items 有2条，是第2页的记录。
- 第3页只有1条，但 total 仍是5。
- 第4页为空，但 total 仍是5。

items.size() 只知道这一页有多少条，无法代替总条数。

### stream().map(...).toList() 做了什么

findAdminOrders 返回 List<TradeOrder>，这是数据库映射出来的对象列表。

```java
.map(AdminOrderResponse::from)
```

表示对每个 TradeOrder 调用静态转换方法，等价于：

```java
.map(order -> AdminOrderResponse.from(order))
```

toList() 把每条转换结果收集成 List<AdminOrderResponse>。最终 new AdminOrderPageResponse(...) 把列表与分页信息装到一个对象中。

本阶段沿用简单 Service，直接组装这个查询响应，不为只用一次的传递过程再引入额外结果对象或接口层级。

### 只读事务有什么作用

readOnly=true 表明本方法用于读取，是给框架和驱动的事务提示，并不是管理员授权机制，也不能理解为任何情况下都能阻止 SQL 写入。

Isolation.REPEATABLE_READ 指定可重复读隔离级别。两条普通 SELECT 在同一个 MySQL InnoDB 事务内使用一致性快照，避免本次请求里 count 和 items 读取不同时间的数据。

这里没有 SELECT FOR UPDATE，不用订单写锁维持查询结果。不同 HTTP 请求是不同事务，因此用户翻到下一页时数据仍可能变化。

## Mapper 接口与动态 SQL

接口新增两个方法：

```java
long countAdminOrders(@Param("status") String status, @Param("userId") Long userId);

List<TradeOrder> findAdminOrders(@Param("status") String status, @Param("userId") Long userId,
                                @Param("offset") long offset, @Param("limit") int limit);
```

status、userId 用来筛选，offset、limit 用来分页。@Param 指定 XML 使用的参数名。

XML 先定义两次查询共用的片段：

```xml
<sql id="adminOrderFilters">
    <where>
        <if test="status != null">
            AND status = #{status}
        </if>
        <if test="userId != null">
            AND user_id = #{userId}
        </if>
    </where>
</sql>
```

- if 决定是否生成对应条件：未传 status，就不限制状态。
- where 只有在存在条件时才生成 WHERE，并去掉开头多余的 AND。
- 两个条件都提供时，用 AND 连接，要求同时满足。
- #{...} 绑定参数值，不把外部值直接当成 SQL 文本拼接。
- sql 标签定义可复用片段，不是独立可执行的 Mapper 方法，因此不需要在 Java 接口中声明 adminOrderFilters 方法。

总数查询：

```xml
<select id="countAdminOrders" resultType="long">
    SELECT COUNT(*) FROM trade_order
    <include refid="adminOrderFilters"/>
</select>
```

分页查询：

```xml
<select id="findAdminOrders" resultType="com.elaina.limitedtrade.order.TradeOrder">
    SELECT id, user_id, product_id, product_name, unit_price, quantity,
           total_amount, status, request_id, created_at, expires_at, paid_at, ended_at
    FROM trade_order
    <include refid="adminOrderFilters"/>
    ORDER BY id DESC
    LIMIT #{limit} OFFSET #{offset}
</select>
```

include 将同一筛选片段用于两次查询。如果一处漏了用户或状态条件，就可能出现 total 和 items 对不上的问题；共用片段减少这类错误。

ORDER BY id DESC 以编号从大到小排列，有确定的顺序。LIMIT 控制返回多少条，OFFSET 控制跳过多少条。分页放在数据库中完成，不是把全表读进 Java 再截取。

## 两个 Response 为什么分开

AdminOrderResponse 的完整代码：

```java
package com.elaina.limitedtrade.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AdminOrderResponse(Long id, Long userId, Long productId, String productName,
                                 BigDecimal unitPrice, Integer quantity, BigDecimal totalAmount,
                                 String status, String requestId, LocalDateTime createdAt,
                                 LocalDateTime expiresAt, LocalDateTime paidAt, LocalDateTime endedAt) {

    public static AdminOrderResponse from(TradeOrder order) {
        return new AdminOrderResponse(order.getId(), order.getUserId(), order.getProductId(),
                order.getProductName(), order.getUnitPrice(), order.getQuantity(), order.getTotalAmount(),
                order.getStatus(), order.getRequestId(), order.getCreatedAt(), order.getExpiresAt(),
                order.getPaidAt(), order.getEndedAt());
    }
}
```

它是“一条订单”，比用户端的 OrderResponse 多一个购买用户编号 userId，便于管理员区分归属。这里明确列出响应字段，不直接把 TradeOrder 作为管理员接口契约，也没有读取用户密码或密码摘要。

AdminOrderPageResponse 的完整代码：

```java
package com.elaina.limitedtrade.order;

import java.util.List;

public record AdminOrderPageResponse(int page, int size, long total, List<AdminOrderResponse> items) {
}
```

它是“一整页”。items 是一个列表，每个元素都是 AdminOrderResponse。record 自动提供构造器及 page()、size()、total()、items() 等访问方法。

以测试夹具查询全部订单、第2页、每页2条，以下是响应字段节选：

```json
{
  "page": 2,
  "size": 2,
  "total": 5,
  "items": [
    {"id": 9303, "userId": 7301, "status": "CANCELED"},
    {"id": 9302, "userId": 7302, "status": "PAID"}
  ]
}
```

真实响应的每个 items 元素还包含商品快照、金额、数量、请求标识和各时间字段，定义见上面的 record。

## 验证与边界

先执行管理员列表测试，缺少 Controller 时预期200、实际404。实现后离线 Maven package：全量117个测试通过，0失败、0错误，其中本阶段新增29个用例。

覆盖默认分页、编号倒序、第2页、末页、超末页、超大合法页码、四种状态、按用户筛选、组合条件、无匹配数据、非法数字与状态，以及匿名401、普通用户403。既有本人订单归属与交易测试也通过。

本次测试使用 MockMvc、模拟登录角色和真实 MySQL。登录过程本身由既有认证测试覆盖；本阶段没有实际 HTTP 查询验证，也没有执行开发库写入。测试夹具在事务中回滚。

当前为 OFFSET 分页，没有新增索引或性能优化故事；大偏移可能扫描更多数据，暂未做大数据量压测。统计和分页同一请求使用同一快照，但不保证跨请求翻页的结果始终不变。

日志保存在 tools/admin-orders-red.log、tools/admin-orders-green.log，Git 忽略。未提交或推送代码。

## 阅读后的复述

1. 为什么 page=3、size=10 时，offset 是20而不是30？
2. 共有25条匹配订单，page=3、size=10 时，total 和 items 的条数分别是多少？
3. 为什么总数查询和分页查询都要使用同一套 status、userId 筛选条件？
