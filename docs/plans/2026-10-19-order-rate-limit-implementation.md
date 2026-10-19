# 下单入口限流 Implementation Plan

> 主助手按superpowers:executing-plans连续实现，按test-driven-development先失败后实现，完成一次独立只读复核。用户授权实现后讲解，保留工作区，不提交推送。

**Goal:** 两种订单创建入口按登录用户共享Redis Lua固定窗口预算。

**Architecture:** Controller在Service事务前调用限流器；单键Lua判断/计数/TTL，429带等待秒数，连接/超时503；测试默认关闭、专项随机键启用。

**Tech Stack:** 复用Java21、Boot3.5.16、Spring Data Redis3.5.13及Redis6，无新增依赖/数据库迁移。

**Spec:** docs/plans/2026-10-19-order-rate-limit-design.md。

## Global Constraints

UTF-8和中文注释。保留十五/十六阶段全部未提交代码；不安装/下载/提交/推送/新工作树/新增HTTP进程，不重跑001—012。仅操作自有随机Redis键。

## Review Focus

- 两入口、同用户不同Session不能绕开同一个预算。
- 已幂等成功请求重试被429时，不改变原订单/库存/购物车。
- 缺身份/CSRF/非法输入被框架提前拒绝，不消耗用户额度。
- TTL最后不足1毫秒、拒绝请求不能误放行或延长窗口。
- Redis连接/超时不进入数据库事务，脚本错误不静默降级。

### Task 1：入口API与Lua实现

**Files:** ratelimit包四个新类、redis/order-rate-limit.lua、OrderController及两个yml。

**Interfaces:** RedisOrderRateLimiter.check(long userId)；OrderRateLimitProperties(enabled,maxRequests,windowSeconds,keyPrefix)；超限OrderRateLimitExceededException含Retry-After。

- [x] 编写OrderRateLimitApiTest并执行，确认缺限流导致失败。
- [x] 编写Lua和配置绑定/具体外部错误处理，在两个创建方法接入；专项通过。

### Task 2：真实Redis/并发/故障与配置验证

**Files:** OrderRateLimiterIntegrationTest、OrderRateLimitConfigurationTest及真实Session API覆盖。

- [x] 验证两个实例并发共享预算、TTL不续期与过期恢复、损坏键快速失败、禁用无Redis访问。
- [x] 使用实际关闭端口连接拒绝和受控超时检查503，MockMvc证明数据库不变；参数非法启动绑定失败。
- [x] 测试清理仅本轮随机键，保留共享Redis/MQ服务。

### Task 3：复核、回归和交付

- [x] 按tools/rate-limit-baseline做本阶段独立只读复核，重要发现用RED→GREEN修复。
- [x] 完整offline package，开发十一表快照/测试清理、Lua归档与凭据边界核对。
- [x] 更新README、17-order-rate-limit学习稿和执行记录，完成后详细讲新增类、Lua与Controller调用链，等待当前阶段学习结束。

验收完成：450项通过；独立只读复核无待处理重要或次要发现。证据及限制见README与17-order-rate-limit.md。
