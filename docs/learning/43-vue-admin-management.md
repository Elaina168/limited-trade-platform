# Vue前端第五阶段：管理员业务页面

实施日期：2026-11-21。按既定第五阶段接入已有Java管理接口，保留前面阶段未提交内容。没有安装依赖、改变数据库结构或后端业务，没有提交或推送。

## 业务闭环

管理员进入管理中心后，依次维护分类、商品、活动、券和订单。分类支持创建和重命名，重复名称409。商品创建时指定初始库存，初始OFF_SALE；资料编辑只提交名称、价格、分类，库存不可编辑；上下架使用PATCH状态接口。

活动绑定已有商品，填写名称、正整数名额与开始/结束时间；不预留库存。优惠券选择已有活动，填写满减门槛/优惠、发放数量、领取/使用窗口；只能创建和查看规则，本阶段没有增加后端不支持的编辑/删除操作。使用窗口必须在活动内，允许活动前开始领券。

管理订单列表每页10笔，按状态与用户编号筛选。列表记录含userId但没有地址；管理详情返回OrderResponse，有成交地址而不带userId。页面没有猜测两种返回结构。PAID且有地址快照才能登记发货；管理员核对地址，填写承运方和运单号并确认。物流是人工登记，不会向外部物流公司下单。发货后买家在第四阶段页面确认收货，形成管理端与买家端闭环。

## 新增运行文件与交付行数

| 文件 | 新增行数 |
|---|---|
| frontend/src/types/admin.ts | 14 |
| frontend/src/api/admin.ts | 21 |
| frontend/src/utils/admin.ts | 10 |
| frontend/src/composables/useAdminPage.ts | 59 |
| frontend/src/views/AdminCategoriesView.vue | 34 |
| frontend/src/views/AdminProductsView.vue | 52 |
| frontend/src/views/AdminActivitiesView.vue | 39 |
| frontend/src/views/AdminCouponsView.vue | 49 |
| frontend/src/views/AdminOrdersView.vue | 31 |
| frontend/src/views/AdminOrderDetailView.vue | 41 |

## 基础类型、API与校验

types/admin.ts对应真实DTO，AdminProduct在既有Product上补categoryId；ProductInput不含stock，NewProductInput才含初始库存；ActivityInput、CouponInput、ShipmentInput对应创建/发货请求；AdminOrderItem从Order去掉address并增加userId，AdminOrderPage保存分页。

api/admin.ts封装管理商品读取/创建/修改/PATCH状态、分类创建/重命名、活动/券创建、管理订单分页/详情/发货。分类、活动和活动券列表复用现有公共读取接口。http.ts只扩展writeJson的方法类型，增加PATCH，继续使用当次CSRF、Session Cookie和身份版本保护。

utils/admin.ts提供validMoney、validQuantity、validCouponWindow。金额正数且最多8位整数2位小数；数量按Integer范围，商品初始库存允许0，活动/券数量必须大于0。券要求领取和使用起止各自递增、领取结束不晚于使用结束、使用窗口在活动内；日期按LocalDateTime字符串直接提交，不转换UTC。金额输入使用文本加decimal输入模式，保持输入为字符串进行精度校验，再转数字发送JSON。

## useAdminPage共享流程

六个业务页复用同样的读取、提交、权限生命周期。read是当前页面读取函数，resetDraft负责清空本页草稿，describeError允许分类重名等上下文提示。

data是后端完整读取结果的shallowRef，每次整体替换；busy代表读取，saving代表写入，error/notice分别显示错误与提示，needsReview表示未知写结果须先核对。blocked合并读取、写入、身份确认、非ADMIN和待核对状态，统一禁止写操作。

sequence表示最新读取，operation表示最新写入/身份作用域。load在已登录ADMIN时调用read，最新响应才更新data。未知写结果后，成功刷新才解除needsReview并提示核对实际列表。mutate接收具体action、成功文字和可选success回调；执行后作废旧GET，更新提示/草稿，再load读取真实结果。网络、JSON读取或5xx未知结果先禁止继续提交，要求刷新核对，不自动重试POST。明确HTTP拒绝按错误展示。写操作完成时也结束被作废读取的busy，继承第四阶段发现的读写竞态修复原则。

