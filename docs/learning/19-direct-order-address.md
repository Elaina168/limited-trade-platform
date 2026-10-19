# 第十九阶段：活动下单接入收货地址

## 1. 本次只增加哪里

生产代码只改CreateOrderRequest和OrderService。原购物车结算已能查本人地址并保存order_address，本次把同一下单能力接给DIRECT入口（包含活动单和普通单商品单），复用原表、Mapper和saveOrder，不增加迁移、依赖、Controller或Response。

addressId可选，省略保持旧行为；选择地址的新订单可沿用现有支付、发货、收货流程。历史无地址订单不补地址，也不能靠换requestId含义覆盖原单。

## 2. CreateOrderRequest新增字段

```java
@Positive(message = "地址编号必须大于0") Long addressId
```

Long允许null，@Positive仅在传值时要求大于0。没有@NotNull，所以旧HTTP请求可以省略。原四参构造现在调用六参构造并把券/地址设null；原五参构造也保留，把地址设null。已有Java下单测试调用无需批量改签名。

## 3. createOrder先处理幂等

原判断商品/数量/活动/券的条件后增加：

```java
|| !Objects.equals(
    existing.getAddress() == null ? null : existing.getAddress().sourceAddressId(),
    request.addressId())
```

existing是同用户、同requestId的旧订单。loadDetails已给它装好地址快照。三元表达式从旧快照取来源地址编号，无地址则null；Objects.equals可安全比较两个可空值。相同编号通过；换编号、由有地址变为无地址或由无地址变为有地址都409。

幂等分支先返回旧单，因此不依赖地址簿记录仍存在。旧地址修改或删除后，同一请求重试返回原来的收货信息；同一个来源编号的地址内容变了，也不会更新已成交快照。

## 4. 新订单查询本人地址

```java
UserAddress address = null;
if (request.addressId() != null) {
    address = addressMapper.findOwnedAddress(request.addressId(), userId);
    if (address == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地址不存在");
    }
}
```

这段在幂等返回之后、活动和商品操作之前。未选地址保持null；选了地址则按编号及当前登录用户查询。不存在与非本人统一404，不泄露别人的地址。

createOrder一开始已取得用户行锁；AddressService创建/修改/删除/设默认也先取得同一用户锁。因此地址读取到订单保存期间，本人的并发地址修改需要等待，不额外增加另一种锁顺序。当前查询失败还没扣库存、活动名额或占券。

## 5. 传给已有保存方法

原调用的地址参数是null，现在变为查询结果：

```java
saveOrder(userId, request.requestId(), "DIRECT", List.of(item), address, now, coupon)
```

saveOrder已经存在这段代码，本次无需修改：

```java
if (address != null) {
    orderAddressMapper.insertAddress(new OrderAddress(
        order.getId(), address.getId(), address.getRecipientName(),
        address.getPhone(), address.getProvince(), address.getCity(),
        address.getDistrict(), address.getDetailAddress()));
}
```

它复制收货人、电话、省市区、详情到order_address，并保存sourceAddressId作幂等比较。OrderResponse中的address由既有loadDetails与OrderAddressResponse返回，管理员详情也已有显示。发货已有的PAID且有地址判断自然能够通过。

## 6. 验证范围

新增11项真实MySQL/MockMvc测试先全部失败，再实现通过；联同原优惠券、购物车结算和履约接口共73项通过。包括完整用券活动订单履约、地址变更删除后重试、归属和非正数、幂等地址选择、无地址兼容、普通DIRECT复用、地址插入和后续Outbox受控失败回滚。

本阶段不修改券规则、活动名额协议、商品扣减协议或购物车；不新增真实支付、HTTP部署或生产压测证据。完整回归和资源核对以README与tools/direct-address-progress.md最终记录为准。

最终完整离线package505项通过，0失败/错误/跳过，44个本轮测试类XML计数一致。独立只读复核无Critical/Important/Minor。开发十四表保持，测试十四表空且无触发器，Redis/MQ随机资源无残留，JAR与编译类一致且无本机凭据/配置/工具/测试资源。无新迁移、依赖、工作树、提交或推送。
