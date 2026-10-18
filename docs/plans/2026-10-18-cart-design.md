# 第十三阶段设计：用户购物车

日期：2026-10-18。

状态：设计及计划均获用户确认，2026-10-18实施完成。专项30项、完整离线package303项通过，0失败/错误/跳过；数据基线及清理检查完成，未提交或推送。

## 1. 目标与已核对依据

为登录用户保存商品购买意向，支持查看、设置数量和移除。衔接已实现的商品分类/筛选及后续多商品结算。购物车数据保存到MySQL，退出登录不删除，再次登录可继续读取。

已核对的现有接口：OrderController使用@AuthenticationPrincipal PlatformUserDetails取得user.getId()；CreateOrderRequest一次传productId、quantity、requestId和可选activityId。ProductMapper.findById读取当前商品信息；SecurityConfig默认CSRF保留、订单路径要求登录、其他未声明路径拒绝。

购物车是新增模块，采用书面设计后再制定实现计划的流程。沿用单Spring Boot应用、MyBatis和现有数据库连接，无新增依赖、服务或缓存。保留既有未提交改动，不提交、推送、合并或新建工作树。

## 2. 方案与选择

| 方案 | 取舍 |
|---|---|
| MySQL持久化、PUT设置绝对数量（采用） | 用户/商品唯一约束清晰；单条UPSERT原子写入，相同请求重试不累加；适合现有技术与正确性验证 |
| POST累加购买数量 | 符合“再加一件”的操作，但网络重试需要额外请求幂等机制；当前没有必要引入 |
| Redis保存购物车 | 可实现，但需要另行考虑持久化、过期与恢复，当前没有容量证据要求此方案 |

接口设置最终数量，界面未来可以把“加一”转换成设置新数量。本阶段不引入版本号或合并多设备编辑；不同数量的并发请求由数据库写入顺序决定最终值，不承诺按网络到达顺序覆盖。

## 3. 业务规则

1. 所有购物车接口要求登录；用户编号只从PlatformUserDetails获取，不由请求正文、路径或查询参数决定。
2. 一位用户对一件商品只有一条购物车记录。不同用户可保存同一商品，各自数量独立。
3. PUT表示将数量设置为指定正整数；不存在记录时新增，存在时覆盖数量，重复相同请求不累加。成功统一返回204，无响应正文。
4. 数量使用Java Integer及MySQL INT；@NotNull与@Positive校验，拒绝0、负数、小数及超出Integer范围的JSON值。不增加任意的99件等上限。
5. PUT时商品必须存在且当前查询状态为ON_SALE：不存在404，下架409。
6. 购物车不占用或扣减库存，也不保证可买数量。允许保存超过当前库存的意向数量，包括在售但当前库存0的商品；GET展示当前库存，正式下单时仍按MySQL检查、扣减。商品状态在检查后可能变化，购物车不锁定或保证商品持续在售。
7. 已有记录关联的商品后来下架，GET仍展示该记录及当前状态，允许移除；对下架商品重新PUT数量返回409，保留原记录。重新上架后可设置数量。
8. DELETE仅按当前用户和商品编号删除，记录不存在也返回204。路径指向别人也买过的商品不会删除别人的记录。
9. 分类、名称、价格、库存变化不重写购物车记录；GET连接商品表读当前信息。保存的quantity是意向数量，price不是订单价格快照，也不是价格承诺。
10. 当前普通/活动订单接口继续沿用原实现，本阶段不自动从购物车创建订单或下单后清空购物车。活动购买不进入当前购物车记录；后续结算另行设计。

## 4. 拟新增数据结构

以下均为新建标识，不是已有表。

迁移文件：sql/009_cart_item.sql。对开发库和测试库各执行一次，创建空表，不回填或修改原有用户、商品、分类、订单、活动或Outbox。

表cart_item：

| 字段 | 类型与约束 | 含义 |
|---|---|---|
| user_id | BIGINT NOT NULL，外键app_user(id) | 所属用户 |
| product_id | BIGINT NOT NULL，外键product(id) | 商品编号 |
| quantity | INT NOT NULL，CHECK quantity > 0 | 意向数量 |
| created_at | DATETIME NOT NULL，默认CURRENT_TIMESTAMP | 首次加入时间 |
| updated_at | DATETIME NOT NULL，默认及ON UPDATE CURRENT_TIMESTAMP | 最近变更时间 |

PRIMARY KEY(user_id,product_id)直接保证一用户一商品一记录，不再增加无用途的自增编号。显式建立product_id索引支持商品外键；不设置级联删除，不保存商品名称/价格/库存副本。

按user_id查询、按product_id升序展示；空购物车返回[]。当前不增加购物车分页、勾选状态、批量清空或商品规格结构。

## 5. 接口及响应

### GET /api/cart/items

认证用户获取自己的购物车。成功200，返回数组。每项字段固定为productId、name、price、stock、status、quantity；price沿用BigDecimal，字段名与现有Product一致。

通过cart_item连接product查询，WHERE c.user_id=#{userId}，ORDER BY c.product_id ASC；不按ON_SALE筛掉下架项。现有商品没有物理删除接口，外键保证关联存在，无需为不可能的缺失连接添加伪商品或默认数据。

### PUT /api/cart/items/{productId}

