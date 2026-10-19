# 第十七阶段：Redis Lua下单入口限流

沿用完成代码与验收后详细讲新增源码；不展示测试代码。当前范围只有两个创建订单入口，不增加热点新缓存、数据库迁移或依赖。

## 1. 目标和位置

过去每个合法创建请求都会进入OrderService。这次在OrderController.createOrder和checkout中，取认证userId后先调用rateLimiter.check(user.getId())，放行才进入数据库事务。默认一个用户首个请求起10秒最多5次，两入口共享；换Session不改变身份键。认证/CSRF/JSON和@Valid前置失败不进入方法；Service业务校验、库存失败、幂等重试进入方法后均计尝试。

```text
请求 → Security/CSRF → JSON及@Valid → Controller
  → RedisOrderRateLimiter.check(userId)
      → Lua读取当前计数及TTL
      → 放行0：继续原OrderService
      → 超限正数：429及Retry-After，停止
      → 连接/超时：503，停止
```

此处限流是请求尝试额度，数据库回滚不撤销Redis已消费额度；不作为购买次数限制或幂等机制。超限后不能立即完成幂等重试，窗口恢复后原请求仍返回原订单。

## 2. 新增文件及Java代码

Java包com.elaina.limitedtrade.ratelimit。

### OrderRateLimitProperties.java

record承接app.order-rate-limit四个配置。enabled控制开关，maxRequests/windowSeconds由@Min要求正数，keyPrefix限制字符和长度。@Validated触发启动校验；ignoreUnknownFields=false使拼错字段启动报错，不悄悄忽略。YAML的max-requests绑定到maxRequests()等record访问器。

```java
package com.elaina.limitedtrade.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.order-rate-limit", ignoreUnknownFields = false)
public record OrderRateLimitProperties(
        boolean enabled,
        @Min(1) int maxRequests,
        @Min(1) int windowSeconds,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{1,100}") String keyPrefix) {
}
```

### OrderRateLimitConfiguration.java

@Configuration注册配置类；@EnableConfigurationProperties让Spring绑定并注册上面的配置对象，不需要手工new配置或增加application主类逻辑。

```java
package com.elaina.limitedtrade.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OrderRateLimitProperties.class)
public class OrderRateLimitConfiguration {
}
```

### OrderRateLimitExceededException.java

这是HTTP错误，不是普通订单Response。继承ResponseStatusException指定429；保存等待秒数，覆盖getHeaders返回Retry-After。已核对当前Spring版本基类返回不可变HttpHeaders.EMPTY，因此不能直接对基类头集合set。

```java
package com.elaina.limitedtrade.ratelimit;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class OrderRateLimitExceededException extends ResponseStatusException {
    private final long retryAfterSeconds;

    public OrderRateLimitExceededException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "下单请求过于频繁，请稍后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
```

### RedisOrderRateLimiter.java

构造注入StringRedisTemplate和配置对象，加载类路径Lua并声明结果Long。check返回void，正常结束代表放行，抛异常代表停止；关闭时直接结束，不调用Redis。只捕获具体连接失败和查询超时转503，脚本或损坏键错误继续传播。

```java
package com.elaina.limitedtrade.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RedisOrderRateLimiter {
    private final StringRedisTemplate redis;
    private final OrderRateLimitProperties properties;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();

    public RedisOrderRateLimiter(StringRedisTemplate redis, OrderRateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
        script.setLocation(new ClassPathResource("redis/order-rate-limit.lua"));
        script.setResultType(Long.class);
    }

    public void check(long userId) {
        if (!properties.enabled()) {
            return;
        }
        String key = properties.keyPrefix() + ":user:" + userId;
        long retryAfter;
        try {
            retryAfter = redis.execute(script, List.of(key), Integer.toString(properties.maxRequests()),
                    Long.toString(Duration.ofSeconds(properties.windowSeconds()).toMillis()));
        } catch (RedisConnectionFailureException | QueryTimeoutException exception) {
            // 外部限流服务不可用时拒绝下单，数据库事务尚未开始。
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "下单限流服务暂不可用", exception);
        }
        if (retryAfter > 0) {
            throw new OrderRateLimitExceededException(retryAfter);
        }
    }
}
```

execute传入一个键，以及两个字符串参数：最大次数、窗口毫秒。List.of(key)对应脚本的KEYS；Duration.ofSeconds(...).toMillis()使用long，避免int乘法溢出。参数由已校验的内部配置提供，不在每次请求重复校验。按实际同步execute契约读取Long，不添加null放行回退。

