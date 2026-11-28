# 限量商城前端体验重构与独立微服务扩建执行方案（定稿）

> **For agentic workers (GPT / Codex):** 本方案为最终确认版，共 7 个阶段（Phase 1—Phase 7）。
> **核心架构边界确认：**
> 1. **`limited-trade-platform` 保持单体架构与现有 Java 交易助手代码**：不拆微服务，不引入外部 Python/MongoDB Sidecar（复杂多 Agent、LangGraph、PyTorch、MongoDB 留给后续独立 Agent 项目）。当前 Java 助手采取**“保留底座 + 界面重塑美化 + 必要轻量增强 + 补齐新版规则库真实模型复评”**策略。
> 2. **微服务在独立工程 `D:\codex\Java\spring-cloud-lab` 中深度扩建**：将该独立模块从目前的静态内存 Demo 升级为完整的 **Spring Cloud Alibaba（Nacos + Gateway + OpenFeign + Sentinel）+ 真实隔离 MySQL/Redis + 跨服务库存工作单与 MQ 延时反查最终一致性 + `CompletableFuture` 详情页并行编排压测** 标杆工程。
>
> **执行铁律：**
> 1. **逐阶段推进**：每次只执行 1 个阶段，开工前先读取本方案点名的真实源码与测试文件，核对最新接口与字段，完成后汇报验证结果并等待确认，不自动提交推送。
> 2. **保留既有工程不变量**：不得破坏购买幂等（`requestId`）、前端异常写入自动对账（`reconcileWrite` / `PendingOrderBanner`）、Spring Security Session + CSRF 轮换（`writeJson` / `postForm`）、跨账号隔离（`sessionRevision`）以及交易助手的只读安全边界。
> 3. **测试与浏览器验收同步演进**：前端 UI 改动会直接影响单元测试（`frontend/tests/*.test.ts`）与 Playwright 隔离浏览器验收脚本（`frontend/acceptance/trade.mjs`、`frontend/acceptance/model.mjs`）。每阶段修改交互后，**必须同步更新受影响的测试断言**，确保 `mvn -Pci test`、`npm test`、`npm run build` 及 `npm run acceptance` 全部绿灯。
> 4. **真实数据原则**：所有压测吞吐/延迟、MySQL/ES 对比、微服务并行编排与熔断数据均须来自本机真实运行产物，严禁编造数字；默认离线 CI（`-Pci`）不得调用付费大模型或强依赖未启动外部容器。

---

## 阶段总览与问题映射表

| 阶段 | 核心目标 | 覆盖问题 | 优先级 |
| --- | --- | --- | --- |
| **阶段 1** | **交易去摩擦改造**：购物车数量自动同步、详情页累加加购与库存上限、清理原生 `window.confirm`、侧边栏购物车角标 | #1, #2, #3, #14 | **P0** |
| **阶段 2** | **下单链路提速**：详情页“立即购买”确认抽屉、自动选中最优可用优惠券、登录 `?redirect` 安全回跳、结算页原地弹窗添地址 | #4, #5, #6, #7 | **P0** |
| **阶段 3** | **自动同步与商城质感升级**：移除手动刷新按钮、`visibilitychange` 静默同步、分类/订单 Tab 即时筛选、订单卡片快捷操作、商城导购首页、省市区级联、管理端商品分页搜索 | #8~#13, #15, #16 | **P0** |
| **阶段 4** | **Java 交易助手颜值重塑与轻量增强**：左右分栏现代客服工作台 UI、会话可读标题、快捷提问胶囊、关联订单直达卡片、补齐新版规则库真实 DeepSeek 复评 | #17, #21（轻量收口） | **P0** |
| **阶段 5** | **限量秒杀与 ES 搜索升级**：Redis 预热 + Lua 一人一单原子扣减 + MQ 异步建单与 500/2000 并发压测；商品搜索接入 Elasticsearch（分词/高亮/聚合/联想 + MySQL 降级） | #18, #19 | **P1** |
| **阶段 6** | **独立模块 `spring-cloud-lab` 微服务深度扩建**：对齐 Spring Cloud Alibaba（Nacos 热刷新 + Gateway + OpenFeign + Sentinel）+ 真实库库存工作单 MQ 双向兜底一致性 + `CompletableFuture` 详情页并行编排压测 | #20 | **P1** |
| **阶段 7** | **Docker Compose 全栈一键交付与运维演练收尾**：干净卷五服务启动与持久化验收（补强 3）+ 版本暴露、回滚与告警恢复演练（补强 15） | #22 | **P1** |

