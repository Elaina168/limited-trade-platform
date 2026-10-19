# 第十八阶段：限时限量活动专用满减券

## 1. 本阶段解决的问题

原活动只有开始/结束时间和销售名额，成交仍是商品现价乘数量。本阶段让管理员给某个活动配置满减券，用户先领取，活动下单时选择自己的用户券。例如单价129元、数量2，明细258元；使用满200减20券，原金额258、优惠20、应付238。券发放数量、活动销售名额、商品库存分别管理：领一张券只减券发放余量，下单买两件才减2库存和2活动名额。

本阶段每人每种券限领一张、每单最多一张、不叠加，只用于POST `/api/orders` 的活动购买。购物车结算保持原接口。原单商品订单仍没有地址，不能直接用于发货；本阶段没有扩展该边界。

## 2. 规则、本人券与成交快照

| 文件/表 | 保存什么 | 用在哪里 |
|---|---|---|
| ActivityCoupon / activity_coupon | 活动编号、名称、门槛、减额、总发放量/余量、领取和使用时间 | 创建规则、活动页展示、领券与下单校验 |
| UserCoupon / user_coupon | 券规则编号、领取人、状态、领取时间、当前订单编号 | 保证本人使用、一张券只有一个当前占用 |
| OrderCoupon / order_coupon | 当次订单所选用户券编号及完整成交规则 | 解释订单优惠、幂等比较、历史查询 |

`activity_coupon.id` 是一种券的规则编号。`user_coupon.id` 是某个人领到的那张券的编号。下单的 `userCouponId` 必须填写后者，不能把活动编号或券规则编号当成它。

规则只提供创建入口，不提供修改或删除。订单仍另存快照，避免历史成交依赖展示规则。取消后的旧订单保留快照；原用户券解除占用后可以用于新的订单，因此 order_coupon 的 user_coupon_id 不是唯一键。

## 3. 新接口与请求对象

`AdminCouponController.create` 接收 `@Valid CreateCouponRequest`，调用 `CouponService.createCoupon`，返回201和券规则。请求包括：activityId、name、thresholdAmount、discountAmount、totalQuantity、claimStartsAt、claimEndsAt、useStartsAt、useEndsAt。金额最多8位整数和2位小数，门槛与减额均为正，减额严格小于门槛。

`createCoupon` 确认活动存在，将时间统一为秒，检查两个区间起点早于终点、领取截止不晚于使用截止，且使用区间位于活动区间内；名称strip后不能空白。之后初始化总量和余量并入库。

`CouponController` 提供三个入口：

- GET `/api/activities/{activityId}/coupons`：公开查询活动绑定的规则，返回时间和剩余发放量，过期记录也保留。
- POST `/api/coupons/{id}/claim`：必须登录和CSRF，路径id是券规则编号；领取归属取认证用户，不信任请求传入的userId。
- GET `/api/coupons/mine`：只返回本人券，UserCouponResponse包含id、status、claimedAt、orderId、coupon和expired。coupon是规则详情，expired只说明是否达到使用截止，AVAILABLE也可能已经过期。

`SecurityConfig` 增加 `/api/coupons/**` 的登录要求；管理员创建继续使用已有 `/api/admin/**` 的ADMIN权限。

## 4. 领取事务如何保证不超发

`CouponService.claim(userId,couponId)` 先锁用户行，查询本人是否已领过。已领取直接返回原记录，哪怕现在已过期，也不会再发一张或扣额度。未领取则锁活动券规则行，锁等待结束后重新取now，调用 `deductClaim`：

```sql
UPDATE activity_coupon SET remaining_quantity=remaining_quantity-1
WHERE id=#{id} AND remaining_quantity>0
  AND claim_starts_at <= #{now} AND claim_ends_at > #{now}
```

返回0代表不在领取时间或已领完，409；返回1才插入AVAILABLE用户券。扣额度和插入同事务，插入失败会恢复额度。数据库唯一键 `(coupon_id,user_id)` 表达每人每种一张。领券不写商品、活动名额或订单。

`listMine` 批量读取本人券与相关规则，用Map关联，避免每张券单独查规则。

## 5. 下单新增的代码

