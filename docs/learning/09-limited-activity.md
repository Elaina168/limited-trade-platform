# 第九阶段：最小限时限量活动

按完整生命周期阅读，请先看[活动全流程：从创建活动到订单结束](09-activity-full-flow.md)；本文保留设计、分段解析及原始代码快照。

## 已确认的设计

一个活动对应一个商品。活动配置名称、开始时间 startsAt、结束时间 endsAt、总名额 totalQuantity，保存剩余名额 remainingQuantity。名额是活动销售上限，不是预留商品库存；创建活动不扣商品库存。活动不另设优惠价，订单继续保存下单时读取的商品价格和名称。

开始时间 <= 下单时间 < 结束时间才能创建活动订单。已创建订单继续保留15分钟支付期限。取消或关闭成功时同时归还商品库存和活动名额，即使活动已经结束也归还记录；时间条件继续阻止新下单。

POST /api/admin/activities：管理员创建活动。GET /api/activities/{id}：公开查询单个活动；不做列表、修改、删除、优惠叠加或每人限购。

现有 CreateOrderRequest 新增可选 activityId；不传时沿用普通下单。活动必须对应请求中的 productId。TradeOrder 和两种订单响应均增加 nullable activityId，历史订单保持 null。同用户同 requestId 的重试先查原订单，商品、数量、活动均相同才返回原结果，即使活动已结束也不重新校验或扣减。

新增 activity 表和 trade_order.activity_id 外键，使用006增量脚本。活动总量为正，剩余量在0和总量之间，开始早于结束；数据库 CHECK 作为表数据约束。API 名称非空且最多100个Java字符单元，名额为正INT，两个时间必填；时间存到秒，创建时去掉纳秒后验证先后关系。

交易锁顺序：创建订单锁用户，活动订单再锁活动，之后更新商品库存；在取得库存写锁后取当前时间并条件扣活动名额。失败抛异常使商品扣减一起回滚。取消/关闭先成功转换订单状态，再归还活动名额，最后归还商品库存，保持活动→商品的锁顺序。普通订单没有活动操作。

成功标准：活动创建与查询、权限及参数边界；活动不预扣库存；时段与商品关联校验；商品库存和名额同时扣减/回滚；幂等重试；取消关闭只归还一次；并发不超名额、不超商品库存；支付仍可在活动结束后、订单截止前完成。保留原有全部测试，无新增下载，不提交或推送。

## 实施与验证记录

实施步骤见 [阶段计划](../plans/2026-10-16-limited-activity.md)。本阶段已实现并通过验证，原始代码快照在本文最后，真实源码仍位于src下。

## 这次新增和修改了什么

| 类型 | 文件 | 作用 |
|---|---|---|
| 新增 | sql/006_activity.sql | 建活动表，给订单增加可空活动外键，保留旧订单 |
| 新增 | activity/Activity.java | 活动JavaBean，支持MyBatis映射和自增主键回填 |
| 新增 | activity/CreateActivityRequest.java | 接收并校验管理员创建活动的JSON |
| 新增 | activity/ActivityService.java | 校验时间先后、商品存在性，设置初始名额 |
| 新增 | activity/AdminActivityController.java | POST /api/admin/activities，创建成功201 |
| 新增 | activity/ActivityController.java | GET /api/activities/{id}，读取单个活动 |
| 新增 | activity/ActivityMapper.java、mapper/ActivityMapper.xml | 创建、普通读取、锁定读取、扣减与归还名额 |
| 修改 | auth/SecurityConfig.java | 公开放行GET活动详情；管理员创建仍要求ADMIN和CSRF |
| 修改 | order/CreateOrderRequest.java | 新增可选Long activityId，非空时必须为正数 |
| 修改 | order/TradeOrder.java、OrderResponse.java、AdminOrderResponse.java | 保存并返回活动编号，普通订单为null |
| 修改 | order/OrderService.java | 下单加入活动校验和扣减；幂等比较活动；取消/关闭归还活动名额 |
| 修改 | mapper/OrderMapper.xml | INSERT和所有订单SELECT加入activity_id |
| 新增 | ActivityApiTest、ActivityOrderApiTest、ActivityConcurrencyTest、activity-test-data.sql | 配置、交易、时间边界、并发、真实回滚验证 |

