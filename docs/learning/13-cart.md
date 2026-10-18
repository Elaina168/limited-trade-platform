# 第十三阶段：用户购物车

## 目标与接口

登录用户可以把商品及意向数量保存到数据库，查看、设置最终数量和移除。购物车持久化到MySQL，退出登录再登录仍能读取。

| 接口 | 请求/响应 |
|---|---|
| GET /api/cart/items | 200，返回本人商品列表；空购物车返回[] |
| PUT /api/cart/items/{productId} | 正文quantity正整数；不存在则新增、存在则覆盖，成功204 |
| DELETE /api/cart/items/{productId} | 删除本人的该商品；已不存在也204 |

三个接口都需要登录，PUT/DELETE还需要CSRF。用户编号从登录身份获取，客户端不能指定要操作别人的购物车。ADMIN也只能操作自己的购物车。

购物车表示购买意向，不扣减或预留库存，也不创建订单/Outbox。允许保存超过当前库存的数量，包括在售但库存为0的商品。正式下单仍由原交易流程重新校验和扣减。本阶段不接购物车结算、活动购买或清空购物车。

## 1. 数据表：一用户、一商品、一条记录


`sql/009_cart_item.sql`：

```sql
-- 在008之后，对开发库和测试库各执行一次；只创建空购物车表。
CREATE TABLE cart_item (
    user_id BIGINT NOT NULL COMMENT '所属用户编号',
    product_id BIGINT NOT NULL COMMENT '商品编号',
    quantity INT NOT NULL COMMENT '意向购买数量，不占用库存',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次加入时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '最近变更时间',
    PRIMARY KEY (user_id, product_id),
    KEY idx_cart_item_product (product_id),
    CONSTRAINT fk_cart_item_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_cart_item_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT chk_cart_item_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户购物车购买意向';
```


组合主键PRIMARY KEY(user_id,product_id)同时承担唯一约束与按用户读取的索引。同一用户同一商品只能有一行，不需要额外自增记录编号；接口路径里的productId就是商品编号。

quantity是意向数量，CHECK(quantity>0)保证数据库不保存非正数。Java请求的Integer范围为1至2147483647，没有另设99件上限。

user_id和product_id分别引用app_user、product，确保关联存在，不级联删除。created_at记录首次插入，updated_at由MySQL在记录实际变化时维护。

只保存编号和数量，不保存商品名称、价格、库存副本。商品调价或下架后，购物车查询读取当前商品信息。本机009已对开发库和测试库各执行一次，不要重跑。

## 2. 请求与响应：只接收数量，不接收用户编号


`src/main/java/com/elaina/limitedtrade/cart/SetCartItemRequest.java`：

```java
package com.elaina.limitedtrade.cart;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SetCartItemRequest(
        @NotNull(message = "购买数量不能为空")
        @Positive(message = "购买数量必须大于0") Integer quantity) {
}
```


`src/main/java/com/elaina/limitedtrade/cart/CartItemResponse.java`：

```java
package com.elaina.limitedtrade.cart;

import java.math.BigDecimal;

public record CartItemResponse(Long productId, String name, BigDecimal price,
                               Integer stock, String status, Integer quantity) {
}
```


SetCartItemRequest只声明quantity，@NotNull拒绝缺失/null，@Positive拒绝0和负数。既有JSON配置禁止把小数自动截成整数，超出Integer范围也会在HTTP转换时拒绝。

CartItemResponse是一条展示结果，productId/quantity来自购物车，name/price/stock/status来自当前商品表。price沿用BigDecimal。响应不包含userId、密码摘要等字段；当前price是展示值，不是订单价格快照或价格承诺。

## 3. Controller：每次从登录身份取当前用户


`src/main/java/com/elaina/limitedtrade/cart/CartController.java`：

```java
package com.elaina.limitedtrade.cart;

import java.util.List;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cart/items")
public class CartController {
    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    public List<CartItemResponse> listItems(@AuthenticationPrincipal PlatformUserDetails user) {
        return service.listItems(user.getId());
    }

    @PutMapping("/{productId}")
    public ResponseEntity<Void> setQuantity(@AuthenticationPrincipal PlatformUserDetails user,
                                            @PathVariable @Positive long productId,
                                            @Valid @RequestBody SetCartItemRequest request) {
        service.setQuantity(user.getId(), productId, request.quantity());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> removeItem(@AuthenticationPrincipal PlatformUserDetails user,
                                          @PathVariable @Positive long productId) {
        service.removeItem(user.getId(), productId);
        return ResponseEntity.noContent().build();
    }
}
```