请求正文：

```json
{"quantity": 3}
```

路径productId必须为正。正文仅定义quantity；Service使用认证用户编号和路径商品编号，不信任请求额外提供的用户标识。ADMIN也只能维护自身购物车，没有跨用户管理入口。

成功204；非法输入400；未知商品404；当前下架409。未登录请求仍由现有Security处理；写入还需有效CSRF，未提供CSRF时可能先被过滤器拒绝，不能仅凭匿名状态承诺所有写请求必定401。

### DELETE /api/cart/items/{productId}

路径productId必须为正。WHERE user_id=#{userId} AND product_id=#{productId}，成功或本人记录不存在均204；需要登录和CSRF。不额外查询商品，不要求商品仍在售。

## 6. 调用链与并发

```text
PUT
  → CartController取登录用户编号，校验路径和quantity
  → CartService事务中调用现有ProductMapper.findById
  → 检查存在与ON_SALE
  → CartItemMapper执行单条INSERT ... ON DUPLICATE KEY UPDATE
  → 提交，返回204

GET
  → CartController取登录用户编号
  → CartService查询本人记录
  → CartItemMapper连接商品表读取当前字段
  → 返回CartItemResponse数组

DELETE
  → CartController取登录用户编号并校验商品编号
  → CartService按本人+商品删除
  → 返回204
```

拟用UPSERT更新语句的quantity仍使用绑定参数，不使用累加表达式；数据库主键及单条写入负责同键并发原子性，不先查购物车再决定INSERT/UPDATE。不同数量并发SET后的数量必须是某个成功设置的值；同键不重复、不把数量相加。

PUT与DELETE并发时由数据库行锁及唯一性约束串行处理，最终允许“记录不存在”或“存在最后生效的设置值”，不能影响其他用户。不为这些写操作增加整个用户的全局串行锁，也不持有库存写锁。

只有写事务有数据库变更，不调用ProductCache.evictAfterCommit，不发送MQ消息。购物车读当前商品信息直接访问MySQL，不使用商品列表Redis缓存。

## 7. 拟新增文件及现有改动

Java新增包com.elaina.limitedtrade.cart：

| 文件 | 职责 |
|---|---|
| SetCartItemRequest.java | quantity的HTTP边界校验 |
| CartItemResponse.java | productId/name/price/stock/status/quantity查询结果record |
| CartController.java | GET/PUT/DELETE，认证用户取号与参数校验 |
| CartService.java | 商品状态校验、写事务、本人购物车操作 |
| CartItemMapper.java | findUserItems(userId)、setQuantity(userId,productId,quantity)、deleteItem(userId,productId) |
| mapper/CartItemMapper.xml | 连接查询、原子UPSERT和带用户归属条件删除 |
| sql/009_cart_item.sql | 组合主键、外键、数量约束及必要索引 |
| docs/learning/13-cart.md | 新增源码及调用链讲解，不展示测试源码 |

修改SecurityConfig，仅精确声明/api/cart/items及其子路径需要认证，保留现有CSRF和管理员路径规则。README补迁移、接口与真实验证记录。

测试位于对应cart包。已有会真正提交用户/商品夹具的测试先核对清理方式；新表无需旧业务主动写入，若夹具有购物车则必须先清理自有cart_item，再删商品/用户，不使用级联删除掩盖所有权。

## 8. 成功标准与证据边界

1. 首次PUT新增记录；再次PUT覆盖数量；重复同值PUT不累加；GET读取本人记录。
2. 不同登录用户无法查看、覆盖或删除他人的记录；伪造额外userId不改变归属。匿名、非法参数及写入CSRF规则正确。
3. 连接查询展示当前名称、价格、库存、状态；下架项保留，设置下架商品失败但可删除。
4. 不存在商品404，非法数量/编号400；失败请求不写购物车；多于库存/库存0仍能保存意向且库存不变。
5. 多线程设置同一用户/商品，最终只有一行；同值不累加；不同值为合法设置值；设置/删除竞争不影响他人。
6. 购物车变更不改商品库存、不创建订单/Outbox；现有购物车不因旧下单接口自动删除。
7. 隔离事务回滚、测试自有夹具清理正确；开发库原六表公开字段基线保持，新增cart_item为空；测试七表均清理。
8. 运行新增专项和一次完整离线package，实际读取结果后报告数量。上一阶段273项是历史基线，不预先当作本阶段通过结果。
9. 独立只读复核聚焦用户归属、SQL原子性、事务和权限。JAR不含实际凭据与测试工具。不新增安装、下载或外部发布动作。

验证范围分别记录为MockMvc、真实MySQL、已有Redis/RabbitMQ回归及构建打包；实际HTTP若没有执行，不称为通过。若再次发生自动审批拒绝，记录拒绝动作与工具原因，不变换方式绕过。

## 9. 教学与执行约定

用户希望由助手完成当前阶段实现，再按新增代码逐段讲解；旧业务只说明到新增点，不讲测试源码。先审阅本设计，随后制定可执行计划；不在设计阶段创建业务代码或执行迁移。

本设计自检及实施完成：接口设置数量语义、非法输入、下架与库存边界、用户隔离、并发及清理责任均按此实现；真实结果见README及docs/learning/13-cart.md。
