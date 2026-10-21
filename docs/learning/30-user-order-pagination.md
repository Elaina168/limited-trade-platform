# 用户订单分页

日期：2026-10-21。

## 请求与返回

`GET /api/orders?page=2&size=20&status=PAID`

- `page`默认1，必须为正整数。
- `size`默认20，范围1—100。
- `status`可省略；允许`PENDING_PAYMENT`、`PAID`、`CANCELED`、`CLOSED`、`SHIPPED`、`COMPLETED`，其余值返回400。
- 只能查询登录用户本人；不接收用户编号作为查询条件，额外传入`userId`不能改变查询范围。未登录返回401，ADMIN访问这个接口也只查自己的订单。

响应从原来的订单数组改为以下分页对象，分页约定与管理员订单一致：

```json
{
  "page": 2,
  "size": 20,
  "total": 23,
  "items": []
}
```

上例只展示分页结构。真实`items`为当前页订单，订单字段仍使用`OrderResponse`，包括明细、地址快照、优惠和物流等既有内容。`total`为本人且符合状态条件的订单总数；超出末页返回空`items`，保留总数和请求页码。

## 数据流程

1. `OrderController.listOrders`读取登录身份并校验分页、状态参数。
2. `OrderService.listUserOrders`在只读`REPEATABLE_READ`事务中先计数，再查询当前页；偏移量用`long`计算。
3. `OrderMapper.xml`的计数与列表共用`userOrderFilters`，始终包含`user_id`条件，按`id DESC`排序并使用`LIMIT/OFFSET`。
4. 仅为当前页订单批量加载明细、地址、物流和优惠，再组装`OrderPageResponse`。

用户页面沿用现有分页组件，每页20条，支持状态筛选。页码和筛选条件保存在Hash路由中；筛选时回到第一页，翻页保留筛选，刷新和支付、取消、收货后的列表重载保留当前路由。没有匹配订单时显示空结果。

未确认下单提示仍只在查到相同`requestId`时清除；当前页没有找到不代表订单不存在，仍可使用原请求重试确认结果。

## 调用方与验证

- 同步调整订单、活动、结算、履约和注册关联测试中的列表解析。
- 数据库恢复验证脚本改为读取分页对象的`items`；本次没有重新执行备份恢复流程。
- 双实例验证脚本只比较两个实例的完整响应，不依赖数组结构，无需改动。
- 未增加数据库表、迁移或依赖，没有修改订单写入、库存、支付和消息逻辑。

关联后端测试：`UserOrderPageApiTest`、`AdminOrderApiTest`、`OrderApiTest`、`ActivityOrderApiTest`、`CheckoutApiTest`、`CheckoutTransactionIntegrationTest`、`FulfillmentApiTest`、`RegistrationIntegrationTest`，共152项通过，0失败、0错误，其中新增分页测试22项。新增分页测试先确认旧实现失败，再实现功能。

浏览器使用现有Playwright契约夹具，运行`pagination`、`user`、`retry`、`privacy`四组场景。新场景覆盖23条订单跨页、首末页按钮、状态筛选重置页码、取消后刷新、筛选空结果与清除筛选；这属于真实浏览器配合模拟接口验证，不是浏览器连接真实后端的端到端验收。

首次关联后端回归因Redis未运行导致结算事务测试失败并中断清理。恢复已有WSL Redis后，按测试库名称和夹具身份核对并清理本次残留，再重跑关联测试。日志位于被Git忽略的`tools/user-order-pagination-*.log`，未运行全量后端回归。
