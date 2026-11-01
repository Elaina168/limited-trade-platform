# 超时关单与库存回补

## 关单条件与状态
待支付订单达到或超过expiresAt时，可以从PENDING_PAYMENT转为CLOSED。已经支付或处于其他状态的订单，不满足超时关闭的状态条件。超时关闭是系统对过期待支付订单的处理，不等于用户主动取消的CANCELED状态。

## 消息、扫描与处理延迟
系统通过RabbitMQ超时消息消费触发关单，并保留定时扫描兜底。消息或扫描执行可能有处理延迟，所以支付期限已到而数据库状态暂未变为CLOSED，并不表示仍允许支付。单凭CLOSED状态不能确定是消息消费者还是扫描完成了关闭。

## 回补与幂等
成功关闭订单后，在同一事务中归还商品库存、活动名额并释放适用优惠券占用。只有成功转换状态的一次执行会回补；重复投递或扫描再次处理同一个已关闭订单，不会重复回补库存。补发超时消息不延长原来的支付截止时间。

## 规则依据
依据：src/main/java/com/elaina/limitedtrade/order/OrderService.java的closeExpiredOrder；src/main/resources/mapper/OrderMapper.xml的markClosed；OrderTimeoutScheduler与order/message/OrderTimeoutConsumer。
