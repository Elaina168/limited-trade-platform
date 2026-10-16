# 活动全流程：从创建活动到订单结束

这是第九阶段的补充阅读稿。原始代码快照与文件变化见[第九阶段学习记录](09-limited-activity.md)。本文按实际调用顺序组织，不新增功能。

## 贯穿全文的假设数据

以下编号、价格、时间用于讲解，不表示开发库已有这些记录：商品1、单价20、可售库存10；管理员创建活动10，名额5，2026-10-17 10:00至11:00允许下单；用户2在10:50买2件，生成订单100，金额40，支付截止11:05。

## 运行应用后的准备

Spring启动时创建Controller、Service等Bean，MyBatis创建Mapper代理并加载XML。Controller构造器收到Service，Service构造器收到Mapper实例。请求仍经Tomcat、Security过滤链和DispatcherServlet进入对应Controller。

管理员创建活动需ADMIN和CSRF；用户下单、支付、取消需登录和CSRF；活动详情GET可以匿名访问。定时关闭由Spring调度触发，不经过HTTP和Controller。

## 一、管理员创建活动

POST /api/admin/activities：

```json
{"productId":1,"name":"键盘限量活动","totalQuantity":5,"startsAt":"2026-10-17T10:00:00","endsAt":"2026-10-17T11:00:00"}
```

1. Spring把JSON转换成CreateActivityRequest，@Valid触发非空、正数、名称长度等校验。
2. AdminActivityController.createActivity调用activityService.createActivity(request)。
3. Service把时间归一到秒，校验结束晚于开始；通过ProductMapper确认商品存在。
4. new Activity并设置字段。totalQuantity=5，remainingQuantity也=5；Request不是数据库行，不会因为接收JSON就自动入库。
5. activityMapper.insertActivity(activity)由MyBatis执行XML中的INSERT。
6. 数据库生成活动主键；useGeneratedKeys=true、keyProperty=id让MyBatis通过setter回填activity.id。Mapper返回的影响行数不是活动主键。
7. Service事务提交，Controller把Activity作为201响应正文，Spring序列化JSON。

此时product.stock仍10，activity.total_quantity=5、remaining_quantity=5。创建活动不操作库存，不创建订单。

## 二、用户查看活动

GET /api/activities/10 → ActivityController.getActivity(10) → ActivityService.getActivity(10) → ActivityMapper.findById(10) → SELECT activity。

MyBatis将查询结果映射成Activity；没有记录则Service抛404，有记录则Controller返回200 JSON。普通查询没有FOR UPDATE，不减少名额，也不预占资格。

当前没有活动列表、编辑、删除、发布状态或优惠价。活动是否允许新下单由起止时间和剩余名额判断，不依赖定时器把活动改成某种状态。

## 三、用户创建活动订单

POST /api/orders：

```json
{"productId":1,"quantity":2,"requestId":"activity-buy-001","activityId":10}
```

### Controller取得身份与输入

Spring把JSON转换成CreateOrderRequest；userId来自认证后的PlatformUserDetails，不来自客户端JSON。OrderController调用orderService.createOrder(user.getId(), request)。整个Service方法受@Transactional管理。

### 先锁用户并处理幂等

appUserMapper.lockById(userId)锁住该用户行。同一用户下单串行，之后findByUserAndRequest(userId,requestId)查已有订单。

requestId是客户端生成的重试标识，不是订单编号。若已有订单，比较productId、quantity、nullable activityId。相同返回CreateOrderResult(existing,false)；不同返回409。相同重试不再检查活动时间或扣数量，因此活动结束后也能返回原结果。

### 锁活动并检查商品

activityId非空时，activityMapper.findByIdForUpdate(activityId)通过SELECT FOR UPDATE锁住活动行。查不到404；活动productId与请求productId不同409。这里只检查关联并加锁，还未减少名额。

随后productMapper.findById读取商品，名称和价格从这里取得，用于订单快照。

### 扣商品库存，再按当前时间扣名额

productMapper.deductStock执行：

```sql
UPDATE product SET stock = stock - #{quantity}
WHERE id = #{id} AND status = 'ON_SALE' AND stock >= #{quantity}
```

成功时事务内部商品库存10→8；返回0则409。该UPDATE也可能等待商品行锁。

成功返回后才取LocalDateTime.now().withNano(0)，再调用activityMapper.deductQuantity：

```sql
UPDATE activity SET remaining_quantity = remaining_quantity - #{quantity}
WHERE id = #{id} AND remaining_quantity >= #{quantity}
  AND starts_at <= #{now} AND ends_at > #{now}
```

10:50在活动范围内且名额5足够，成功后名额5→3。若返回0，抛409，前面的库存扣减一起回滚为10；不会留下库存扣了但订单没生成的部分结果。

先锁活动再更新商品，归还也采用活动→商品顺序。时间在这两个锁等待结束后取得，避免等待期间已结束还使用旧时间判断。

### 创建并保存订单

Service创建TradeOrder：userId=2、productId=1、activityId=10，保存商品名称、unitPrice=20、quantity=2、totalAmount=40、status=PENDING_PAYMENT、createdAt=10:50、expiresAt=11:05和requestId。

orderMapper.insertOrder执行订单INSERT，数据库主键回填为假设的100。事务提交使商品扣减、名额扣减、订单插入一起生效，锁随事务结束释放。