类路径为/api/cart/items。@AuthenticationPrincipal PlatformUserDetails user沿用OrderController的现有方式取得登录用户，user.getId()作为Service的userId。

GET不从请求中读取userId。PUT/DELETE路径只接productId，并由@Positive验证编号；PUT的@Valid @RequestBody校验数量。即使客户端额外发送正文或查询参数userId，也不影响实际传入Service的认证用户编号。

PUT和DELETE返回ResponseEntity<Void>，noContent().build()构造204且无正文。PUT不区分“新增/覆盖/值未变化”的状态码，避免依赖MySQL UPSERT不同情况下的更新行数。

SecurityConfig在既有订单规则后增加：

```java
.requestMatchers("/api/cart/items", "/api/cart/items/**").authenticated()
```

这里要求登录，没有把购物车加到permitAll。原CSRF仍启用，写入需要有效Token。匿名写请求可能先被CSRF过滤器拒绝；携带有效CSRF但未登录时认证返回401。购物车没有跨用户管理接口。

## 4. Service：商品检查与购物车事务


`src/main/java/com/elaina/limitedtrade/cart/CartService.java`：

```java
package com.elaina.limitedtrade.cart;

import java.util.List;
import com.elaina.limitedtrade.product.Product;
import com.elaina.limitedtrade.product.ProductMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CartService {
    private final CartItemMapper cartMapper;
    private final ProductMapper productMapper;

    public CartService(CartItemMapper cartMapper, ProductMapper productMapper) {
        this.cartMapper = cartMapper;
        this.productMapper = productMapper;
    }

    public List<CartItemResponse> listItems(long userId) {
        return cartMapper.findUserItems(userId);
    }

    @Transactional
    public void setQuantity(long userId, long productId, int quantity) {
        Product product = productMapper.findById(productId);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        if (!"ON_SALE".equals(product.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已下架");
        }
        // 保存意向数量，不预留库存；同键重复请求覆盖数量，不累加。
        cartMapper.setQuantity(userId, productId, quantity);
    }

    @Transactional
    public void removeItem(long userId, long productId) {
        cartMapper.deleteItem(userId, productId);
    }
}
```


listItems只执行一次Mapper连接查询，直接按认证用户编号取数据。

setQuantity的@Transactional将商品检查和购物车写入放在同一事务：先用原ProductMapper.findById从MySQL读取商品，查不到则404；读取到的状态不是ON_SALE则409；通过后设置购物车数量。

这个检查没有锁定商品在售状态，也没有占用库存。状态检查后商品仍可能变化，因此GET始终展示当前状态，正式交易仍需重新检查。库存是否足够不在这里阻止保存意向。

removeItem按用户和商品直接删除，不查询商品是否存在、是否在售，也不把“删除0行”当作错误。这样用户可以移除下架商品，多次删除同一商品结果一致。

这里没有ProductCache、RabbitTemplate、OrderMapper等依赖，不执行商品缓存失效、不创建订单或消息。旧直接下单接口也没有新增清空购物车行为。

## 5. Mapper：绝对数量与归属约束


`src/main/java/com/elaina/limitedtrade/cart/CartItemMapper.java`：

```java
package com.elaina.limitedtrade.cart;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CartItemMapper {
    List<CartItemResponse> findUserItems(long userId);

    int setQuantity(@Param("userId") long userId, @Param("productId") long productId,
                    @Param("quantity") int quantity);

    int deleteItem(@Param("userId") long userId, @Param("productId") long productId);
}
```


`src/main/resources/mapper/CartItemMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.elaina.limitedtrade.cart.CartItemMapper">
    <select id="findUserItems" resultType="com.elaina.limitedtrade.cart.CartItemResponse">
        SELECT c.product_id, p.name, p.price, p.stock, p.status, c.quantity
        FROM cart_item c JOIN product p ON p.id=c.product_id
        WHERE c.user_id=#{userId}
        ORDER BY c.product_id ASC
    </select>

    <insert id="setQuantity">
        INSERT INTO cart_item (user_id, product_id, quantity)
        VALUES (#{userId}, #{productId}, #{quantity})
        ON DUPLICATE KEY UPDATE quantity=#{quantity}
    </insert>

    <delete id="deleteItem">
        DELETE FROM cart_item WHERE user_id=#{userId} AND product_id=#{productId}
    </delete>
</mapper>
```


### 设置数量的UPSERT

```sql
INSERT INTO cart_item (user_id, product_id, quantity)
VALUES (用户编号, 商品编号, 意向数量)
ON DUPLICATE KEY UPDATE quantity=意向数量
```

