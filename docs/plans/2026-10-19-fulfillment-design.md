# 第十六阶段设计：订单履约

2026-10-19。实现和验收完成：全量417项通过，当前进入新增源码学习。用户确认第十五阶段已理解，明确要求进入下一阶段；沿用自行设计实现验收后讲新增源码的方式，不逐项等待设计确认。

## 目标与边界

实现管理员查看订单详情并登记整单发货，买家查看发货信息及确认收货，形成PAID→SHIPPED→COMPLETED。整单只有一份发货记录，不拆包、不对接物流API、不验证真实承运或签收，不增加退款/自动收货/消息/依赖。

现有CART有地址快照，DIRECT及历史订单可能没有地址。仅已支付且已有地址快照的订单可发货；无快照409，不从用户当前地址簿猜地址，不为历史补造数据。本阶段不改变CreateOrderRequest，不扩展原单商品地址输入。

保留当前分支和第十五阶段全部未提交改动；不提交推送、不新建工作树、不新增下载，不重跑001—011或重试被拒绝的临时HTTP进程。

## 接口与状态

- GET /api/admin/orders/{id}：管理员订单详情，返回OrderResponse，含该订单的地址快照及shipment；只新增有明确履约目的的ADMIN访问，不扩大普通用户范围。管理列表仍不返回地址。
- POST /api/admin/orders/{id}/ship：ADMIN+CSRF，ShipOrderRequest的carrier、trackingNo均必填非空白，分别最多50、64字符，保存前strip并拒绝归一化后为空（包括Unicode空白）；仅登记文本，不限定真实物流格式。首次PAID且有地址→SHIPPED，200及订单详情。
- POST /api/orders/{id}/receive：本人+CSRF，无正文；SHIPPED→COMPLETED，记录receivedAt，200及订单响应；非本人或不存在404。
- 相同发货内容重试SHIPPED或COMPLETED订单返回当前结果并保留shippedAt/receivedAt；变更carrier或trackingNo复用已发货订单409，不提供修改/二次发货入口。
- 确认已完成订单幂等返回；待支付、已支付、取消、关闭的订单不能确认，409。
- 既有pay仍仅PAID重复幂等，SHIPPED/COMPLETED再pay409；取消和超时仍仅待支付，不改变履约状态或库存。
- 管理分页筛选允许SHIPPED、COMPLETED。新状态不影响原四种状态语义，ended_at仍仅取消/关闭时间，不复用为收货时间。

## 数据与文件

sql/012_order_shipment.sql：创建order_shipment，order_id BIGINT主键/外键trade_order ON DELETE CASCADE；carrier VARCHAR(50)、tracking_no VARCHAR(64)、shipped_at DATETIME非空，received_at DATETIME可空。增加received_at为空或>=shipped_at的CHECK。无旧数据回填；仅更新主表status列注释，所有现有业务值不改。双库各执行一次。

order/ShipOrderRequest、OrderShipment、OrderShipmentResponse、OrderShipmentMapper及XML为新文件。TradeOrder增加shipment装配字段；OrderResponse/AdminOrderResponse追加可空shipment，响应不公开shipment内部orderId。loadDetails增加一次按订单编号集合批量查询，无逐订单SQL。

OrderMapper增加findByIdForUpdate、markShipped、markCompleted；状态更新带PAID/SHIPPED原状态条件。OrderShipmentMapper.insertShipment、markReceived、findByOrderIds。OrderService增加getAdminOrder、shipOrder、receiveOrder。Controller沿用现有/admin角色与/orders本人入口。

## 事务与并发

发货：锁订单主行→装配不可变快照/发货记录→判断重试或PAID/地址条件→条件改状态→插入发货记录→重新装配响应。同一本地事务提交；插入失败必须回滚状态。

收货：按id+userId锁订单主行→判断COMPLETED重试或SHIPPED→条件改状态→更新发货记录received_at→重新装配响应。同事务；第二个SQL失败回滚状态。时间在取得订单锁后读取；receivedAt不早于shippedAt。

两种写入以订单行锁串行，同单发货/收货/支付/取消/超时不会绕过状态机；不取得库存/活动锁，不修改任何库存、购物车、地址簿、Outbox或Redis。订单状态和发货记录一次性提交，列表/详情原REPEATABLE_READ事务批量读取保持。

## 验收

API覆盖ADMIN权限/CSRF/本人隔离/非法输入、地址前提、两新增状态过滤、快照和重复请求保留时间。真实MySQL并发同值发货仅一记录、异值发货一成功一冲突、重复收货一次完成、发货与取消/收货竞争合法。受控发货插入和收货更新SQL失败实际回滚，原库存/活动/购物车/地址/Outbox/缓存保持。重新登录能读取已保存履约数据。

保存开发十表公开字段基线；012双库执行一次并核对结构；全量离线package，不冒充此前381项为本轮结果。完成独立只读复核和数据/测试十一表/触发器/随机Redis与MQ资源/JAR边界核对；只停止本轮自有保活进程，保留共享服务。
