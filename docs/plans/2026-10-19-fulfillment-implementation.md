# 订单履约 Implementation Plan

> 主助手使用superpowers:executing-plans连续执行，完成后一次独立只读复核。用户已授权下一阶段并要求实现后讲解，不等待逐段设计批准。

**Goal:** 管理员整单发货，买家确认收货，真实事务保护状态与发货记录。

**Architecture:** PAID→SHIPPED→COMPLETED；单表order_shipment保存承运/运单号及发货收货时间，订单行锁串行所有状态操作，批量装配详情。

**Tech Stack:** 复用Java21、Boot3.5.16、MyBatis3.0.5和MySQL，无新依赖。

**Spec:** docs/plans/2026-10-19-fulfillment-design.md。

## Global Constraints

保留第十五阶段未提交代码；UTF-8和中文注释。不提交/推送/下载/新工作树，不重跑001—011，不新增HTTP进程；只对双库各执行012一次，不输出真实凭据。

## Review Focus

- 无地址的历史/DIRECT订单不能被隐式填地址后发货。
- 已收货后重试相同发货请求仍幂等；换运单不能覆盖首次记录。
- 主状态成功后发货记录SQL失败，必须真实回滚，不能只看注解。
- 收货/发货/取消/超时竞争保持合法状态及库存不变。
- 增加shipment字段不能泄露其他用户订单或扩大管理列表地址展示。

### Task 1：API与迁移、状态机

**Files:** 新增012、ShipOrderRequest、OrderShipment/Response/Mapper及XML；修改OrderService、TradeOrder、OrderMapper/XML、两个Controller和响应。

**Interfaces:** shipOrder(long,ShipOrderRequest)、receiveOrder(long,long)、getAdminOrder(long)返回TradeOrder；shipmentMapper.insertShipment、markReceived(long,LocalDateTime)、findByOrderIds(List<Long>)。

- [x] 编写FulfillmentApiTest，缺路由预期失败；不新增测试源码讲解。
- [x] 保存开发十表基线/核对测试库空和旧DDL，执行012双库各一次。
- [x] 实现三接口、状态锁/条件更新、发货记录和批量装配；专项通过。

### Task 2：真实事务、并发与持久化

**Files:** FulfillmentTransactionIntegrationTest、FulfillmentConcurrencyIntegrationTest。

- [x] 添加实际SQL失败、时间幂等、Session持久化、无旁路业务变更、发货/收货并发状态竞争测试。
- [x] 核对合法状态与数据原子性；异常直接传播；专项通过。

### Task 3：独立复核、回归与学习交付

- [x] 按本阶段tools/fulfillment-baseline与新增文件独立只读复核，重要发现失败测试复现后修复。
- [x] 完整offline package及开发快照/测试十一表/资源/JAR边界检查。
- [x] 更新README、16-fulfillment学习稿、设计和计划状态及tools/fulfillment-progress.md，完成后详细讲实际新增源码；不进入下一阶段。

验收完成：417项通过；独立只读复核发现的Unicode空白输入已RED→GREEN修复，无待处理重要发现。全部证据见README和16-fulfillment.md。