---

## 阶段 1：交易去摩擦改造——购物车自动同步、累加加购与弹窗清理

### 现状定位
- `frontend/src/views/CartView.vue`：修改数量后必须手动点“保存数量”，否则 `unsaved` 为 `true` 并阻止提交订单，且无 `- / +` 步进器。
- `src/main/java/com/elaina/limitedtrade/cart/CartService.java`：`setQuantity` 为覆盖语义；`ProductDetailView.vue` 默认数量为 `1` 且 `max="2147483647"`，重复加购会覆盖原有数量。
- `OrderDetailView.vue`、`AddressesView.vue`、`AdminOrderDetailView.vue`：使用原生 `window.confirm` 阻断交互。
- `frontend/src/layouts/AppLayout.vue`：侧边栏“购物车”无件数角标。

### 具体实施步骤
- [x] **1.1 后端新增“累加加购”接口，保留原覆盖接口兼容**
  - 在 `CartController.java` 与 `CartService.java` 新增 `POST /api/cart/items`（请求体 `{ productId: Long, quantity: Integer }`，语义为在本人购物车现有数量基础上累加 `quantity`）。
  - 在 `users.lockById(userId)` 行锁内校验商品存在且 `ON_SALE`；若 `stock <= 0` 返回 409 已售罄；计算 `target = existing + quantity`，若超出当前 `product.getStock()` 返回明确 409 库存不足提示（或按业务规则封顶并返回实际结果）。
  - 保留原有 `PUT /api/cart/items/{productId}` 供购物车页直接设定数量。补充 `CartApiTest.java` 单测。
- [x] **1.2 新建 Pinia 购物车状态仓库与侧边栏角标**
  - 新建 `frontend/src/stores/cart.ts`：维护 `items`、`totalQuantity` / `itemCount` 与 `refresh()`，随登录态自动加载或清空。
  - 在 `AppLayout.vue` 的“购物车”链接旁展示实时数量徽标（大于 0 时显示）。
- [x] **1.3 改造商品详情页加购与数量步进器**
  - 修改 `ProductDetailView.vue`：使用 `el-input-number`（保留无障碍 `label`“购买数量”），设置 `:min="1"`、`:max="Math.max(1, product.stock)"`；当 `product.stock === 0` 时禁用加购与选购按钮。
  - 点击“加入购物车”调用新累加接口（配合 `reconcileWrite` 对账），成功后更新 `cartStore` 角标并弹出 `ElMessage.success`。
- [x] **1.4 改造购物车页：取消“保存数量”按钮，改为自动同步**
  - 修改 `CartView.vue`：移除“保存数量”按钮与 `unsaved` 红字阻塞逻辑。
  - 使用步进器调整数量，在 `@change` / 400ms 防抖后自动调用 `PUT /api/cart/items/{productId}` 同步服务端与 `cartStore`；若用户在防抖未完成瞬间点击“提交购物车订单”，在 `submit()` 内部先 `await flushPendingUpdates()` 自动保存后再提交结算。
- [x] **1.5 清理全站原生 `window.confirm`**
  - `AdminOrderDetailView.vue`：移除“登记发货”提交时的 `window.confirm`；
  - `OrderDetailView.vue`：“确认收货”改为 `ElPopconfirm` 气泡确认，“取消订单”改为 `ElMessageBox.confirm`；
  - `AddressesView.vue`：“删除”地址改为 `ElPopconfirm`。
