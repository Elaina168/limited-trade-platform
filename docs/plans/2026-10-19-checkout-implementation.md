# 多商品结算 Implementation Plan

> 使用superpowers:executing-plans，由主助手连续实现；用户已要求完成后再讲解。保留当前工作区，不提交或推送。

**Goal:** 普通购物车原子结算为一张多明细订单，保存地址快照并沿用原状态机。

**Architecture:** 订单主表、明细表、地址快照表；用户锁串行本人的下单/购物车/地址写入，商品锁升序；Outbox与业务同事务。

**Tech Stack:** Java21、Boot3.5.16、MyBatis3.0.5、现有MySQL/Redis/RabbitMQ，无新依赖。

**Spec:** docs/plans/2026-10-19-checkout-design.md。

## Global Constraints

UTF-8、中文注释；不读取输出真实凭据。迁移011双库各一次；不重跑001—010，不重试临时HTTP启动。不另建工作树、不提交推送。

## Review Focus

- 请求成功后购物车被删除/地址被修改或删除，重试仍返回首次订单。
- 购物车更新和结算竞争时，后发生的购买意向不能被误删。
- 不同顺序的多商品竞争，锁升序；活动锁保持先于商品锁。
- 多次库存归还或中途失败，不出现部分归还及非法终态。
- 旧查询夹具、历史活动订单和管理分页不依赖已移除主表商品列。

### Task 1: 迁移与结算API

**Files:** 新增sql/011_order_checkout.sql、order/CheckoutRequest、OrderItem、OrderAddress、对应Mapper/XML和响应；修改OrderService、OrderController、OrderMapper、TradeOrder、CartService、ProductMapper。

**Interfaces:** POST /api/orders/checkout接收productIds/addressId/requestId，首次201/重试200；OrderService.checkout(long,CheckoutRequest)返回CreateOrderResult。OrderItemMapper.findByOrderIds(List<Long>)、OrderAddressMapper.findByOrderIds(List<Long>)供装配。

- [x] 先写CheckoutApiTest并执行，确认因缺结算入口失败；增加现有响应结构变更的失败断言。
- [x] 隔离schema验证旧数据迁移；保存开发八表公开字段基线，确认测试库为空；双库执行011并核对DDL。
- [x] 实现主表/明细/地址模型与批量装配、两种下单和幂等、用户锁及升序商品锁；迁移原查询/测试夹具。
- [x] 执行CheckoutApiTest与原订单/活动/购物车测试，确认通过。

### Task 2: 并发、SQL失败与状态生命周期

**Files:** 新增CheckoutConcurrencyIntegrationTest、CheckoutTransactionIntegrationTest；同步取消/超时归还与缓存集成覆盖。

**Interfaces:** 沿用payOrder/cancelOrder/closeExpiredOrder；仅成功终态转换者按明细归还，状态与库存同事务。

- [x] 先补多商品取消/关闭、库存竞争、重复结算、购物车更新竞争、SQL失败回滚测试，确认缺失行为失败。
- [x] 完成多明细归还、Outbox原子性、Session持久化和失败回滚，专项通过。
- [x] 核对开发数据基线与测试清理，记录实际命令/结果。

### Task 3: 复核与交付

**Files:** README.md、docs/learning/15-checkout.md、本设计/计划与tools/checkout-progress.md。

- [x] 一次独立只读复核（executing-plans要求），重要发现用失败测试复现后修复。
- [x] 执行完整offline package（Maven项目既有命令），0失败/错误/跳过；不重复无理由构建。
- [x] 核对开发公开数据、测试十表/触发器、自有Redis/MQ资源和JAR凭据边界。
- [x] 更新学习稿与验收记录，先交付结果，再开始第一段实际新增源码讲解；等待用户继续。

验收完成：全量381项通过；独立只读复核无额外重要发现。详细证据和边界见README及15-checkout.md。