`CreateOrderRequest` 原四字段后新增可空正数 `userCouponId`；原Java四参构造保留并传null，旧调用不必修改。HTTP请求省略该字段就不使用优惠券。

`OrderService.createOrder` 的幂等处理仍在业务校验前：已有订单的商品、数量、activityId和userCouponId都一致就返回原单；不同则409。因此原单已使用的券后来过期、取消或用于另一单，都不会使旧请求重新扣资源。

新订单按用户→活动→商品→用户券的顺序取锁。`findOwnedForUpdate` 同时限制用户券id和user_id，不存在或非本人404。全部锁等待结束后采样now，然后检查：用户券AVAILABLE、规则activityId与请求活动一致、使用开始≤now<使用结束、明细金额达到门槛。失败409并回滚先前商品扣减。现有活动扣名额SQL同时检查活动时间与剩余名额。

通过后构造OrderCoupon，把当时规则放进快照。`saveOrder` 用明细合计设originalAmount，用券减额设discountAmount，totalAmount=originalAmount-discountAmount。插入主表取得订单id，再把用户券改为RESERVED并绑定该订单，插入order_coupon，随后保存明细和Outbox。所有步骤同事务，商品缓存仍只在提交后失效。

## 6. 金额和响应

`TradeOrder` 增加originalAmount、discountAmount和coupon。OrderMapper所有订单查询都读取新金额列，insertOrder写total_amount与discount_amount。

数据库original_amount采用持久化生成列 `(total_amount+discount_amount)`，不用应用在多列之间重复维护一致性。旧单discount_amount默认0，所以迁移后原金额等于原来的应付金额，不改历史价格。明细line_amount保持未优惠金额，优惠属于整单。

`loadDetails` 额外批量读order_coupon，以order_id建立Map，再setCoupon到每个订单。当前一批订单的主表、明细、地址、发货、优惠共5次查询。OrderResponse和AdminOrderResponse新增originalAmount、discountAmount与coupon；OrderCouponResponse过滤内部orderId，Spring将这些值序列化到JSON。

## 7. 支付、取消与超时

`payOrder` 成功将待支付订单转为PAID后调用 `useForOrder(id)`，把该订单的RESERVED券改为USED。这里不重新检查券的使用期，因为优惠在有效期内下单时已锁定；订单仍必须在原15分钟支付截止前付款。核销失败会回滚支付状态。

`cancelOrder` 与 `closeExpiredOrder` 只有成功转换订单状态后才归还库存和活动名额，再调用 `releaseForOrder(id)`：只将当前绑定该订单且RESERVED的用户券改回AVAILABLE、清空order_id。释放失败时，状态和资源归还一起回滚。

重复取消/关闭不会重复归还。取消后券用于新单，旧单的迟到关闭事件也无法释放新占用，因为查询限定旧order_id，且原订单已经不是待支付。USED不被释放，已支付订单仍遵循原状态规则，不能用取消接口退券。

过期不删除用户券、不改变订单金额、不批量写状态。释放后的AVAILABLE券若已经达到useEndsAt，下次下单仍409。券发放余量不会因取消恢复，它代表未发出的领取资格，不是未使用券数量。

## 8. 迁移和验证

`sql/013_activity_coupon.sql` 新建三张券表，增加主表优惠列及生成原金额列。开发库和测试库各执行一次；执行前检查012已存在且013新表不存在，执行后确认历史单原金额=应付、优惠=0。不要重复执行013。

接口测试使用MockMvc和真实MySQL；事务故障通过测试库定向触发器制造，再清理；并发使用真实数据库事务，证明额度与状态正确性。现有Redis限流仍只作用于两个下单入口，领券本阶段不新增Redis限流。最终完整回归与数据核对记录在README和忽略的tools/coupon-progress.md，不展示测试源码，也不将本轮结果称为真实HTTP部署、生产压测或真实支付。

本阶段最终新增44项专项通过，完整离线package494项通过（0失败、0错误、0跳过）；独立只读复核无问题。开发原十一表公开字段快照一致，新三表无业务数据；测试十四表为空，无触发器、随机Redis键或MQ测试拓扑残留，JAR检查通过。事务和并发放在同一测试类复用夹具，没有额外拆成第二个并发类。没有新增依赖、提交或推送。