- [x] **1.6 同步更新测试与 `trade.mjs` 浏览器验收**
  - 更新 `frontend/tests/cart-quantity-save.test.ts`、`frontend/tests/checkout.test.ts`。
  - 更新 `frontend/acceptance/trade.mjs`：
    - 第 80 行附近：加购请求断言适配新 `POST /api/cart/items` 接口，并验证角标更新；
    - 第 194 行 `admin.once('dialog', ...)` 与第 201 行 `buyer.once('dialog', ...)`：移除原生弹窗监听，适配新的确认交互。

### 阶段 1 验收标准
- `mvn -Pci test`、`CartApiTest`、`cd frontend && npm test && npm run build`、`npm run acceptance` 全部通过；源码中无残留 `window.confirm`。

---

## 阶段 2：下单链路提速——快捷购买抽屉、自动选最优券与上下文回跳

### 具体实施步骤
- [x] **2.1 登录路由携带 `?redirect` 安全回跳**
  - 修改 `frontend/src/router/index.ts` 与 `LoginView.vue`：未登录访问受保护页面时跳转 `{ name: 'login', query: { redirect: to.fullPath } }`；编写 `safeRedirect` 校验仅允许以单斜杠 `/` 开头且非 `//`、非 `/login`、非 `/register` 的站内路径；直接访问 `/login` 无 `redirect` 时保持原默认落地页。
  - 修改 `ProductDetailView.vue`、`ActivityDetailView.vue` 中未登录引导链接携带当前页 `redirect`。
- [x] **2.2 抽取 `AddressFormDialog.vue` 结算原地新增地址弹窗**
  - 新建 `frontend/src/components/AddressFormDialog.vue`；在快捷购买抽屉、`PurchaseView.vue`、`CartView.vue` 中无地址或点击“+ 新增地址”时原地打开弹窗保存，成功后自动刷新地址下拉列表并选中新建地址，不离开结算页。
- [x] **2.3 自动选中当前最优可用优惠券**
  - 新增纯函数 `pickBestCoupon(usable: UserCoupon[])`（按优惠面额降序、到期时间升序选择）并配单测；在 `PurchaseView.vue` 与快捷购买抽屉中默认自动选中最优可用活动券（若用户主动切换为“不使用优惠券”则尊重用户选择，不强行覆盖）。
- [x] **2.4 商品/活动详情页“立即购买”抽屉 `QuickPurchaseDrawer.vue`**
  - 新建 `QuickPurchaseDrawer.vue`：在 `ProductDetailView.vue` 与 `ActivityDetailView.vue` 点击购买直接呼出抽屉选数量、地址（支持原地弹窗新建）、自动匹配最优活动券并提交订单。
  - 保留 `/purchase/:productId` 路由（共用同一套购买 composable），确保直接 URL 访问及 `trade.mjs` 过期活动禁购校验继续通过。
  - 下单成功后跳转 `/orders/:id?justCreated=1`，在订单详情顶部高亮展示支付倒计时与主按钮“立即模拟支付”。

### 阶段 2 验收标准
- 新增 `safeRedirect` 与 `pickBestCoupon` 单元测试通过；`npm test`、`npm run build`、`npm run acceptance` 全部通过。

---

## 阶段 3：自动同步与商城质感升级——去手动刷新、即时筛选、导购首页与管理端分页

### 具体实施步骤
- [x] **3.1 `useVisibilityRefresh` 静默同步 + 移除买家端手动刷新按钮**
  - 新建 `frontend/src/composables/useVisibilityRefresh.ts`：切回标签页（`visibilitychange` / `focus`，节流 ≥ 5 秒且非写入忙碌态）触发后台 `silentLoad()`。
  - **防闪白改造**：将 `ProductsView`、`OrdersView`、`ActivityDetailView` 等页面的首次加载骨架态与 `silentLoad()` 分离，刷新时不把已有 `result.value` 置为 `null`。
  - 移除买家端 `ActivityDetailView`、`CartView`、`OrdersView`、`OrderDetailView`、`AgentChatView` 上的显式“刷新xxx”按钮；活动页在跨过开始/结束时间点时立即自动拉取一次名额，进行中每 12 秒静默同步。
