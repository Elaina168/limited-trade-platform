# 第十阶段：Redis 商品列表缓存与完整调用链

## 目标和成功标准

给现有 GET /api/products 加查询缓存，学习 Redis 连接、JSON 序列化、命中与回源、过期、提交后失效。保持商品接口路径和响应不变；下单仍以 MySQL 商品信息、条件扣库存和事务为准。

成功标准：首次查询写入有有效期的缓存，重复查询命中，过期后读取当前数据库值；名称、价格、上下架和交易库存变化后失效，回滚不删；普通/活动交易、幂等重试和支付行为符合原规则；既有测试全部通过。

本阶段使用缓存旁路模式（Cache Aside）：业务代码决定何时读缓存、何时查库、何时写缓存和失效。不是给 MySQL 装插件，也不是把订单数据搬到 Redis。

## 环境与已授权的依赖

本机盘点发现 WSL2 的 Ubuntu-22.04（Ubuntu 22.04.5 LTS）已安装 Redis 6.0.16，启动 WSL 后 Redis 监听回环6379，redis-cli PING 返回 PONG，Windows 的127.0.0.1:6379端口可达。没有新装或升级服务端。

用户确认设计和下载后，在 pom.xml 加入：

```xml
<!-- 使用 Redis 缓存商品查询结果，版本由当前 Spring Boot 管理 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

Spring Boot 3.5.16 管理 Spring Data Redis 3.5.13、Lettuce 6.6.0.RELEASE。Spring Data Redis 提供 RedisTemplate 等操作接口；Lettuce 负责底层客户端连接与命令通信。缺失依赖及传递依赖下载到 tools/maven-repository，后续已成功离线打包。没有新增 Redis Repository、Spring Cache 注解或新的缓存框架。

## 文件变化

| 文件 | 本阶段职责 |
|---|---|
| pom.xml | 引入 Redis Starter，版本沿用 Boot 管理 |
| src/main/resources/application.yml | Redis 地址、超时、缓存开关和键 |
| product/ProductCacheConfiguration.java | 配置字符串键和商品列表 JSON 序列化 |
| product/ProductCache.java | 读缓存、写入30秒缓存、注册提交后删除 |
| product/ProductService.java | 商品列表命中/回源；维护商品后注册失效 |
| order/OrderService.java | 实际扣库存、还库存后注册失效 |
| src/test/resources/application-test.yml | 既有回滚测试默认关闭缓存 |
| product/ProductCacheIntegrationTest.java | 单独开启缓存，使用真实 MySQL 和 Redis 验证 |

Java业务文件位于 src/main/java/com/elaina/limitedtrade/，测试Java文件位于 src/test/java/com/elaina/limitedtrade/。Controller、ProductMapper、Mapper XML、数据库表和迁移脚本均未修改。

## 先区分内存中的对象与 Redis 中的数据

Java方法返回 List<Product>。Redis 中只保存字节，不认识 Java 的 List 或 BigDecimal。序列化负责把对象变成字节，反序列化负责按已知结构还原对象。

本阶段缓存整个在售列表，键为：

```text
limited-trade:product:on-sale:v1
```

这是本阶段新建的应用键，所有用户看到相同的公开商品列表，不缓存登录身份或本人订单。v1区分当前数据格式，将来更换格式时可以换键。值是JSON数组，包含id、name、price、stock、status；顺序继续由已有 SQL 的 ORDER BY id ASC 决定。

ProductCacheConfiguration 的完整代码：

```java
package com.elaina.limitedtrade.product;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration
public class ProductCacheConfiguration {

