# Vue前端第三阶段：购物车、地址与结算

实施日期：2026-11-19。基于第二阶段继续开发，保留已有未提交内容，本次未提交或推送。使用现有Vue3、TypeScript、Pinia和fetch，不增加依赖，不改Java业务与数据库结构。

## 本阶段交付

商品卡片的“选购 / 保存到购物车”进入受保护的/purchase/:productId。用户填写目标数量，可直接下单，也可把购物车中该商品数量设为这个值。PUT接口设置最终数量，不是累加。活动详情增加“参加活动下单”，携带activityId进入同一选购页。

/cart支持本人购物车读取、保存数量、移除、勾选和多商品结算。下架或当前库存不足的商品不允许勾选；所选商品存在未保存的数量修改时禁止结算。最多选择50种商品，数量按Java Integer范围检查。预估金额使用已保存的购物车数量，最终金额以服务端订单为准。

/addresses支持创建、编辑、删除与设置默认地址。输入按当前Java DTO校验，首个地址自动成为默认地址，默认地址用于购买页和购物车预选。删除前用浏览器确认框确认。窗口焦点触发身份刷新时，保留地址草稿；账号变化清空地址、草稿与编辑状态。

优惠券能力遵循当前后端边界：购物车checkout只接受productIds、addressId和requestId，不使用活动券；单商品活动购买使用本人领券记录userCouponId。选购页筛选本人AVAILABLE、未过期、属于当前活动、已到使用时间且满足金额门槛的券。数量或时间变化导致券不再可用时，清除选择并提示用户重新确认金额。普通购买不发送优惠券记录。

下单成功进入/checkout/result/:id，重新读取本人订单，展示成交商品、金额、优惠、地址和截止时间。刷新可重读结果，其他用户订单返回404。支付、取消、订单列表和完整履约仍在第四阶段实现。

## 新增运行文件与行数

下表是交付时新增文件总行数。后续修改会改变行号，教学时再读取当前文件。

| 文件 | 新增行数 |
|---|---|
| frontend/src/types/checkout.ts | 24 |
| frontend/src/api/checkout.ts | 12 |
| frontend/src/stores/checkout.ts | 61 |
| frontend/src/components/PendingOrderBanner.vue | 33 |
| frontend/src/views/AddressesView.vue | 107 |
| frontend/src/views/CartView.vue | 106 |
| frontend/src/views/PurchaseView.vue | 130 |
| frontend/src/views/OrderCreatedView.vue | 42 |

## 请求层改动

api/http.ts增加writeJson<T>(path, method, values?, expected)，统一POST、PUT、DELETE的CSRF与JSON发送；204直接按空响应处理，其余读取JSON并再次核对身份代数。原postJson保留为POST包装，因此第二阶段调用方不用改。

HttpError新增path字段，记录失败发生于CSRF接口还是实际订单接口。这用于判断待确认订单是否已经到达后端的幂等查询阶段。新增通用409提示，保留追踪编号。

api/checkout.ts提供cartItems、setCartQuantity、removeCartItem、addresses、createAddress、updateAddress、deleteAddress、setDefaultAddress、readOrder。类型文件对应真实CartItemResponse、AddressResponse、CreateOrderRequest/CheckoutRequest和OrderResponse及其嵌套快照。

## 购买标识与未知结果

stores/checkout.ts通过useCheckoutStore保存pending和busy。pending是购买路径、购买参数及requestId，busy表示当前是否正在提交。generation跟随用户编号变化，用于避免旧账号请求的finally影响新账号的忙碌状态。

提交前生成crypto.randomUUID()，把原参数和标识保存到sessionStorage，键为limited-market:vue-order-intent:用户编号。同一浏览器标签页刷新后恢复；关闭标签页等会话存储生命周期结束后，不保证保留。这不是跨设备订单恢复功能。