- [x] **3.2 即时筛选与订单列表快捷操作**
  - `ProductsView.vue`：分类改为胶囊标签栏（或 `el-select` `@change`）点击即查，清空搜索词自动重载。
  - `OrdersView.vue`：将状态下拉框 +“筛选订单”按钮升级为横向 `el-tabs`（全部 / 待支付 / 已支付 / 已发货 / 已完成 / 已取消或关闭），点击即切；在列表卡片直接展示待支付倒计时及“立即支付 / 取消订单 / 确认收货”快捷按钮，操作后原地更新单卡状态。
- [x] **3.3 商城导购首页重构**
  - 原账号信息页移至 `/account`（顶部栏头像/用户名下拉菜单可进）；重写 `/` `HomeView.vue` 为商城首页：展示顶部精选导购横幅、“进行中/即将开始的限量活动”专区卡片（3~4 个）与“热门在售好物”商品网格预览（8 件），核对并同步 `trade.mjs` 登录后落地页断言。
- [x] **3.4 收货地址省市区级联选择**
  - 引入轻量本地省市区三级联动数据配合 `el-cascader`（保留自定义文本回退能力），改善 `AddressesView.vue` 与 `AddressFormDialog.vue` 填写体验。
- [x] **3.5 管理端商品分页与搜索筛选**
  - 后端在 `AdminProductController` / `ProductService` / `ProductMapper.xml` 新增 `GET /api/admin/products/page?page=1&size=20&keyword=&categoryId=&status=`；前端 `AdminProductsView.vue` 增加名称搜索、分类/状态筛选和分页器，彻底解决 720+ 商品全量渲染卡顿；全站残留原生 `<select>`/`<input>` 统一为 Element Plus 组件（保留无障碍 `label`/`aria-label`）。

### 阶段 3 验收标准
- 买家端无手动刷新按钮，列表切换与后台同步平滑无闪烁；720+ 商品下管理端翻页与筛选流畅；前后端测试与 `npm run acceptance` 全部通过。

---

## 阶段 4：Java 交易助手颜值重塑与必要轻量增强（保留代码底座、高性价比打磨）

### 背景与边界
- 保留现有 Spring AI + SSE + 本地 ONNX BGE + Token 预算台账底座，不加外部 Python 服务。重点解决当前 `AgentChatView.vue` 界面简陋（纯文本堆叠、会话名叫 `会话 uuid前8位`、缺少快捷引导和订单直达卡片）以及 `README.md` 第 36 行遗留的“新版规则库真实上游表现待复评”收口问题。

### 具体实施步骤
- [x] **4.1 左右双栏现代智能客服工作台 UI 重塑**
  - 重构 `frontend/src/views/AgentChatView.vue` 布局与样式（兼顾移动端抽屉折叠适配）：
    - **左侧会话栏**：顶部主按钮“+ 新建对话”，下方会话卡片列表展示**可读会话标题**、有效期提示及悬停/内联删除与清空操作（使用 `ElPopconfirm` 替代生硬的横条确认框）。
    - **右侧对话区**：
      - 空状态欢迎屏：展示助手职责说明（只读查单 + 平台规则解答）及 **4 个可一键点击提问的推荐问题胶囊**（例如：“查一下我最近的待支付订单”、“限时活动的满减券怎么领取和使用？”、“下单后多久不付款会自动关单？”、“取消订单后优惠券和库存会退回吗？”）。
      - 消息流美化：区分买家右侧深色气泡与助手左侧白色卡片气泡、角色徽标、流式生成呼吸指示器（`正在检索规则与订单…` / 打字光标动效）；
      - 引用来源美化：将 `<details>` 升级为紧凑美观的折叠知识来源标签卡（显示规则标题、行号徽标与高可读摘录框）；
      - 底部输入区：圆角组合输入框，内置字数提示、流式生成时醒目的“停止生成”按钮与发送按钮。