    @Bean
    public RedisTemplate<String, List<Product>> productRedisTemplate(
            RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, List<Product>> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        // 明确还原为商品列表，保持价格为 BigDecimal，不在 JSON 中启用任意类型信息。
        var listType = objectMapper.getTypeFactory().constructCollectionType(List.class, Product.class);
        template.setValueSerializer(new Jackson2JsonRedisSerializer<>(objectMapper, listType));
        return template;
    }
}
```

Spring启动时调用@Bean方法，把返回的template注册为Bean，完成初始化。RedisConnectionFactory由Boot按配置创建；ObjectMapper沿用应用已有的JSON配置。RedisTemplate负责Redis操作，序列化器负责数据格式。

constructCollectionType(List.class, Product.class)明确声明“商品列表”，避免读回无类型的Map；Product.price为BigDecimal，反序列化沿用该类型。这里没有开启JSON任意类型信息。键明确使用字符串，便于redis-cli读取。

## 缓存类的完整代码

```java
package com.elaina.limitedtrade.product;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ProductCache {

    private final RedisTemplate<String, List<Product>> redis;
    private final boolean enabled;
    private final String key;

    public ProductCache(RedisTemplate<String, List<Product>> redis,
                        @Value("${app.product-cache.enabled}") boolean enabled,
                        @Value("${app.product-cache.key}") String key) {
        this.redis = redis;
        this.enabled = enabled;
        this.key = key;
    }

    public List<Product> get() {
        return enabled ? redis.opsForValue().get(key) : null;
    }

    public void put(List<Product> products) {
        if (enabled) {
            // 值和有效期通过同一条 SET 命令写入，空列表也缓存。
            redis.opsForValue().set(key, products, Duration.ofSeconds(30));
        }
    }

    public void evictAfterCommit() {
        if (enabled) {
            // 调用方均处于数据库事务中；回滚不删缓存，提交后才执行删除。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    redis.delete(key);
                }
            });
        }
    }
}
```

- @Component把缓存类交给Spring管理，构造器注入前面的专用RedisTemplate。
- @Value从配置读取开关和键。关闭时不读写Redis，查询自然回源；它是明确的配置开关，不是遇到故障后静默降级。
- opsForValue表示Redis的字符串值操作；JSON数组在Redis里仍是一个字符串值，不是Redis List结构。
- GET不存在的键返回null，表示未命中。缓存值为[]时还原为空列表，属于命中，不能用isEmpty代替null判断。
- SET同时带30秒有效期，避免先SET、再EXPIRE之间失败而留下永久键。命中时不续期，回源写入时才设置新有效期。
- evictAfterCommit只登记回调，此刻不删除。当前五处调用都在@Transactional方法中，依赖既有事务约定，不增加无事务兜底。

## 第一次商品查询：未命中

ProductController.listProducts仍调用productService.listOnSaleProducts，方法现在是：

```java
public List<Product> listOnSaleProducts() {
    List<Product> cached = productCache.get();
    if (cached != null) {
        return cached;
    }
    List<Product> products = productMapper.findOnSaleProducts();
    productCache.put(products);
    return products;
}
```

完整调用顺序：

```text
GET /api/products
→ Security允许公开商品查询
→ ProductController.listProducts
→ ProductService.listOnSaleProducts
→ ProductCache.get → Redis GET → 键不存在，返回null
→ ProductMapper.findOnSaleProducts → 原Mapper XML → MySQL查询在售商品
→ ProductCache.put → 商品列表转JSON → Redis SET，同时设置30秒有效期
→ Service返回List<Product>
→ Controller返回对象 → Spring MVC序列化为HTTP JSON
```

写入缓存和HTTP响应序列化是两个不同步骤。前者为后续请求保存副本，后者将本次结果交给客户端。

## 第二次查询与过期

有效期内GET命中：JSON还原为List<Product>，Service在return cached处返回，不执行Mapper。缓存不是拿到商品编号后逐个查库，而是保存整个列表结果。

过期后GET未命中，再走MySQL查询、写缓存、返回的顺序。没有在后台每30秒主动查库刷新，也没有保证每个请求读到数据库最新值。

空列表同样保存为[]。如果把空列表当作未命中，数据库没有在售商品时每次请求都会回源。

## 商品改名、改价、上下架：为什么提交后才删

以updateProduct为例，当前代码是：

```java
@Transactional
public Product updateProduct(long id, UpdateProductRequest request) {
    if (productMapper.updateDetails(id, request) == 0) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
    }
    productCache.evictAfterCommit();
    return productMapper.findById(id);
}
```

```text
管理员请求 → 原Controller校验 → ProductService事务
→ UPDATE商品名称/价格
→ evictAfterCommit登记回调
→ 查询修改后的商品，准备返回对象
→ Service方法结束，事务代理提交MySQL
→ afterCommit执行Redis DEL
→ Controller得到返回值，形成响应
```

若事务失败回滚，afterCommit不执行。不能把Java方法写到return前的删除理解为“提交后”：真正提交由事务代理在方法正常返回后处理。

如果在数据库提交前直接删缓存，另一个请求可能查到旧数据库值并写回；提交后删除可避免这个特定窗口，但不能消除所有并发回填问题。

updateProductStatus也在更新成功后调用同一方法。下架会让列表减少一项，上架会让列表增加一项，所以都删整个在售列表键。createProduct默认创建OFF_SALE商品，没有改变在售列表，不新增失效调用；其后上架时再失效。

## 普通/活动下单、取消、关闭和支付

OrderService构造器新增ProductCache依赖。只有实际变化库存的三个成功分支注册失效：

```java
// createOrder：原库存扣减、活动名额扣减和订单字段设置之后。
orderMapper.insertOrder(order);
productCache.evictAfterCommit();
return new CreateOrderResult(order, true);
```

```java
// cancelOrder：原条件状态转换成功，活动订单先归还活动名额。
productMapper.restoreStock(order.getProductId(), order.getQuantity());
productCache.evictAfterCommit();
return orderMapper.findOwnedOrder(id, userId);
```

```java
// closeExpiredOrder：原条件关闭成功，活动订单先归还活动名额。
productMapper.restoreStock(order.getProductId(), order.getQuantity());
productCache.evictAfterCommit();
return true;
```

这些是对应方法末尾的真实代码节选，不替代原状态条件、锁和活动处理。

| 业务分支 | 数据库行为 | 缓存行为 |
|---|---|---|
| 首次普通/活动下单成功 | 扣商品库存；活动另扣名额，保存订单 | 提交后删 |
| 相同requestId重试 | 提前返回原订单，不重复扣库存 | 不删 |
| 首次取消成功 | 转CANCELED，归还数量 | 提交后删 |
| 重复取消 | 提前返回原结果 | 不删 |
| 首次超时关闭成功 | 转CLOSED，归还数量 | 提交后删 |
| 重复关闭或陈旧候选 | 条件UPDATE返回0 | 不删 |
| 支付 | 只改变订单状态，不改变商品库存 | 不删 |
| 名额不足、库存不足、归还SQL失败 | 原事务回滚 | 不执行提交后删除 |

交易调用链仍是Controller/调度器 → OrderService → 原Mapper/SQL → MySQL。下单商品名、价格来自productMapper.findById，库存以deductStock的ON_SALE和stock >= quantity条件判断；没有调用ProductService的缓存列表方法。

因此即使缓存还显示库存10件，而数据库已只有2件，买3件依然失败；缓存价格也不作为成交价格。这是展示副本与交易依据的区别。

## 一致性和故障的实际边界

并发回填仍可能出现：查询A未命中并读到旧商品 → 写事务B提交并删除缓存 → 查询A把之前读到的旧列表写入Redis。旧副本可能在A写入后的30秒有效期内继续展示；不能严格说“从B提交开始最多30秒”，因为A的查询和写回可能有延迟。

本阶段接受这一展示边界，不引入分布式锁、版本校验、延迟双删或MQ；没有承诺强一致。频繁交易会频繁使整个列表失效，这一版用于理解正确的读写关系，不宣称高命中率或高吞吐。

Redis连接、超时和序列化错误直接向外抛出，没有catch后假装成功或自动返回数据库默认值。读缓存或填缓存失败时商品查询可能报错。

afterCommit删除若失败，数据库已经提交，错误仍会向调用方传播，不能再回滚已提交数据。订单创建可用原requestId确认已有结果；缓存旧副本依赖有效期消失。本阶段没有Redis故障注入用例，也没有补偿任务或告警闭环。

## 配置与本机启动

application.yml新增的配置：

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      connect-timeout: 2s
      timeout: 2s
      repositories:
        enabled: false

app:
  product-cache:
    enabled: true
    key: limited-trade:product:on-sale:v1
```