业务Java文件位于src/main/java/com/elaina/limitedtrade下，Mapper XML位于src/main/resources下，测试Java文件位于src/test/java下。

没有新建活动订单Controller：活动订单和普通订单共用原来的POST /api/orders，避免重复实现已有交易流程。

## 先理解两份数量

例子：商品库存10，活动总量5。创建活动后，商品仍有10件，活动名额5。用户活动下单买2件后，商品库存8，活动名额3。

活动名额不能当成独立的一仓商品。如果普通购买耗尽商品，活动即使还有名额，也不能下单。多个活动可以关联同一商品，分别受各自名额上限限制，同时竞争同一份商品库存。

取消或关闭买2件的订单，恢复库存至10、活动名额至5。支付成功则保持8、3，不能归还。

## 创建活动：输入进入哪里

请求示例只是字段演示，不代表开发库已存在这些活动：

```json
{
  "productId": 1,
  "name": "键盘限量活动",
  "totalQuantity": 5,
  "startsAt": "2026-10-17T10:00:00",
  "endsAt": "2026-10-17T11:00:00"
}
```

调用链：

```text
管理员JSON请求
→ Security检查ADMIN和CSRF
→ AdminActivityController.createActivity(@Valid @RequestBody request)
→ ActivityService.createActivity(request)
→ ActivityMapper.insertActivity(activity)
→ ActivityMapper.xml执行INSERT
→ MyBatis回填activity.id
→ Controller以201返回Activity，Spring序列化JSON
```

CreateActivityRequest是record，只承载输入；Activity是可变JavaBean，既用于映射数据库，也便于MyBatis回填id。这里沿用商品接口直接返回JavaBean的方式，活动字段都是当前需要公开的字段。

@NotNull拒绝缺少商品、名额、起止时间；@Positive拒绝非正编号和数量；@NotBlank/@Size拒绝空白名称和超过100个Java字符单元的名称。现有Jackson配置会拒绝把1.5截成整数名额。

ActivityService先对两个时间withNano(0)，以数据库的秒精度存储，再通过endsAt.isAfter(startsAt)验证先后。这里不要求startsAt一定在未来，因此也可以创建已开始的活动；这个最小接口不区分草稿和发布。

随后productMapper.findById确认商品存在，再创建Activity：productId/name来自request；totalQuantity来自request；remainingQuantity也用request.totalQuantity初始化。客户端不能自行指定较大或较小的初始剩余名额。

insertActivity写数据库，useGeneratedKeys/keyProperty="id"把自增主键设置回对象。创建过程中没有调用deductStock，所以不会预扣商品。

## 活动详情：普通查询与带锁查询不同

GET /api/activities/{id}走ActivityController → ActivityService.getActivity → ActivityMapper.findById，返回活动配置和当前剩余名额。活动不存在404，非正编号400。

下单使用findByIdForUpdate，多了FOR UPDATE。它在订单事务内锁住该活动行，保证同一活动的名额变化按锁顺序进行；这是正确性优先的初版，不宣称高吞吐。详情查询只是展示，不能保证用户下一刻下单时名额仍未变化，最终以交易SQL为准。

## 活动订单：复用原请求增加一个可选值

```json
{"productId":1,"quantity":2,"requestId":"activity-buy-001","activityId":10}
```

activityId是可选Long。不传或传null，走原来的普通购买；非空时必须大于0。它和productId都由客户端提供，所以Service要校验活动与商品是否对应。

原有Controller不变，仍将登录用户编号与CreateOrderRequest交给OrderService.createOrder。新增活动Mapper通过构造器注入OrderService，和此前注入商品Mapper的方式一样。

### 第一步：先判断幂等，再检查活动

先锁用户行，再查userId/requestId。如果已存在，比较商品、数量和activityId：

```java
!Objects.equals(existing.getActivityId(), request.activityId())
```

Objects.equals(a,b)允许null参与比较：两个null相同；null与编号不同；两个编号比较值。不能对可能为null的活动编号直接调用.equals。