上面是语义示意；实际XML使用#{...}绑定参数，不拼接输入。

首次设置时INSERT。若同一个(user_id,product_id)已经存在，组合主键触发重复键分支，执行UPDATE把quantity覆盖成请求值。

例如，同一个用户对同一个商品连续设置3、3、5，最终是5，始终只有一行。代码不是quantity=quantity+请求值，因此网络重复发送PUT3不会变成6。

不先SELECT购物车是否存在再决定INSERT/UPDATE，减少并发首次添加的竞态。主键和单条UPSERT让数据库原子处理同键设置；不同数量并发时最终取某个实际生效的设置值，不按网络发送顺序保证覆盖。数据库异常继续向外传播，没有添加无依据自动重试。

### 查询当前商品信息

```sql
FROM cart_item c JOIN product p ON p.id=c.product_id
WHERE c.user_id=#{userId}
ORDER BY c.product_id ASC
```

INNER JOIN连接当前商品字段。外键保证购物车引用的商品存在，现有系统也没有商品物理删除接口，所以不构造虚假商品或静默默认值。

SQL没有p.status='ON_SALE'条件：加入后下架的记录仍展示，用户能看到OFF_SALE并移除。现有记录下架后再PUT数量返回409，原数量不改变；重新上架后可再次设置。

where中的user_id是认证用户。两个用户可以分别保存相同product_id，互相看不到对方数量。ORDER BY明确给出列表顺序。

### 删除只限定本人

```sql
DELETE FROM cart_item
WHERE user_id=#{userId} AND product_id=#{productId}
```

两个条件缺一不可。只用productId删除会误删其他人的同商品记录；当前SQL始终带认证用户编号。DELETE不存在本人记录也正常204。

## 6. 完整调用链

```text
PUT设置数量
  → Spring Security检查登录及CSRF
  → CartController取认证用户编号，校验productId与quantity
  → CartService开启事务，查当前商品存在/在售状态
  → CartItemMapper单条UPSERT
  → 提交，返回204

GET查看
  → Spring Security检查登录
  → Controller取认证用户编号
  → Service/Mapper按本人JOIN商品表
  → 返回当前商品信息及保存的意向数量

DELETE移除
  → 检查登录、CSRF和路径编号
  → Mapper按本人+商品删除
  → 提交，返回204
```

## 7. 与旧缓存和交易的关系

购物车查询直接访问MySQL，不走“全部在售商品列表”的Redis缓存。一次普通单条连接查询获得商品和购物车展示结果，不单独逐个请求商品，也不维护购物车Redis副本。

购物车修改不会改变商品库存，因而不调用productCache.evictAfterCommit。原直接下单仍使用其库存、幂等和Outbox流程；成功下单不会自动移除购买意向。多商品购物车结算及成功后移除的事务语义会在后续阶段另行设计。

## 8. 验证与证据边界

API红测试24项中22项因购物车尚未实现/放行而失败，0错误；实现后24项通过。并发3项、提交型持久化与回滚/旧交易边界3项，专项共30项通过。

并发验证使用10线程20次操作：同值设置最终一行quantity=3；不同值为某个成功设置值，不累加；设置和删除竞争只影响本人，另一用户同商品数量保持7。全部Future结果读取，工作线程结束后清理自有数据。

提交型验证经MockMvc登录、设置购物车、退出，再使用新Session登录同用户，仍读取到数量；另一用户看到空列表。TransactionTemplate显式回滚验证新增不残留、修改恢复原值；旧单商品下单正确扣库存并写Outbox，购物车仍保留。

完整离线package：303项，0失败、0错误、0跳过，BUILD SUCCESS；包含原273项和新增30项。既有真实MySQL/Redis/RabbitMQ回归均包含在本轮。日志tools/cart-package.log。

独立只读复核未发现需要修复的重要问题。开发库原六表公开字段基线一致，新增购物车表0行；测试库七表均0；组合主键实际列为user_id/product_id。随机Redis测试键及MQ测试前缀队列/交换机无残留，JAR不含实际凭据、测试配置、工具或测试类。

本阶段未执行临时JAR真实HTTP检查；按已确认计划没有重试先前被自动审批拒绝的进程启动。接口与登录验证为MockMvc+真实数据库，不能标作真实HTTP或生产性能证明。

未新增依赖、提交、推送或合并；本阶段结束后按新增代码逐段讲解。证据为tools/cart-api-red.log、cart-api-green.log、cart-integration.log、cart-package.log、cart-before.tsv、cart-after.tsv及cart-progress.md，均不进入Git。
