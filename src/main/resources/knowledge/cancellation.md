# 本人订单主动取消

## 取消条件与重复请求
用户可以主动取消本人的PENDING_PAYMENT待支付订单。已取消的CANCELED订单再次请求取消，返回原结果。已支付、已发货、已完成或已关闭的订单不能按待支付取消流程操作，返回状态冲突。当前取消SQL按待支付状态判断，没有额外要求当前时刻早于支付截止时间。

## 库存与优惠券释放
取消操作在数据库事务内转换订单状态，归还商品库存；活动订单同时归还相应活动名额。适用的优惠券占用也随订单取消释放。重复取消已经取消的订单，不会再次归还库存。不能把助手解释规则当作已经执行取消，当前AI工具没有取消订单能力。

## 规则依据
依据：src/main/java/com/elaina/limitedtrade/order/OrderService.java的cancelOrder与restoreItems；src/main/resources/mapper/OrderMapper.xml的markCanceled。助手只提供咨询，实际操作需使用既有业务接口。