完全相同的请求返回原订单，created=false，不继续扣任何数量。这就是为什么活动结束、活动名额用完之后，同一请求的正常重试仍成功。

普通订单不能用原requestId改成活动订单，也不能从活动A切到活动B；这些都返回409。

### 第二步：锁活动，检查商品关联

activityId非空时调用findByIdForUpdate，查不到404。再比较activity.productId和request.productId，不同返回409。锁从这里持有到整个下单事务结束。

这不是额外预扣名额，只是先锁定活动。此时还没有减少remaining_quantity。

### 第三步：扣商品库存，等待结束后取时间

沿用productMapper.deductStock，要求商品ON_SALE且stock >= quantity。更新失败409。

这条UPDATE可能等待别的事务释放商品行锁。它成功返回后，代码才取LocalDateTime.now().withNano(0)。如果等锁期间活动结束，后面的名额扣减就会拒绝，并回滚刚才的库存扣减。

锁顺序固定为活动→商品；取消和关闭归还也按这个顺序，减少相反锁顺序导致的竞争问题。这里不声称数据库任何情况下都不可能死锁。

### 第四步：条件扣活动名额

```sql
UPDATE activity SET remaining_quantity = remaining_quantity - #{quantity}
WHERE id = #{id} AND remaining_quantity >= #{quantity}
  AND starts_at <= #{now} AND ends_at > #{now}
```

成功影响1行；没开始、已结束或名额不足影响0行。返回0时Service抛出409，@Transactional把此前商品扣减回滚，不生成订单。

时间条件左边含等号，右边不含：开始瞬间可以下单，结束瞬间不能下单。判定基于锁等待完成后取得的时间，不承诺数据库提交瞬间一定早于活动结束。

普通订单跳过这一步。活动订单随后setActivityId(request.activityId())，再按原规则设置商品快照、金额、待支付状态及15分钟截止时间，最后插入订单。

## 支付、取消和关闭有什么变化

支付业务没有新增活动时间检查。活动结束只阻止新下单；已创建订单继续按自身expiresAt判断。支付也不会再扣库存或名额。

取消与关闭在原来成功转换订单状态之后，新增：

```java
if (order.getActivityId() != null) {
    activityMapper.restoreQuantity(order.getActivityId(), order.getQuantity());
}
productMapper.restoreStock(order.getProductId(), order.getQuantity());
```

null分支表示普通订单，而不是为不可能出现的内部错误增加兜底。

restoreQuantity执行remaining_quantity = remaining_quantity + quantity，不限制活动时间。因此即使已结束，也把台账恢复正确。是否能再次购买仍由时间和商品库存条件决定。

这两次归还与订单状态更新处于同一事务。即使名额已经加回，后面的商品SQL失败，也会把名额和订单状态一起回滚。重复取消、重复关闭、两者竞争时，仍由订单状态转换控制“只有成功者归还”，不单独给库存或名额加新的幂等表。

## 为什么要修改原订单查询和响应

trade_order新增nullable activity_id，旧订单保持NULL。TradeOrder增加Long activityId及getter/setter；两种订单Response增加activityId，在from中取值。

所有返回TradeOrder的SELECT都增加activity_id，INSERT也写入它。如果只增加Java字段而忘了SELECT，读出的activityId就会是null，取消时会误当普通订单，无法归还活动名额。测试覆盖本人详情、本人列表、管理员分页和重复请求的映射。

订单仍保留成交金额与商品快照。活动表不提供编辑或删除接口，本阶段也没有额外保存活动名称、优惠价快照。

## 验证结果与边界

红测试：创建接口缺失时预期201实际404；活动订单尚未接入时JSON缺少activityId。最终离线package成功，166个测试通过，0失败、0错误。其中新增49个：

| 用例组 | 数量 | 主要验证 |
|---|---:|---|
| ActivityApiTest | 21 | 创建/查询、权限、CSRF、参数、时序、创建不扣商品库存 |
| ActivityOrderApiTest | 20 | 双扣减、名额或库存不足、时间边界、幂等、双归还、活动结束后支付、响应映射 |
| ActivityConcurrencyTest | 8 | 名额/商品竞争、并发重试与取消、取消关闭竞争、SQL溢出回滚、锁等待跨截止 |