submit先禁止并发重复提交，并检查已有待确认购买是否与本次路径和参数一致。持久化失败时不发送订单请求。POST成功后清除购买标识；网络未知、服务器错误、身份变化等继续保留。retry使用原路径、参数和标识，不自动循环重试。

首次购买收到明确4xx时允许清除；已有未知结果在重试时若被认证、CSRF或限流提前拒绝，保留原标识。当前订单业务404/409在用户锁内幂等查询之后产生，只有失败path确实为原订单接口时才按明确失败清除，避免把CSRF接口拒绝误判为订单不存在。

2026-11-24 更新：下单回执丢失后，结算模块使用原参数和原请求标识自动恢复。浏览器重新连接或恢复登录时继续处理本账号的待确认请求。布局会显示处理状态或已恢复的订单链接。待确认期间禁用新购买与购物车变更，切换账号后按账号读取各自的待确认请求。

## 页面主要变量与流程

AddressesView：list保存地址；editing保存当前编辑的地址编号；form保存六个地址字段；busy控制读取，saving控制写操作，error与notice分别显示失败与成功。load读取列表，edit复制地址到表单，save校验并调用创建或更新，remove确认后删除，mutate复用写操作后的反馈与重新读取。sequence处理读取先后，operation处理写操作与账号变化/销毁。

CartView：items是已保存购物车，quantities是可编辑数量草稿，selected保存勾选商品编号，addressList和addressId保存地址选项。estimate根据已保存数量估算；unsaved检查所选商品草稿是否与保存值不同；blocked统一限制读取、写入、身份确认和待确认订单期间操作。load更新服务端数据时保留尚未保存的草稿；update执行改量或移除；submit将排序后的productIds和地址交给checkout.submit。

PurchaseView：product、event、owned保存商品、可选活动及已领券；quantity、addressId、userCouponId是本次选择；original、usable、discount、activityOpen计算预估金额、可用券与活动可购买状态。load并行读取商品、地址、活动和本人券，校验活动是否属于该商品。saveCart设置最终数量；submit把商品、数量、活动、用户领券记录和地址交给checkout.submit。每秒更新时间只用于状态判断，不轮询后端；销毁时停止定时器。

OrderCreatedView：load从路由取得订单编号，调用readOrder后展示真实成交结果。各受保护页面在账号变化时清空用户数据，并在身份重新明确后重读；旧响应仍受HTTP身份代数与组件加载编号共同限制。

## 既有页面与布局改动

router/index.ts增加cart、addresses、purchase/:productId、checkout/result/:id四个受保护路由。ProductsView增加选购入口，ActivityDetailView增加进行中的活动购买入口，AppLayout增加购物车/地址导航和PendingOrderBanner。

AppLayout在已持有用户身份、正进行焦点身份确认时保留当前组件，并由页面busy状态禁止写入，避免临时loading导致表单组件销毁、草稿丢失。身份读取失败仍进入明确失败分支，匿名不能显示受保护内容，实际权限由后端继续校验。

