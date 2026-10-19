# Activity Coupon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans，当前会话逐项执行。

**Goal:** 为现有限时限量活动增加具有领取/使用期限的满减券闭环。
**Architecture:** MySQL规则、领券、订单快照三表；订单事务占券、核销和释放，沿用Session/CSRF和库存锁。
**Tech Stack:** Java21、Spring Boot3.5.16、MyBatis3.0.5、MySQL8，复用全部现有依赖。
**Spec:** docs/plans/2026-10-19-activity-coupon-design.md

## Global Constraints

UTF-8、中文注释；保留现有未提交内容；不提交/推送/新工作树；连续实现验证后讲解，不逐项询问批准。不改变购物车或履约边界。执行台账 tools/coupon-progress.md。

## Review Focus

1. 创建/释放/领取锁顺序是否存在环路，锁等待后是否使用新时间。
2. 取消后复用同张券时，旧订单延迟事件不得释放新占用。
3. 券有效期结束和活动结束边界，以及已下单支付金额不可变。
4. 商品价格变化、请求幂等和规则快照不能相互覆盖。
5. 迁移历史订单及无券订单金额，真实事务失败时额度/库存/券必须同时回滚。

## Task 1：发券与领取

Files：新增coupon包的CreateCouponRequest、ActivityCoupon、UserCoupon、UserCouponResponse、CouponMapper、CouponService和两个Controller；mapper/CouponMapper.xml；sql/013_activity_coupon.sql。
Produces：CouponMapper.findCouponById、findOwnedForUpdate、reserve、useForOrder、releaseForOrder及订单快照读写；CouponService提供创建、活动列表、领取和本人列表。

- [x] 写CouponApiTest，先证明创建接口尚不存在；断言满200减20、独立发放额度、每人一张、权限/CSRF、无效金额和时间拒绝。
- [x] 离线Maven运行该类，确认预期404失败，再实现最小接口和013。
- [x] 双库前置检查和迁移，专项通过；领取不改库存与销售名额。

## Task 2：订单优惠事务

Files：CreateOrderRequest、TradeOrder、OrderService、OrderResponse、AdminOrderResponse、OrderMapper.xml；新增OrderCoupon；CouponOrderApiTest、CouponTransactionIntegrationTest、CouponConcurrencyIntegrationTest。
Consumes：Task1的Mapper及user_coupon标识；原四参CreateOrderRequest构造仍可用。
Produces：订单originalAmount/discountAmount/coupon；createOrder可选择userCouponId，pay核销/cancel与closeExpiredOrder释放。

- [x] 先写HTTP用券RED测试：258原金额、20优惠、238应付；无活动/异主/异活动/门槛不足/过期拒绝，同请求不同券409。
- [x] 最小接入，批量加载coupon快照；时间在全部锁等待后采样；占券与快照同事务。
- [x] 补先失败的事务/并发测试：最后一步失败回滚，支付失败核销回滚，取消/超时失败释放回滚；多人抢少量券、同券不同请求、取消后旧关闭事件、等待跨有效期。
- [x] 各专项GREEN并记录，不展示测试源码。

## Task 3：独立复核和完整验收

- [x] 对本阶段基线差异执行一次独立只读复核，不改文件、不运行测试、不读本机凭据。
- [x] Important/Critical先RED再修复并全量回归，Minor列明。
- [x] 完整offline package；按本轮日志类名统计XML，核对无数据/随机资源残留，JAR无凭据。
- [x] 更新README及docs/learning/18-activity-coupon.md；集中讲新增代码及证据，停在当前阶段。