这只是新增部分，原数据库、Jackson、MyBatis和server配置仍保留。repositories.enabled=false关闭未使用的Redis Repository扫描。2秒超时是连接和命令超时配置，不是缓存有效期。

本机先在一个终端执行wsl -d Ubuntu-22.04，进入Linux后执行redis-cli PING，确认PONG，并保持会话打开。盘点时短命令退出后WSL会回到Stopped，导致Java连接被拒绝；本轮验证通过临时前台Linux进程保持WSL运行，结束后释放，没有修改自动启动配置。

在项目根目录的另一个PowerShell中执行：

```powershell
# 已有依赖缓存可离线构建；包含真实Redis缓存专项测试。
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress package
java -jar .\target\limited-trade-platform-0.0.1-SNAPSHOT.jar
```

启动后可在另外的终端GET /api/products，并用redis-cli GET limited-trade:product:on-sale:v1与TTL查看开发缓存。不要使用FLUSHDB或FLUSHALL，以免删除同一Redis实例中其他项目的数据。

新环境需要自行准备Redis，并按实际连接地址配置；本仓库不打包服务端，也不要求把本机已有的旧版本作为生产选型。

## 测试、证据与验收范围

新增ProductCacheIntegrationTest，共18个测试执行项，参数化覆盖普通与活动订单。使用真实Spring Bean、真实MySQL和真实Redis；HTTP层使用MockMvc，没有监听真实端口。

