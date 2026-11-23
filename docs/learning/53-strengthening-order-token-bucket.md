# 补强 8 · 交易入口令牌桶

日期：2026-11-23。[实施计划](../superpowers/plans/2026-11-23-strengthening-08-order-token-bucket.md)

## 入口与额度

直接下单和购物车结算由 `OrderController` 调用 `RedisOrderRateLimiter.check(user.getId())`，两个入口共用登录账号的额度。身份从认证会话取得。认证、CSRF 和请求体验证先于限流，订单事务在放行之后开始。幂等重放、业务冲突和商品缺失等已通过准入的请求都扣一个令牌。重复请求耗尽额度时可以先收到 429，取得额度后仍由订单服务识别原订单。查询、支付、取消和确认收货继续走原入口。

仓库配置 `app.order-rate-limit.algorithm=token-bucket` 默认启用令牌桶。改成 `fixed-window` 即可切回既有固定窗口。两种算法同用 `max-requests` 和 `window-seconds`。以默认 5/10 为例，满桶可立即放行 5 次，从耗尽时起每 2 秒形成一个新令牌，空闲 10 秒便恢复满桶。固定窗口则在首个请求开启的 10 秒窗口内最多放行 5 次，耗尽后等待整窗重置。

## Java 与 Redis 的调用

`OrderRateLimitProperties` 绑定配置并在启动时校验取值。自定义配置未声明算法时仍按固定窗口绑定。`RedisOrderRateLimiter` 在构造时选择 Lua 脚本和键前缀，`check()` 把认证用户 ID 放进键，把容量和周期传给脚本。脚本返回 0 代表放行，正数通过 `OrderRateLimitExceededException` 变成 429 和整数秒 `Retry-After`。

`order-token-bucket.lua` 使用 Redis 的毫秒时间，在一个原子操作内完成补充和扣减。一个令牌的计量成本 `tokenCost` 等于周期毫秒数，每毫秒补充 `refillPerMs` 个计量单位。用 `available` 表示余额，`last` 表示该余额对应的服务端时间。限流拒绝不写回余额或续期，`Retry-After` 向上取整到秒。键在余额预计恢复满桶时到期。时钟回拨时暂停补充，直到追上已记时间。

Redis 连接或命令超时返回 503，订单事务尚未开始。正常下单继续使用原有事务、幂等与库存保护。算法有独立键空间，令牌桶键也包含容量和周期。多实例需要使用同一套配置才能共享预算。混用算法或阈值会使用不同的预算，切换后对应键使用该模式仍存活的计数，键已到期时从满额度开始。

## 验证

- 在隔离 MySQL 和 Redis 上通过 37 项限流相关回归和 2 项共享会话回归，覆盖配置、双入口、身份、重放、Redis 故障及两种算法。两个限流实例并发 100 次，在容量 3 的突发测试中合计放行 3 次。令牌桶用容量 2、周期 2 秒验证耗尽后三轮各补一个令牌，以及空闲后恢复满桶。
- 两个独立 JVM 用默认 5/10 参数做真实 HTTP 验收。同一账号跨实例并行提交 6 次，5 次返回 201，1 次返回 429，库存扣 5 件。按 `Retry-After` 等待后在另一实例重试，新建 1 单并扣 1 件。切断两个 JVM 与 Redis 的已建立连接后，下单返回 503，订单数量和库存保持原值。
- 本机证据位于 `tools/strengthening-08-tests.log`、`tools/strengthening-08-session.log` 和 `tools/strengthening-08-http.log`。本轮验证准入曲线和库存正确性，未重跑完整吞吐基准。