20个不同用户竞争5名额，只生成5单，商品20→15、名额5→0；商品只有3件时，只生成3单，商品3→0、名额5→2。同用户同键并发20次，只生成1单、扣1件商品和1个名额。

锁等待用例用真实数据库连接持有商品行锁，通过第三连接的NOWAIT确认下单已持有活动锁，再等待活动结束才释放商品锁，最终下单409，库存和名额均保持原值。

活动订单API和并发测试不套外层测试事务，先提交夹具，让服务事务真正提交/回滚后检查数据，再按自有编号清理。配置API测试仍用测试事务回滚。故障用例故意设置INT最大库存，让真实库存归还SQL溢出，确认状态、endedAt和已归还名额均回滚。

测试是MockMvc/Service + 真实MySQL，不是实际HTTP、生产调度或吞吐测试。开发库原有商品、订单及账号公开字段与006前基线一致；开发库活动/订单0行，测试库四张表均0行。没有新增依赖，没有运行Redis/MQ，没有提交或推送。

证据保存在tools/activity-red.log、activity-config-green.log、activity-order-red.log、activity-green.log、activity-baseline.tsv，均被Git忽略。独立只读复核未发现证据充分的实现缺陷。

## 复述问题

1. 创建活动为什么不先把商品库存减去活动总名额？
2. 商品库存已经扣成功，但活动名额不足，最终商品库存为什么不减少？
3. 活动结束之后，为什么允许已创建订单支付，也允许取消归还活动名额？

## 本阶段核心原始代码快照

下面内容来自本阶段完成后的真实源码；完整getter/setter、测试及其他既有方法见对应文件。

### sql/006_activity.sql

```sql
-- 在005之后，对开发库和测试库各执行一次；历史订单的activity_id保持NULL。
CREATE TABLE activity (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '活动编号',
    product_id BIGINT NOT NULL COMMENT '活动商品编号',
    name VARCHAR(100) NOT NULL COMMENT '活动名称',
    total_quantity INT NOT NULL COMMENT '活动销售数量上限',
    remaining_quantity INT NOT NULL COMMENT '剩余活动名额，不代表预留库存',
    starts_at DATETIME NOT NULL COMMENT '允许下单的开始时间，含边界',
    ends_at DATETIME NOT NULL COMMENT '允许下单的结束时间，不含边界',
    PRIMARY KEY (id),
    KEY idx_activity_product (product_id),
    CONSTRAINT fk_activity_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT chk_activity_quantity CHECK (total_quantity > 0 AND remaining_quantity BETWEEN 0 AND total_quantity),
    CONSTRAINT chk_activity_time CHECK (starts_at < ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='最小限时限量活动';

ALTER TABLE trade_order
    ADD COLUMN activity_id BIGINT NULL COMMENT '活动编号；普通及历史订单为空',
    ADD KEY idx_trade_order_activity (activity_id),
    ADD CONSTRAINT fk_trade_order_activity FOREIGN KEY (activity_id) REFERENCES activity (id);
```

### src/main/java/com/elaina/limitedtrade/activity/CreateActivityRequest.java

```java
package com.elaina.limitedtrade.activity;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateActivityRequest(
        @NotNull @Positive Long productId,
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive Integer totalQuantity,
        @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt) {
}
```

### src/main/java/com/elaina/limitedtrade/activity/AdminActivityController.java

```java
package com.elaina.limitedtrade.activity;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/activities")
public class AdminActivityController {

    private final ActivityService activityService;

    public AdminActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @PostMapping
    public ResponseEntity<Activity> createActivity(@Valid @RequestBody CreateActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(activityService.createActivity(request));
    }
}
```

### src/main/java/com/elaina/limitedtrade/activity/ActivityController.java

```java
package com.elaina.limitedtrade.activity;

import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/{id}")
    public Activity getActivity(@PathVariable @Positive long id) {
        return activityService.getActivity(id);
    }
}
```