测试不套外层@Transactional：夹具提交后再调用Service，观察真正提交或回滚的结果。产品9701/9702、用户7701、活动9701均属本测试夹具；先检查编号无冲突，再创建，最后定向清理。缓存键采用limited-trade:test:product-cache:加本次随机UUID，仅删除自己的键，不触碰开发缓存。

既有test配置默认关闭缓存，避免将未提交的回滚夹具发布到Redis或跨测试复用；新缓存集成类显式开启。缓存命中用例先请求商品列表，再直接修改隔离数据库夹具，确认第二次请求仍返回旧缓存值；这是为证明命中而故意绕过业务失效，不代表推荐直接改业务库。

| 用例 | 验证内容 |
|---|---|
| 首次/再次查询 | JSON数组、中文、价格、库存、TTL与真实命中 |
| 过期回源 | 缩短自有键剩余时间并等待真实过期，重新读到数据库值 |
| 空列表 | []也命中，避免无商品时反复回源 |
| 改名改价 | 提交前缓存仍在，提交后失效并返回新值 |
| 商品回滚 | 数据库和旧缓存都保留原值 |
| 上架/下架 | 列表增减，旧列表失效 |
| 普通/活动下单 | 扣库存后失效，相同requestId重试不再失效 |
| 普通/活动取消 | 还库存后失效，重复取消不失效 |
| 普通/活动关闭 | 还库存后失效，重复关闭不失效 |
| 支付 | 不动库存，不删除缓存 |
| MySQL权威性 | 旧缓存价格不决定成交额，旧缓存库存不能绕过库存不足 |
| 交易回滚 | 名额不足回滚；真实INT溢出使取消、名额归还回滚，缓存保留 |

测试演进：缺少缓存时3项失败，补查询缓存但不加失效时10项失败；实现提交后失效后18项全通过。随后离线package全量184项通过，0失败、0错误、0跳过，保留既有166项测试。JDK21下仍有原有Mockito/Byte Buddy动态代理提示，本阶段未修改相关构建配置。

额外启动临时Java进程做真实HTTP检查被自动审批拒绝，原因仅为blocked by policy，未获得更具体说明；本阶段没有真实HTTP缓存结果，也没有开发库HTTP交易写入、性能压测或生产验证。

本机日志均在tools且被Git忽略：product-cache-red.log、product-cache-all-red.log、product-cache-invalidation-red.log、product-cache-green.log、product-cache-package.log。只读复核未发现需修复的问题。

本轮前后开发库公开字段快照product-cache-before.tsv与product-cache-after.tsv一致，仍为3条商品、2个学习账号、0订单、0活动；测试库四张表均0行。未修改本机凭证、未新增SQL迁移、未提交或推送。README中原有参考项目说明保留。

## 阅读后的集中复述

1. 第一次GET /api/products和第二次GET分别经过哪些对象？哪个分支不再调用Mapper？
2. 为什么空列表属于缓存命中？为什么SET时同时设置有效期？
3. evictAfterCommit调用时是否已经删缓存？数据库事务回滚时会发生什么？
4. 缓存显示10件、MySQL只有2件，用户买3件为什么仍然失败？
5. 为什么支付和相同requestId重试不删除缓存，而取消和关闭成功需要删除？
6. 提交后删缓存是否保证强一致？Redis删除失败时数据库是否一定回滚？

本阶段到此，学习者提问和复述后，等待“继续”再进入下一阶段。