main.css增加原生表单、购物车行、地址编辑器、购买表单、结果页和待确认提示样式，并适配390像素窄屏。package.json的test命令改为执行tests/*.test.ts，新旧测试一起运行。

## 总体调用链

购物车：ProductsView选购链接 → PurchaseView.saveCart → api/checkout.setCartQuantity → http.writeJson(PUT) → CartController.setQuantity → CartService.setQuantity。CartView.load再调用cartItems读取保存数量。

地址：AddressesView.save → mutate → createAddress/updateAddress → writeJson → AddressController.createAddress/updateAddress → AddressService对应方法 → 成功后load刷新。

活动购买：ActivityDetailView活动入口 → PurchaseView.load → 选择数量、地址、本人券 → PurchaseView.submit → useCheckoutStore.submit('/api/orders') → postJson/writeJson → OrderController.createOrder → OrderService.createOrder → 保存订单/商品/地址/券快照 → 返回订单 → OrderCreatedView.load。

购物车结算：CartView.submit → useCheckoutStore.submit('/api/orders/checkout') → OrderController.checkout → OrderService.checkout → 保存成交快照并清除选中购物车项 → 结果页。未知结果则经PendingOrderBanner.retry → checkout.retry → 原参数和原requestId重新提交，服务端返回已有订单。

## 验证证据与边界

Node24.13.0/npm11.6.2运行20项前端测试全部通过，包含原15项及新增5项：未知结果重建store后复用原参数/标识、不同购买被阻止与账号隔离、首次及重试CSRF拒绝的不同保留策略、迟到成功不能确认已切换身份的购买、PUT/DELETE带CSRF且接受204。类型检查与Vite生产构建通过，git diff --check通过。最终入口JS约77.96KB，CSS约52.78KB，其余公共与路由块另计。

真实Chrome通过Vite5173访问临时Java18739，显式指定limited_trade_platform_test，关闭Agent、消息消费、定时任务和缓存，未启动Redis/RabbitMQ。后端使用既有JAR；本次没有Java改动，不重跑无关Java全套测试。

实际浏览器与数据库检查包括：

- 游客访问购物车要求登录；地址创建、编辑、删除、首个默认与切换默认均真实落库，非法输入被前端阻止。
- 焦点身份刷新保留地址草稿。复核发现购物车草稿会被自动刷新覆盖，已先复现草稿3变回保存值100，再修正load合并草稿，浏览器回归通过。
- 活动购买数量1不满足满100门槛，数量2出现已领可用券；真实订单原价199.00、优惠10.00、应付189.00，提交的userCouponId是本人领取记录编号。
- 修改地址簿后，原订单仍显示成交地址；刷新结果页仍正确。
- 购物车保存、改量、移除与勾选生效，未保存数量禁止结算；两种商品3件及2件合计497.50。
- 在后端已创建购物车订单后，浏览器拦截并丢弃成功响应，真实制造未知结果；刷新后恢复原标识，重试CSRF403仍保留。退出A登录B无旧购买/地址/购物车；B查询A订单及修改A地址均404。切回A重试使用完全相同参数与requestId，返回200及原订单，购物车已清空。
- 普通单商品无券订单应付99.50。数据库按用户和requestId确认三笔最终订单各只有一条，金额分别189.00、497.50、99.50。
- 页面读取后再把本次测试商品库存改为0，提交得到真实409；页面读取后删除所选地址，提交得到真实404。两种情况没有生成订单，也没有残留待确认标识。仅操作本次测试商品，随后恢复库存。
- 库存不足的购物车项不能勾选；真实服务端退出后提交触发401并清理受保护页面。
- 390像素地址、购物车、选购和结果页无横向溢出；桌面购物车和手机结果截图已检查，浏览器pageErrors为空。

构建产物另在4173预览，验证登录保护、地址/购物车/选购路由、无地址禁用提交、其他用户订单404和390像素布局，pageErrors为空。丢失响应与CSRF403是受控网络故障；业务成功、冲突、权限和快照走真实后端。

测试使用随机分类、商品、活动、券和账号。早期浏览器脚本修正过带默认徽标的标题定位，以及等待网络请求结束的时机；这些脚本问题修正后才完成上述成功验收。各轮按确切测试ID和随机名称清理，最终一轮包括5个账号、3张订单及相应明细、券占用、地址和购物车，清理已验证；开发库对应账号数量为0。自增ID间隔保留，不重置数据库序列。

临时证据位于被忽略的tools/vue-checkout-*和tools/screenshots/vue-checkout-*。临时浏览器、5173、4173和18739实例在验证后停止。

## 讲解安排

短文件合并讲类型、API和HTTP改动；购买意图store单独讲，随后讲地址、选购、购物车与结果。带当前文件路径和行号，说明各段TS/JS和主要变量；HTML/CSS只概括，SQL和测试源码不讲。

第四阶段继续本人订单列表、完整详情、模拟支付、取消与确认收货。本阶段先学习，不自动进入第四阶段。