账号或角色变化时同步reset，清空数据、错误、草稿并作废旧读写；同一身份的焦点刷新只读取，不清草稿。组件销毁也使旧结果失效。后端仍负责最终权限和业务判断。

创建商品/活动/券等接口没有购买requestId协议，因此本阶段不声称它们有订单级幂等保证。前端阻止并发和自动重复提交；未知结果需刷新核对实际记录。发货接口自身支持相同物流的重复请求返回原结果，冲突物流409。

## 各页面TS/JS与主要变量

AdminCategoriesView：name是输入，editing为空表示创建否则重命名；edit复制记录，save检查名称后调用createCategory/renameCategory，经mutate统一保存并重置。409使用分类名称已存在的专用提示并保留追踪编号。

AdminProductsView：editing和form保存名称、价格、初始库存、分类。read并行获取全部商品和分类。edit复制现有资料；save检查输入，创建才带stock，编辑严格只提交ProductInput。toggle根据当前status发ON_SALE或OFF_SALE，不直接猜测库存变化。成功后重新读取。

AdminActivitiesView：form保存商品编号、活动名、总名额和起止时间。read并行获取活动和全部管理商品，允许绑定已有下架商品并标明状态；是否可购买仍受上架条件限制。save验证后构造ActivityInput并创建，成功重置草稿。

AdminCouponsView：activityId选择活动；form保存规则输入；read并行获取活动及所选活动券；selected根据编号找到当前活动，用于使用窗口校验。活动选择变化时重置规则草稿并重新load。save检查名称、金额、数量及validCouponWindow，再提交CouponInput。成功保留活动选择，清空规则草稿。

AdminOrdersView：status/userId是正在编辑的筛选，appliedStatus/appliedUser是已提交条件，page是当前页。search验证用户编号并回第一页，changePage保留已提交条件翻页。data为AdminOrderPage，只展示真实列表字段，详情另行读取地址。

AdminOrderDetailView：route读取订单编号；form保存carrier/trackingNo；canShip要求PAID且有地址。read校验编号后调adminOrder；save检查输入长度，确认后shipOrder。路由编号改变时reset并重新load。未知发货响应后blocked禁止重复提交，刷新看到SHIPPED后只展示物流；没有地址时说明不能发货。完整快照、优惠和时间按真实字段展示。

## 修改既有运行文件

- http.ts的writeJson增加PATCH方法类型，其余Session/CSRF逻辑沿用。
- router/index.ts增加/admin/categories、products、activities、coupons、orders、orders/:id六条路由，全部requiresAuth和requiresAdmin。
- AdminHomeView.vue增加五个业务入口；AppLayout.vue增加对应导航，管理中心用exact-active-class避免在子页同时突出，长侧栏允许纵向滚动。
- main.css增加管理原生输入、选择框、卡片间距和窄屏筛选/长导航布局。
- README、roadmap、实施计划及本学习记录同步进度；新增admin.test.ts，不增加依赖。

## 完整调用链

商品维护：AdminProductsView.save/toggle → api/admin.createProduct/updateProduct/updateProductStatus → http.writeJson（POST/PUT/PATCH及CSRF）→ AdminProductController对应方法 → ProductService → 页面mutate成功后load读取。

活动与券：AdminActivitiesView.save → createActivity → AdminActivityController.createActivity → ActivityService.createActivity；AdminCouponsView.save → createCoupon → AdminCouponController.create → CouponService.createCoupon。买家随后通过原有公开活动与券接口浏览/领取，并在PurchaseView下单。

发货：AdminOrdersView.search/load → adminOrders → AdminOrderController.listOrders → OrderService.listAdminOrders；进入详情 → AdminOrderDetailView读取adminOrder → AdminOrderController.getOrder。save → mutate → shipOrder → AdminOrderController.shipOrder → OrderService.shipOrder → 返回/重新读取SHIPPED → 买家OrderDetailView.act('receive') → OrderService.receiveOrder → COMPLETED。

## 验证证据

