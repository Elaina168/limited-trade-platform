# Vue前端第四阶段：本人订单管理

实施日期：2026-11-20。沿用第三阶段的身份、CSRF、订单类型和购买标识，交付本人订单列表、状态筛选、完整详情、模拟支付、取消和确认收货。不新增依赖，不修改Java业务或SQL结构，不自动提交或推送。

## 业务行为与边界

/orders显示本人订单，每页10笔，支持全部及六种状态筛选：待支付、已支付、已取消、已关闭、已发货、已完成。切换筛选回到第一页；编辑选择和已提交筛选分开，翻页沿用已提交条件。列表有加载、空结果、失败重试与分页。

/orders/:id显示服务端保存的成交商品、单价、数量、金额、收货地址、优惠券、物流和时间。下单成功与原参数重试均进入新详情；第三阶段/checkout/result/:id保留，通过薄包装共用OrderDetailView，避免两套展示。

待支付且本机时间未到支付截止时显示模拟支付和取消；已过截止但服务器仍为待支付时仅允许取消，不把本机时间到期直接显示为已关闭。已发货才显示确认收货。模拟支付不接入真实支付。取消和确认收货前要求用户确认；所有最终状态/时间条件由后端事务判断。管理员发货页面仍属于第五阶段，本次只通过已有管理员接口准备收货测试数据。

订单写操作期间禁止重复点击和相互冲突的动作。操作失败或响应未知后，保留原订单信息但禁用后续写操作，要求先GET刷新确认；不会自动重发POST。成功响应直接更新当前订单。焦点身份刷新与写操作交错时，用读取编号和写操作编号分别防止旧响应覆盖新结果。

## 新增文件与交付行数

| 文件 | 新增行数 |
|---|---|
| frontend/src/api/orders.ts | 9 |
| frontend/src/utils/orders.ts | 14 |
| frontend/src/views/OrdersView.vue | 64 |
| frontend/src/views/OrderDetailView.vue | 101 |

## 类型、请求及共享状态改动

frontend/src/types/checkout.ts增加OrderStatus、OrderAction、OrderPage，并把Order.status约束为真实的六种状态。

api/orders.ts的listOrders(page,status)构造page/size=10/可选status，使用readJson；changeOrder(id,action)使用postJson发送本人订单的pay/cancel/receive操作，自动获取当前CSRF。

utils/orders.ts集中保存orderStatusLabels与orderActionLabels，availableOrderActions(order,now)按照服务器状态和支付截止时间返回允许展示的动作数组。时间只影响前端操作提示，不模拟后台自动关单。

stores/checkout.ts增加confirmKnownOrder(order)。只在当前账号确实存在pending，且本人接口返回订单的requestId与pending一致时清除。列表只检查实际返回的items；空页、无关订单或另一个账号不清除原购买记录。详情读取或操作成功也可确认。未知创建既可原参数重试，也可通过实际查到订单解除。

## OrdersView主要代码流程

auth与checkout是身份/购买store。status是当前选择，appliedStatus是提交的筛选；page当前页，result后端分页，busy读取状态，error失败提示，sequence加载编号。

load先清空旧分页并标记加载，调用listOrders；只有最新请求才能更新result。对实际返回的订单调用confirmKnownOrder，失败展示错误，身份变化导致的旧请求失效不当成普通失败。search保存筛选并回到第一页，changePage翻页。账号编号变化立即清空结果与筛选，身份确认后重新加载；销毁后使旧请求失效。

## OrderDetailView主要代码流程

order保存完整订单；busy表示正在GET；acting保存正在执行的pay/cancel/receive；refreshRequired表示必须先重新GET才能继续写操作。error与notice分别显示错误与操作提示。now/timer每秒更新前端时间，不轮询服务器；secondsLeft/countdown显示支付剩余时间，actions计算可用动作，blocked集中禁用条件。

sequence管理GET先后，operation管理写操作与路由/账号变化。reset在订单编号或账号变化时立即清空记录、动作和提示，并作废旧任务。load读取route.params.id，检查编号，调用readOrder，成功后更新订单、确认匹配购买并解除refreshRequired。失败时显示错误并要求重新读取。

act先检查动作与忙碌状态，取消/收货再询问确认。开始时记录operation并作废旧GET，随后调用changeOrder。当前操作成功后再次作废并发GET、结束其busy状态，用响应替换订单并显示结果；失败也作废旧GET，设置refreshRequired并要求刷新。finally仅允许仍有效的操作清除acting。组件销毁时停止定时器，使旧读写结果失效。

特别修复：如果支付期间窗口焦点触发新的GET，支付响应使GET失效后，GET的finally不能再清busy。实际浏览器先复现支付成功但刷新按钮永久禁用，再在操作成功/失败路径同时清理被作废的读取busy；修复后按钮恢复，迟到GET也不能把已支付覆盖为待支付。

## 既有文件与界面改动