## 3. Lua脚本逐段作用

```lua
-- 一个用户共用一个键；首次请求同时建立计数和过期时间。
local current = redis.call('GET', KEYS[1])
if not current then
    redis.call('SET', KEYS[1], 1, 'PX', ARGV[2])
    return 0
end

-- 外部存储中的损坏键必须报错，不能变成永久额度或无条件放行。
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then
    return redis.error_reply('Order rate limit key has no expiration')
end
local count = tonumber(current)
if not count or count < 1 or count % 1 ~= 0 then
    return redis.error_reply('Invalid order rate limit counter')
end

if count < tonumber(ARGV[1]) then
    redis.call('INCR', KEYS[1])
    return 0
end

-- 拒绝不递增、不续期；剩余0毫秒也至少等待1秒。
return math.floor(ttl / 1000) + 1
```

KEYS[1]是完整Redis键，Lua数组从1开始。ARGV[1]是次数上限，ARGV[2]是毫秒窗口。不存在键时SET value1 PX窗口，一条命令同时保存计数和期限；返回0由Java识别为放行。

已有键先读PTTL：返回毫秒剩余时间，负数表示缺少期限或键，当前脚本协议不接受这种损坏状态，redis.error_reply使Java得到Redis错误。tonumber将字符串计数转成Lua数值；非数字、小于1或非整数也报错，不隐式重建或放行。错误Redis类型由GET自身快速报错。

没达到上限就INCR，保留首次到期时间；达到上限不再加计数，返回floor(ttl/1000)+1。即使ttl=0也返回1，防止把临近过期的拒绝误当成放行0；Retry-After为保守整数等待，可能略长于真实剩余时间。拒绝不执行EXPIRE，所以持续重试不会变成永远不结束的窗口。

Redis保证脚本执行期间相关命令不被其他客户端操作穿插；这不同于数据库事务回滚。本脚本在已有键写入前检查状态，新键一次SET带期限，没有拆成两个网络请求的INCR/EXPIRE中间窗口。DefaultRedisScript复用Spring脚本执行/缓存机制，不手工SCRIPT FLUSH。

## 4. Controller实际改动

OrderController增加RedisOrderRateLimiter字段和构造参数。原createOrder/checkout在调用Service之前各新增同一句：

```java
rateLimiter.check(user.getId());
```

user来自@AuthenticationPrincipal PlatformUserDetails。请求中的伪造userId不影响键，同一用户在两个入口最终都使用keyPrefix + ':user:' + userId。没有Filter/Interceptor的新路径判断或IP信任规则。OrderService及原MySQL库存条件更新、事务、Outbox、afterCommit均不改。

## 5. 配置及响应边界

```yaml
app:
  order-rate-limit:
    enabled: true
    max-requests: 5
    window-seconds: 10
    key-prefix: limited-trade:rate-limit:order
```

application-test.yml只覆盖enabled=false，让原业务回归保持原行为；专项启用真实Redis与随机前缀。禁用不影响原商品缓存自己的开关，也不等于整个应用不再访问Redis。

429通过自定义异常的getHeaders携带Retry-After，Spring MVC处理HTTP状态和头；不新增订单响应字段。503只表示这个入口的限流依赖暂不可用，保护性拒绝在数据库事务之前发生，没有本地放行降级。其他脚本异常不吞掉，不误报429。

## 6. 验收与限制

2026-10-19完整离线package450项通过，0失败/错误/跳过；按本轮40个测试类XML交叉核对，不累加历史报告。新增33项覆盖API9、Redis/Lua10、配置10、真实连接拒绝/受控超时/禁用4。独立只读复核未发现Critical/Important/Minor。

开发十一表保持：原十表公开字段SHA256前后相同，order_shipment仍为空；测试十一表均空且无触发器，随机Redis键和RabbitMQ测试拓扑无残留。JAR Lua与源码字节一致，本机凭据/测试/工具边界通过。

固定窗口从首次请求开始，邻接窗口边界可能出现短时突发，不能声称严格滚动QPS。两个限流器对象100并发请求只放行3，是本机共享预算正确性证据，不是多应用进程部署或生产吞吐基线。查询等接口没有这次新增限流，其原有依赖与行为保持。

本阶段无SQL迁移，无新依赖/下载/临时HTTP进程/提交推送。只操作本轮随机Redis键，不FLUSHDB、不停止共享Redis/RabbitMQ。
