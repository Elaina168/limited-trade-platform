# 发货与确认收货

## 管理员发货条件
管理员只能为PAID已支付订单登记发货，订单必须已有收货地址快照，承运方与运单号不能是空白。登记发货后订单进入SHIPPED状态。已发货或已完成订单重复提交相同的发货信息返回原结果，使用不同发货信息会产生状态冲突。此处仅解释规则，AI没有发货工具。

## 买家确认收货
买家只能确认本人的SHIPPED已发货订单，成功后状态变为COMPLETED并记录收货时间。已完成订单重复确认返回原结果，不覆盖原收货时间。其他状态不能确认收货。订单不存在或不属于本人按既有归属规则返回404。

## 规则依据
依据：src/main/java/com/elaina/limitedtrade/order/OrderService.java的shipOrder与receiveOrder；src/main/resources/mapper/OrderMapper.xml的markShipped与markCompleted。