### src/main/java/com/elaina/limitedtrade/activity/ActivityService.java

```java
package com.elaina.limitedtrade.activity;

import java.time.LocalDateTime;

import com.elaina.limitedtrade.product.ProductMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final ProductMapper productMapper;

    public ActivityService(ActivityMapper activityMapper, ProductMapper productMapper) {
        this.activityMapper = activityMapper;
        this.productMapper = productMapper;
    }

    @Transactional
    public Activity createActivity(CreateActivityRequest request) {
        LocalDateTime startsAt = request.startsAt().withNano(0);
        LocalDateTime endsAt = request.endsAt().withNano(0);
        if (!endsAt.isAfter(startsAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "活动结束时间必须晚于开始时间");
        }
        if (productMapper.findById(request.productId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        Activity activity = new Activity();
        activity.setProductId(request.productId());
        activity.setName(request.name());
        activity.setTotalQuantity(request.totalQuantity());
        activity.setRemainingQuantity(request.totalQuantity());
        activity.setStartsAt(startsAt);
        activity.setEndsAt(endsAt);
        activityMapper.insertActivity(activity);
        return activity;
    }

    public Activity getActivity(long id) {
        Activity activity = activityMapper.findById(id);
        if (activity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在");
        }
        return activity;
    }
}
```

### src/main/java/com/elaina/limitedtrade/activity/ActivityMapper.java

```java
package com.elaina.limitedtrade.activity;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ActivityMapper {

    int insertActivity(Activity activity);

    Activity findById(long id);

    Activity findByIdForUpdate(long id);

    int deductQuantity(@Param("id") long id, @Param("quantity") int quantity, @Param("now") LocalDateTime now);

    int restoreQuantity(@Param("id") long id, @Param("quantity") int quantity);
}
```

### src/main/resources/mapper/ActivityMapper.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.elaina.limitedtrade.activity.ActivityMapper">

    <insert id="insertActivity" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO activity (product_id, name, total_quantity, remaining_quantity, starts_at, ends_at)
        VALUES (#{productId}, #{name}, #{totalQuantity}, #{remainingQuantity}, #{startsAt}, #{endsAt})
    </insert>

    <select id="findById" resultType="com.elaina.limitedtrade.activity.Activity">
        SELECT id, product_id, name, total_quantity, remaining_quantity, starts_at, ends_at
        FROM activity WHERE id = #{id}
    </select>

    <select id="findByIdForUpdate" resultType="com.elaina.limitedtrade.activity.Activity">
        SELECT id, product_id, name, total_quantity, remaining_quantity, starts_at, ends_at
        FROM activity WHERE id = #{id}
        FOR UPDATE
    </select>

    <update id="deductQuantity">
        UPDATE activity SET remaining_quantity = remaining_quantity - #{quantity}
        WHERE id = #{id} AND remaining_quantity &gt;= #{quantity}
          AND starts_at &lt;= #{now} AND ends_at &gt; #{now}
    </update>

    <update id="restoreQuantity">
        UPDATE activity SET remaining_quantity = remaining_quantity + #{quantity}
        WHERE id = #{id}
    </update>
</mapper>
```

### src/main/java/com/elaina/limitedtrade/order/CreateOrderRequest.java

```java
package com.elaina.limitedtrade.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
        @NotNull(message = "商品编号不能为空") @Positive(message = "商品编号必须大于0")
        Long productId,
        @NotNull(message = "购买数量不能为空") @Positive(message = "购买数量必须大于0")
        Integer quantity,
        @NotBlank(message = "请求标识不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "请求标识须为1至64位字母、数字、下划线或连字符")
        String requestId,
        @Positive(message = "活动编号必须大于0") Long activityId) {
}
```

### OrderService.createOrder

```java
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
        return new CreateOrderResult(order, true);
    }
```

### OrderService.cancelOrder / closeExpiredOrder

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
        if (order.getActivityId() != null) {
            activityMapper.restoreQuantity(order.getActivityId(), order.getQuantity());
        }
        productMapper.restoreStock(order.getProductId(), order.getQuantity());
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
        return true;
    }
```