Node24.13.0/npm11.6.2运行30项前端检查全部通过（原25项和新增5项）。新增覆盖PATCH/CSRF、商品更新不带库存、管理订单筛选与发货路径、管理创建/重命名接口、金额/整数边界、券窗口约束。类型检查、Vite构建和git diff --check通过。最终入口JS约81.01KB，CSS约53.62KB，其余公共块与路由块另计。

独立只读复核核对六页、共享生命周期和后端DTO/服务规则，未发现阻断交付的实质问题。

Chrome通过Vite5173连接临时Java18739，显式指定limited_trade_platform_test；关闭Agent、消息消费、定时任务和缓存，没有启动Redis/RabbitMQ。Java源码无变化，使用既有JAR，不运行无关Java全套回归。

真实业务与受控故障分层记录：

1. 分类真实创建、重名409、重命名；公开列表反映新名称。非法商品表单先阻止提交。
2. 创建商品返回OFF_SALE，公开在售列表不出现；PATCH上架后出现，下架后消失。修改名称、价格、分类，库存仍为初始30，编辑库存输入禁用。
3. 活动起止校验、券折扣/使用窗口越界校验反馈；合法活动与券真实创建，买家公开接口可以读取。
4. 买家通过已有页面领管理端新券、参加活动下单：2件原价59.80、满50减5、应付54.80，并真实模拟支付。
5. 管理员按买家编号查13笔订单，10+3分页正确；按PAID筛选命中3笔。列表没有address字段，详情读取真实成交地址。
6. 物流草稿在同账号焦点刷新后保留。管理页登记发货后显示物流，重复同物流200、不同物流409；买家刷新后确认收货，管理详情刷新显示COMPLETED。
7. 已支付但没有地址的订单没有发货表单。另一次发货在服务端成功后人为丢弃响应，页面禁止再发，刷新确认SHIPPED，POST仅一次。
8. 创建分类在服务端成功后丢弃响应，草稿保留且按钮禁用；刷新看到实际记录后解除限制，POST仅一次。受控PATCH403显示追踪编号且商品状态未改变。
9. USER访问全部六条管理路由均受限，管理GET和POST均真实403。只对本轮测试管理员修改数据库角色，并通过退出/重新登录建立实际USER Session，再焦点读取身份；管理页面和草稿被清理。恢复角色并重新登录后草稿为空。这不等于验证数据库角色变更立即使旧Session失效。
10. 六个管理页在390像素宽度无横向溢出，含完整券时间表单与可发货表单；长菜单可滚动并完成导航。桌面发货结果及手机发货表单截图已检查，pageErrors为空。

初轮脚本把权限页的小标题“访问受限”当成h1，定位超时；实际h1是“当前账号没有访问权限”。修正脚本后先清理首轮，再完整验收通过，未因此修改页面业务。

生产构建另在4173用随机测试管理员核对匿名登录保护、六条管理路由、真实登记发货、管理商品GET503与刷新恢复和390像素布局，pageErrors为空。网络丢弃、403/503属于受控故障；正常创建、商品状态、订单、发货、收货、权限和库存均由真实后端提供。

最终一轮含4个测试账号、2个分类、1个商品、1个活动、1种券、15张订单；数据库按用户和requestId验证各购买仅一张订单，金额一致。各轮均按确切ID和随机前缀名称清理，包括明细、物流、outbox、地址、券占用及样本；开发库对应账号数为0。没有重置自增ID。测试数据和临时浏览器、5173/4173/18739服务已清理。

本地忽略证据：tools/vue-admin-browser-summary.json、vue-admin-fixture.json、vue-admin-preview.mjs与tools/screenshots/vue-admin-*.png。

## 教学与后续

短文件合并讲类型/API/校验；useAdminPage单独讲共用读取/提交/身份流程；六页按分类商品、活动券、管理订单与发货分组讲。覆盖全部TS/JS与主要变量，给文件地址和当前行号；HTML/CSS概括，SQL/测试不讲。

下一阶段是第六阶段Vue Agent聊天：接通现有Java会话与SSE，显示来源并处理停止/清空。本次先学习第五阶段，不自动进入下一阶段，不提交推送。
