# 订单支付与截止时间

## 待支付订单的付款期限
创建订单时设置15分钟的支付期限，具体时间以订单字段expiresAt为准。订单处于PENDING_PAYMENT待支付状态，不代表查询时仍然可以付款。只有状态仍为PENDING_PAYMENT且支付操作时刻严格早于expiresAt，才允许模拟支付。已经到达截止时刻也不能付款。

## 模拟支付与重复请求
当前平台仅实现模拟支付状态转换，没有接入真实银行、微信或支付宝支付。已支付订单的重复支付请求返回原结果，不再次扣减库存。其他状态或超出支付期限时返回状态冲突。实际是否能支付，必须以支付接口执行时的检查为准。

## 规则依据
依据：src/main/java/com/elaina/limitedtrade/order/OrderService.java的createOrder与payOrder；src/main/resources/mapper/OrderMapper.xml的markPaid。规则说明不能替代实时订单查询。