### Controller形成响应

Service返回CreateOrderResult(order,true)。Controller使用created判断201还是200，用OrderResponse.from(result.order())取出响应字段，再交给Spring序列化JSON。CreateOrderResult本身不是接口正文。

成功后状态为待支付、金额40、库存8、名额3。普通订单activityId=null，跳过活动锁和名额操作，其他流程沿用。

## 四、用户查询已创建订单

GET /api/orders/100 → OrderController.getOrder → OrderService.getUserOrder → OrderMapper.findOwnedOrder(id,userId)。SQL用订单编号和认证用户编号共同筛选，不能查别人的订单。

数据库activity_id通过映射进入TradeOrder.activityId，OrderResponse.from再把它放进响应。本人列表和管理员分页也都映射了该列。若漏SELECT这个列，取消时可能误把活动订单当普通订单，因此不能只改Java字段。

## 五、分支A：用户支付

POST /api/orders/100/pay，无JSON正文。Controller从路径取订单编号、从认证身份取userId，调用OrderService.payOrder。

Service先SELECT FOR UPDATE锁住本人订单。已PAID直接返回原订单，避免重试改变支付时间。否则取当前时间，markPaid要求status=PENDING_PAYMENT且expires_at > now，成功改为PAID、记录paid_at；失败409。

这里不检查活动endsAt。假设11:02支付，活动虽在11:00结束，但订单11:05才截止，因此可以成功。11:05及之后首次支付会失败。

支付不再扣商品或活动名额。最终金额40、库存8、名额3；这是模拟支付状态记录，没有接入真实收款。

## 六、分支B：用户主动取消

支付和取消是同一笔待支付订单的不同分支，不是支付成功后继续取消。

POST /api/orders/100/cancel → OrderController.cancelOrder → OrderService.cancelOrder。

1. 锁定本人订单；已CANCELED直接返回原结果。
2. markCanceled条件UPDATE，要求仍PENDING_PAYMENT；成功改CANCELED并记录ended_at，失败409。
3. activityId非空，restoreQuantity把activity.remaining_quantity加回购买数量，3→5。
4. restoreStock把product.stock加回购买数量，8→10。
5. 重新查询本人订单，返回新状态；同一事务提交上述三个更新。

即使活动已结束，也归还名额，但时间条件继续阻止新下单。待支付订单过期但尚未自动关闭时仍可主动取消。

归还SQL本身是加法，防重复的关键在前面的订单状态转换。重复取消提前返回；已PAID或CLOSED不能改为CANCELED。如果库存归还失败，状态更新和名额归还一起回滚。

最终金额仍40、状态CANCELED、库存10、名额5。金额保留交易快照，不改成0。

## 七、分支C：一直不支付，超时关闭

OrderSchedulingConfiguration启用调度，OrderTimeoutScheduler.closeExpiredOrders每轮结束后默认等待30秒再执行下一轮，每轮最多100个候选。

扫描调用findExpiredOrderIds，查status=PENDING_PAYMENT且expires_at <= cutoff的订单编号。这查的是订单支付截止时间，不是活动结束时间。

找到100后，调度器跨Bean调用OrderService.closeExpiredOrder(100,cutoff)，为这一单开启事务：

1. markClosed再次要求PENDING_PAYMENT和已到期，成功改CLOSED并设置ended_at；返回0则false，什么都不归还。
2. 成功后findById读取订单，取得activityId、productId、quantity。
3. 归还活动名额3→5，再归还商品库存8→10，同一事务提交。

候选扫描不保证稍后状态仍未变化，所以必须再次条件UPDATE。支付、取消或其他扫描已经处理的订单不会重复归还。

订单11:05到期，可能11:05之后某轮扫描才实际关闭；支付接口从到期时起已拒绝首次支付，不必等待扫描。最终状态CLOSED，金额40、库存10、名额5。

## 八、各分支的最终结果

| 阶段或分支 | 订单状态 | 金额 | 商品库存 | 活动剩余名额 |
|---|---|---:|---:|---:|
| 只创建活动 | 尚无订单 | — | 10 | 5 |
| 买2件成功 | PENDING_PAYMENT | 40 | 8 | 3 |
| 从待支付成功支付 | PAID | 40 | 8 | 3 |
| 从待支付主动取消 | CANCELED | 40 | 10 | 5 |
| 从待支付超时关闭 | CLOSED | 40 | 10 | 5 |

普通重复请求不会重复扣数量；取消和关闭竞争只能成功一种终态，两份数量只归还一次。活动结束本身不会自动修改库存或释放待支付订单占用，释放由取消或订单超时关闭决定。

## 源码阅读入口

1. activity/CreateActivityRequest.java → AdminActivityController.java → ActivityService.java → ActivityMapper.java / XML。
2. activity/ActivityController.java → getActivity / findById。
3. order/CreateOrderRequest.java → OrderController.createOrder → OrderService.createOrder → 两种数量UPDATE与订单INSERT → OrderResponse.from。
4. OrderController.payOrder/cancelOrder → 对应Service方法与状态SQL。
5. OrderTimeoutScheduler → OrderService.closeExpiredOrder → 状态更新与双归还。

本文为源码讲解，没有新增业务或重新运行测试。实现阶段的166个测试结果、证据和边界见第九阶段主文档。