- [x] **4.2 三个必要的功能小提升**
  1. **会话自动可读标题**：后端 `AgentConversationMapper.xml` 查询本人会话列表时，通过子查询或字段带出该会话第一条用户提问的前 22 个字符作为 `title`（无提问的空会话显示“新对话”），前端优先展示 `title` 并以小字保留短编号（同步核对并更新 `frontend/acceptance/model.mjs` 第 118 行的会话按钮定位方式）。
  2. **回答内嵌“关联订单直达卡片”**：后端 `AgentChatResponse`（及历史轮次快照）返回本轮工具查询涉及的本人订单简要列表 `orders: List<AgentOrderSummary>`（已有 `AgentOrderSummary.java` 可直接复用！）；前端在助手气泡下方渲染精致的订单小卡片（显示 `订单 #ID`、状态标签、应付金额、“查看订单 / 去支付 →”路由链接）。
  3. **订单详情“问这笔订单”一键顺滑衔接**：从 `OrderDetailView.vue` 点击“问这笔订单”进入 `/agent?orderId=123` 时，支持平滑带入问题并聚焦（或带 `auto=1` 自动发送首问，保持与 `model.mjs` 验收用例一致）。
- [x] **4.3 补跑一次新版规则库真实 DeepSeek 轻量复验（收口 `README.md` 遗留说明）**
  - 在配置了本机 DeepSeek API Key 且显式指定预算上限（如 `≤ 2 元`）的前提下，用新版 13 篇商城规则语料跑通一次 `AgentEvaluationV3Test`（`-Dagent.evaluation.v3=live`），记录真实首字延迟（TTFT P50/P95）、工具与引用准确率、负例拒答表现及账本结算结果，更新至 `docs/learning/61-storefront-rag-corpus.md` 与 `README.md`，消除“留待单独复评”的未完待续感。（若当前机器未配置真实 Key，则先完成 4.1~4.2，保留命令待用户填入 Key 后一键执行。）

### 阶段 4 验收标准
- 交易助手桌面端（1280×800）与移动端（390×844）视觉整洁美观、气泡层次清晰；
- 会话显示可读标题，查单回答下方可点击订单卡片直达订单详情；
- `mvn -Pci test`、`npm test`、`npm run build`、`npm run acceptance`（含 `verifyAgent`）全部通过。

---

## 阶段 5：商城后端硬核补强——Redis+MQ 异步秒杀与 Elasticsearch 商品搜索

### 具体实施步骤
- [x] **5.1 限量活动异步秒杀链路（Redis 预热 + Lua 一人一单 + RabbitMQ 异步建单 + 对账）**
  - 新增 `sql/017_activity_seckill.sql`：为 `activity` 增加 `per_user_limit INT NOT NULL DEFAULT 1` 及异步抢购凭据表 `activity_purchase_reservation`（唯一约束防重与限购兜底）。
  - 编写 `src/main/resources/redis/activity-seckill.lua`（原子完成 `requestId` 幂等、活动时间窗、一人一单限购、名额预扣）及失败/取消补偿归还 Lua 脚本。
  - 新增异步抢购接口与 `ActivitySeckillOrderConsumer`：预扣成功返回 `202 QUEUED` → MQ 异步执行数据库条件扣减与建单 → 失败自动回补 Redis + 死信告警 + 定时三恒等式对账任务 `ActivitySeckillReconciler`。
  - 前端抢购抽屉支持 `QUEUED` 排队态自动短轮询，出单后无感跳往支付。
  - 在隔离环境实测 **500 / 2000 并发抢 50 件商品**（对比原 MySQL `FOR UPDATE` 与新 Redis+MQ 异步链路），将吞吐、P50/P95/P99、0 超卖与对账证据写入 `docs/learning/62-seckill-redis-mq-benchmark.md`。