- OrderCreatedView.vue改为5行薄包装，旧路径共用完整详情。
- router/index.ts新增受保护的/orders和/orders/:id。
- AppLayout.vue增加我的订单导航。
- PurchaseView、CartView、PendingOrderBanner的成功跳转目标改为/orders/{id}。
- main.css增加订单卡片、列表底部和详情的窄屏样式。
- README、阶段计划与roadmap同步进度；测试增加订单API/动作规则和本人订单确认购买的覆盖。

HTML负责显示状态标签、成交快照与按钮，CSS负责布局，不包含额外业务规则。

## 完整调用链

列表：OrdersView.load → api/orders.listOrders → http.readJson → OrderController.listOrders → OrderService.listUserOrders → 分页结果 → result → checkout.confirmKnownOrder。

详情：OrderDetailView.load → api/checkout.readOrder → OrderController.getOrder → OrderService.getUserOrder → OrderResponse → order及允许动作。

操作：OrderDetailView.act → api/orders.changeOrder → http.postJson/writeJson（CSRF）→ OrderController.payOrder/cancelOrder/receiveOrder → OrderService同名方法 → 返回实际订单状态 → 更新详情。失败时先OrderDetailView.load重新确认，不自动重发操作。

未知创建的查询确认：第三阶段checkout.pending保留 → OrdersView或OrderDetailView获取本人实际订单 → confirmKnownOrder匹配requestId → 清除该账号pending。其他账号和未命中的分页不清除。

## 验证记录

Node24.13.0/npm11.6.2执行25项前端检查全部通过，包含原20项及5项新增检查：列表参数与可选状态、三个动作的CSRF及POST地址、精确支付截止边界和六种状态动作、仅匹配已知订单清除pending、其他账号不能清除原账号待确认购买。vue-tsc、Vite构建与git diff --check通过。最终入口JS约78.61KB、CSS约53.21KB，其余公共块和路由块另计。

独立只读复核核对后端规则和异步边界，除已修复的busy竞态外未发现额外实质问题。

Chrome通过5173访问真实临时Java18739，明确指定limited_trade_platform_test并关闭消息消费、定时任务、缓存和Agent。未启动Redis/RabbitMQ。使用已有JAR，Java本次无改动，不重跑无关Java全套测试。

真实/受控证据分开记录：

1. 游客订单路由进入登录；真实创建11张订单验证10+1分页。随后对六种真实状态验证筛选、总数和中文标签。
2. 活动券订单取消后库存恢复、券变回AVAILABLE；重复取消200，库存没有重复归还。重新使用同一券下单并模拟支付，券为USED，重复支付200。
3. 使用本次测试管理员通过既有ship接口登记测试物流；本人详情显示物流，确认收货转COMPLETED，重复收货200。没有实现管理员前端。
4. 真实支付已完成后拦截丢弃成功响应；页面禁用后续动作，手动刷新GET显示PAID，没有自动重发支付。受控CSRF403展示追踪编号，刷新后才重新允许动作。
5. 页面读取后仅把本次测试订单expires_at设为过去，支付得到真实409；刷新后无支付按钮，但尚未关闭仍可取消。CLOSED筛选/展示使用该测试订单的数据库状态夹具，未验收定时自动关单任务。
6. 页面创建订单已落库后丢弃响应，进入订单列表实际查到相同requestId，解除pending且创建POST只有一次。
7. 旧checkout/result路径正确展示同一份完整详情；390像素列表与详情无横向溢出。桌面完成订单与手机详情截图已检查。
8. 换账号后列表为空；其他用户详情与pay/cancel/receive均404；非法编号有明确提示。浏览器pageErrors为空。
9. 受控并发GET/POST复现并修复busy残留，修复后迟到的旧GET不能撤销已支付状态，刷新按钮可用。

最初一轮浏览器脚本等待了按钮存在但没有等待重新启用，断言过早；修正等待条件后清理该轮数据，再完整验收成功。

最终构建产物在4173另用一个随机测试账号验证：普通购买后跳到新详情、取消、旧路径兼容、列表503及重试恢复、外人订单404和390像素布局。pageErrors为空。

最终一轮（含构建预览）4个测试账号、16张订单。数据库按各账号与requestId确认每个购买标识仅一张订单，金额正确；之前中止轮与最终轮均按确切ID和随机名称清理，包含明细、地址、券、购物车、物流、超时outbox与样本商品/活动。开发库对应账号数为0，不重置自增ID。测试数据已清理，临时浏览器与5173/4173/18739实例已停止。

本地忽略证据：tools/vue-orders-browser-summary.json、vue-orders-fixture.json、vue-orders-race.mjs、vue-orders-preview.mjs及screenshots/vue-orders-*.png。

## 学习安排与后续阶段

先合并讲类型、API和状态策略，再讲列表、详情的变量与读写流程；最后讲confirmKnownOrder、旧入口和导航改动。带文件路径及当前行号，重点TS/JS，HTML/CSS仅概括，SQL与测试不讲。

下一阶段为管理员业务页面，当前先学习第四阶段，不自动进入第五阶段，也不自动提交推送。
