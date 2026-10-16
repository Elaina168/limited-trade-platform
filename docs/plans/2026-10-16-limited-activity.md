# 限时限量活动 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在既有交易闭环中加入限时限量活动，并证明名额和商品库存一致变化。

**Architecture:** 沿用单Boot应用、MyBatis与MySQL事务。ActivityMapper提供活动锁和条件扣减，OrderService组织活动→商品的锁顺序，状态转换成功后才归还两份数量。

**Tech Stack:** Java 21、Spring Boot 3.5.16、MyBatis Starter 3.0.5、MySQL 8.0.45；使用现有依赖。

**Spec:** ../learning/09-limited-activity.md

## Global Constraints

- 新文本UTF-8，注释中文；不新增依赖下载，不提交或推送。
- 保持普通下单兼容和本人订单归属校验。
- 活动采用 startsAt <= now < endsAt；已创建订单支付期限仍是15分钟。
- 名额不预留商品库存；没有折扣、活动编辑、删除或每人限购。
- 本机真实配置与凭证不输出；工具和日志留在被忽略的tools下。

## Review Focus

- 锁等待跨活动结束时间：拿到库存锁后再检查时间。
- 不同活动或普通下单复用requestId：比较nullable activityId。
- 商品库存不足或名额不足：两种扣减不得部分提交。
- 重复取消/关闭、取消与关闭竞争：只归还一次两份数量。
- 旧订单无activityId、活动结束后支付：保持原规则。

### Task 1: 活动配置与持久化

**Files:** sql/006_activity.sql、activity包下Activity/CreateActivityRequest/ActivityMapper/ActivityService/AdminActivityController/ActivityController、mapper/ActivityMapper.xml、SecurityConfig、ActivityApiTest和activity-test-data.sql。

**Interfaces:** CreateActivityRequest(productId,name,totalQuantity,startsAt,endsAt)；ActivityMapper.findById/findByIdForUpdate/insertActivity/deductQuantity/restoreQuantity；ActivityService.createActivity/getActivity。

- [x] 先写管理员创建用例，观察预期201实际404。
- [x] 保存开发库基线，执行006开发库和测试库增量；不改已有数据行。
- [x] 实现管理员创建、公开详情、参数/时序/存在性校验；活动初始剩余名额等于总名额。
- [x] 测试创建不扣库存、权限、空白名称、名额和时间边界。

### Task 2: 活动订单及归还

**Files:** CreateOrderRequest、TradeOrder、OrderResponse、AdminOrderResponse、OrderService、OrderMapper.xml、ActivityOrderApiTest、ActivityConcurrencyTest。

**Interfaces:** CreateOrderRequest新增nullable activityId；现有创建/支付/取消/关闭接口不改路径；响应携带activityId。

- [x] 先写活动订单测试，验证缺失功能会失败。
- [x] 全部订单查询/插入映射activity_id；幂等比较增加活动编号。
- [x] 在用户锁后锁活动，验证商品对应；扣商品后取时间，再条件扣活动名额，任何失败同事务回滚。
- [x] 取消与关闭成功后按活动→商品顺序归还；普通订单跳过活动归还。
- [x] 验证时间边界、重复请求、支付期限、名额和商品竞争、并发重复取消、SQL故障回滚、锁等待跨活动结束。
- [x] 离线package全量测试；检查测试库清理及开发库基线；独立只读复核。
- [x] 在学习文档保存原始代码、调用链和验证结果，更新README，阶段完成后等待学习者提问。

## 执行记录

- 用户已确认最小业务规则并要求“好，做吧”；沿用当前会话实施，不另建工作树、不提交现有未提交代码。

- 已完成：两次红测试、006迁移、166个测试离线package成功、独立只读复核、开发库基线及测试库清理核验。
- Ruling: 活动订单API测试不使用外层测试事务，改用提交夹具和定向清理，以真实验证Service异常后的回滚；配置API仍保留事务回滚。