- [x] **5.2 商品搜索接入 Elasticsearch（含自动降级 MySQL）**
  - 抽象 `ProductSearchGateway`，默认 `app.search.mode=mysql` 保持离线 CI 零外部依赖，`app.search.mode=es` 启用 Elasticsearch（支持中文分词、关键词高亮、分类数量聚合、价格区间与有货筛选、价格排序及 `GET /api/products/suggest` 搜索联想；ES 故障自动熔断降级回 MySQL）。
  - 通过 `@TransactionalEventListener(phase = AFTER_COMMIT)` 增量同步商品变更；前端 `ProductsView.vue` 接入搜索词联想、高亮标题、价格排序与分类聚合数量展示；实测万级商品检索耗时对比写入 `docs/learning/63-elasticsearch-product-search.md`。

### 阶段 5 验收标准
- 默认离线 `mvn -Pci test` 全部通过；隔离中间件集成测试与高并发压测对账 100% 通过。

---

## 阶段 6：独立模块 `spring-cloud-lab` 微服务深度扩建（对标大厂/中厂微服务考点）

### 背景与版本策略
- 在独立的 `D:\codex\Java\spring-cloud-lab` 工程内扩建，不改动 `limited-trade-platform`。
- **版本对齐要求**：开工前先核对 Maven 中央仓库的官方兼容矩阵，将 `spring-cloud-lab/pom.xml` 对齐到 **Spring Cloud Alibaba 官方稳定兼容组合**（例如 `Spring Boot 3.3.x` + `Spring Cloud 2023.0.x` + `Spring Cloud Alibaba 2023.0.3.2`，保持 `JDK 21`），严禁在不兼容的 Boot 版本上强塞依赖。

### 目标服务拓扑
| 模块 | 职责 |
| --- | --- |
| `gateway-service` | Spring Cloud Gateway：统一入口、登录态/用户 ID 头透传、网关路由限流、全链路 `X-Trace-Id` 生成与透传 |
| `product-stock-service`（双实例） | 商品详情、Redis 缓存读取、库存扣减与**库存工作单（Stock Task）状态机** |
| `marketing-service` | 限时活动校验、满减优惠券查询与核销/释放工作单 |
| `order-service` | 订单创建、**商品详情页 `CompletableFuture` 跨服务并行聚合**、超时关单延迟消息 |
| 中间件依赖 | **Nacos**（服务发现 + 配置中心）、**Sentinel**（限流/熔断/热点保护）、MySQL（独立库 `cloud_mall_lab`，按服务逻辑分库或表前缀隔离）、Redis、RabbitMQ |

### 具体实施步骤
- [x] **6.1 底座替换：Nacos + OpenFeign + Sentinel + Gateway**
  - 移除原 `registry`（Eureka）与 `config-server`，提供本机便携 Nacos 单机模式一键启停脚本（复用 `scripts/lab.ps1` manifest 安全管理进程）。
  - 所有服务接入 Nacos 注册发现与配置中心：演示通过 `@RefreshScope` / `@ConfigurationProperties` 在线修改营销文案、降级开关与限流规则，**不重启 JVM 秒级热生效**。
  - 使用 **OpenFeign**（配连接/读取超时、`X-Trace-Id` 拦截器透传、`FallbackFactory` 降级）替代原手写 `RestClient`。
  - 接入 **Sentinel**：配置网关层与服务层 QPS 限流、下游慢调用比例熔断、异常比例熔断及热门 `productId` 热点参数限流。
- [x] **6.2 核心亮点 1：跨服务下单与“库存工作单 + RabbitMQ 延时反查”双向兜底最终一致性**
  - `order-service` 创建订单时，先写本地订单记录，再通过 OpenFeign 调用 `product-stock-service` 锁定库存（写入 `stock_lock_task` 工作单 `LOCKED`，同时向 RabbitMQ 投递一条延时检查消息，如延时晚于订单落库/关单窗口）及调用 `marketing-service` 占用优惠券。
  - **异常反向兜底**：若 `marketing-service` 核销失败或 `order-service` 在扣完库存后发生异常/宕机回滚，订单未落库；当 `product-stock-service` 收到库存延时消息后，通过 OpenFeign 反查 `order-service` 订单状态——若订单**不存在**或状态已变为 **`CANCELED / CLOSED`**，则依据工作单状态机**幂等解锁归还库存**（`LOCKED -> RELEASED`）；正常超时未支付关单也通过 MQ 触发幂等解锁，形成双向闭环，彻底杜绝跨服务“少扣/死锁库存”。
- [x] **6.3 核心亮点 2：商品聚合详情页 `CompletableFuture` 跨服务并行编排与局部降级**
  - 在 `order-service`（或独立 `bff-aggregation` 接口 `GET /api/aggregate/products/{id}`）使用 **`CompletableFuture` + 自定义有界隔离线程池**，并行通过 OpenFeign 拉取：
    1. `product-stock-service`：商品信息与实时可售库存；
    2. `marketing-service`：当前关联限时活动信息；
    3. `marketing-service`：该商品可领满减优惠券列表。
  - **局部容错降级**：若 `marketing-service` 慢调用或故障触发 Sentinel 熔断，活动与优惠券字段降级为空列表并标记 `marketingDegraded=true`，**商品主信息依然正常返回 200**，不阻断原价浏览与购买。
  - 实测对比“串行 Feign 调用 vs `CompletableFuture` 并行编排”在模拟下游 RT 下的接口 P50/P95 延迟下降比例与吞吐提升倍数。
- [x] **6.4 全自动真实进程验收脚本与报告**
  - 更新 `spring-cloud-lab/scripts/verify.py`：自动拉起集群并逐项验证：双实例负载均衡、`X-Trace-Id` 跨四进程日志关联、Nacos 免重启配置热更新、并行聚合耗时优于串行且营销故障局部降级可用、下单模拟下游报错后库存经 MQ 延时反查自动归还（0 脏占用）、Sentinel 慢调用熔断与半开恢复。
  - 将完整架构图、状态机说明与实测数据写入 `spring-cloud-lab/docs/design.md` 与 `spring-cloud-lab/docs/results.md`。

### 阶段 6 验收标准
- `spring-cloud-lab` Maven 构建与全部单测通过；`python scripts/verify.py` 全链路真实 HTTP 与库存一致性对账 100% 通过，测试结束后进程干净回收。

---

## 阶段 7：Docker Compose 全栈交付与发布回滚演练收尾（对应原补强 3 & 15）

### 具体实施步骤
- [ ] **7.1 完成 Docker Compose 干净环境一键启动与持久化验收**
  - 更新 `limited-trade-platform/compose.yaml` 与 `Dockerfile`（涵盖 MySQL 自动迁移 `schema.sql` + `002`~`017`、Redis、RabbitMQ、Java 后端、Nginx 托管最新 Vue `dist` 并支持 `/api` 与 SSE 流无缓冲反代，可选 `es` profile 启动 Elasticsearch）。
  - 在全新空卷运行 `python scripts/verify-compose.py`：验证全容器 `healthy`、买家全链路交易、管理员发货、交易助手问答通过，且容器重启/重建后订单、库存与会话历史完整保留。
- [ ] **7.2 完成版本暴露、发布回滚与消息积压告警恢复演练**
  - 暴露应用构建 Git SHA 与数据库迁移版本；演练版本更新与故障回滚、模拟 MQ 积压触发告警及恢复清零，归档运行证据至 `docs/learning/48-strengthening-compose.md` 与 `docs/learning/64-release-rollback-drill.md`，更新 `README.md` 交付状态。

### 阶段 7 验收标准
- 干净环境仅需 `docker compose up -d --build` 即可完整体验优化后的限量商城与交易助手；全部 Java、Vue、Playwright 与 Compose 验收绿灯。
