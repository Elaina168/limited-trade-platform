# Vue3前端开发准备与实施计划

计划日期为2026-11-16。2026-11-17用户授权开始后，第一阶段已实现并完成身份、权限和浏览器联调，结果见../../learning/39-vue-auth-foundation.md。2026-11-19第二阶段已实现并完成浏览器联调，结果见../../learning/40-vue-market-browsing.md。2026-11-19第三阶段也已交付，见../../learning/41-vue-cart-checkout.md，2026-11-20第四阶段已交付，见../../learning/42-vue-order-management.md，2026-11-21第五阶段已交付，见../../learning/43-vue-admin-management.md，后续从第六阶段按表中顺序推进。

## 目标与本次交付

目标是由用户逐阶段参与实现可运行的业务前端，掌握组件、路由、状态管理、类型、接口联调和Agent流式交互。完成标准包括真实业务操作、账号隔离、失败反馈、移动端布局及构建交付。

本次交付包括环境盘点、已核对的接口契约、拟采用的工程组织、分阶段范围和验收标准。下文的frontend目录与文件名属于待实施设计。

## 已确认的现状

| 项目 | 当前证据 |
|---|---|
| Java工程 | limited-trade-platform，当前HEAD为ba0de983bd2c42cd7c619bac5034e9dad0988e40 |
| 原有页面 | src/main/resources/static/index.html与assets中的app.js、admin.js、api.js、ui.js、agent.js、sse.js、styles.css |
| 已有业务 | 登录注册、商品、活动、购物车、地址、优惠券、订单、管理操作和Agent聊天 |
| 后端认证 | Spring Security，Session与CSRF，USER/ADMIN角色，业务接口采用真实HTTP状态码 |
| 日常Node与npm | 按用户要求精简后，nvm仅保留并启用Node24.13.0，配套npm11.6.2，实际版本命令通过 |
| Node24目录 | C:/Users/zytx/AppData/Local/nvm/v24.13.0，Corepack0.34.5可调用，已有Yarn全局包目录存在 |
| 工具执行环境 | Codex终端优先解析到自身Node24.22.1。受限执行环境访问nvm目录会被拒绝，授权环境可正常读取和执行 |
| nvm目录 | NVM_HOME为C:/Users/zytx/AppData/Local/nvm，NVM_SYMLINK为C:/nvm4w/nodejs，当前链接指向v24.13.0；原v20.20.2已通过nvm卸载并确认目录移除 |
| npm连通性 | 配置的registry为https://registry.npmmirror.com，使用现有npm11.6.2查询Vite8.3.8成功 |
| 前端运行要求 | 已查询Vite8.3.8和plugin-vue6.0.11的Node要求为^20.19.0或>=22.12.0，当前Node24.13.0符合。最终项目依赖组合在初始化时固定 |
| Java与Maven | 实际执行确认JDK21.0.10、Maven3.9.16，Maven位于tools/apache-maven-3.9.16 |
| MySQL | 服务运行，127.0.0.1:3306可连接，现有账号只读查询确认服务端8.0.45，开发/测试库基础用户与订单表存在 |
| Agent表 | 测试库存在agent_conversation和agent_conversation_turn，开发库在本次information_schema核对中缺少这两张表，迁移留对应Agent联调阶段处理 |
| Redis与RabbitMQ | 本次127.0.0.1:6379、5672及15672连接被拒绝，联调前需要检查并启动已有服务 |
| 应用与开发端口 | 本次8080、8081、8082、18080、5173连接均被拒绝。开始实施时重新核对端口并启动对应实例 |
| 浏览器 | Chrome157.0.8103.52、Edge157.0.4341.60的可执行文件已核对，可用于后续浏览器验收 |

已读取用户提供的deep-research-report.md及《面向现有 Java 后端项目的 AI 全栈学习与 Agent 集成路线.docx》作为学习参考。学习进度依据用户本次说明。现有Agent接口由Java提供，前端阶段围绕这些接口开展。

## 技术方案

采用Vue3、TypeScript、Vite、Vue Router、Pinia和Element Plus。这些工具分别负责组件、类型、开发构建、路由、共享状态以及后台表单和列表交互。

拟采用原生fetch封装请求，与现有页面的请求及流式读取经验衔接。样式以项目现有“限量集市”命名和蓝色主色为基础，桌面端采用清楚的导航、内容和账号区域，窄屏提供可用的菜单与表单。具体视觉稿在基础工程阶段确定。

采用独立frontend目录管理源码与package-lock.json。开发阶段由Vite提供页面，/api转发到Spring Boot。后端默认端口来自application.yml中的8080，实际联调目标可配置。Session Cookie通过同源请求携带，代理路径与CSRF一起验证。

日常Node与npm已核实并统一到Node24.13.0及npm11.6.2。实施时明确选用的可执行路径并记录版本。Vue等依赖在初始化时核对兼容版本、Node engines及锁文件，并固定本阶段版本。完整依赖组合的安装、类型检查和构建结果将在实施记录中补全。

## 已核对的身份接口

| 操作 | 接口与输入 | 成功结果 | 前端要求 |
|---|---|---|---|
| 获取CSRF | GET /api/auth/csrf | JSON含headerName、parameterName、token | 写操作前读取，使用返回的headerName作为请求头 |
| 登录 | POST /api/auth/login，表单username/password | HTTP204 | Content-Type为application/x-www-form-urlencoded，成功后读取me |
| 当前用户 | GET /api/auth/me | JSON含id、username、role | 未登录为401，角色显示与路由权限取此结果 |
| 退出 | POST /api/auth/logout | HTTP204 | 使用当次CSRF，成功后清理用户状态和用户相关页面数据 |
| 注册 | POST /api/auth/register，JSON含username/password | HTTP201，返回当前用户结构 | 默认创建USER，成功后进入登录流程 |

登录与退出由SecurityConfig配置。登录失败返回401。注册用户名为3至50位字母、数字或下划线，密码按Java字符串长度至少8且UTF-8编码最多72字节校验，重名返回409。

类型直接对应现有返回结构。商品搜索及订单分页均采用page、size、total、items。HTTP204按空响应处理。响应错误优先根据HTTP状态和X-Trace-Id显示可操作提示。

## 业务接口清单

| 页面范围 | 已核对入口 | 核对来源 |
|---|---|---|
| 商品与分类 | GET /api/products/search，GET /api/categories | ProductController、CategoryController |
| 活动与优惠券 | GET /api/activities，GET /api/activities/{id}，GET /api/activities/{activityId}/coupons，POST /api/coupons/{id}/claim，GET /api/coupons/mine | ActivityController、CouponController |
| 购物车 | GET /api/cart/items，PUT及DELETE /api/cart/items/{productId} | CartController |
| 地址 | GET及POST /api/addresses，PUT及DELETE /api/addresses/{id}，PUT /api/addresses/{id}/default | AddressController |
| 订单 | GET及POST /api/orders，POST /api/orders/checkout，GET /api/orders/{id}，POST /api/orders/{id}/pay、cancel、receive | OrderController |
| 管理 | /api/admin/products、categories、activities、coupons、orders及对应已实现操作 | 对应Admin控制器 |
| Agent | POST /api/agent/conversations，POST /api/agent/conversations/{conversationId}/messages，POST /api/agent/conversations/{conversationId}/messages/stream，DELETE /api/agent/conversations/{conversationId}/messages | AgentConversationController |

每进入一个业务阶段，再核对对应请求DTO、返回类型、校验条件和数据库状态语义，并建立该阶段的TS类型与API函数。

## 拟定工程结构

```text
frontend/
  package.json
  package-lock.json
  index.html
  vite.config.ts
  tsconfig.json
  src/
    main.ts
    App.vue
    router/index.ts
    stores/auth.ts
    api/http.ts
    api/auth.ts
    types/auth.ts
    layouts/AppLayout.vue
    views/LoginView.vue
    views/HomeView.vue
    views/AdminHomeView.vue
    views/ForbiddenView.vue
    views/NotFoundView.vue
    styles/main.css
```

后续业务文件随阶段增加，例如api/orders.ts和views/orders。每个文件对应实际调用方。用户信息由Pinia管理，组件自身的输入、提交中和错误状态放在组件内。密码仅用于当次表单提交，登录身份通过me恢复。

## 阶段安排与验收

| 阶段 | 实现范围 | 验收标准 |
|---|---|---|
| 一 | 基础工程、布局、登录退出、身份恢复、角色路由、403/404与连接失败反馈 | 类型检查和构建成功，真实后端登录闭环、刷新恢复、退出、权限和失效场景通过 |
| 二 | 注册、商品筛选与分页、活动与领券 | 表单校验和重名提示可用，筛选分页与真实接口一致，空列表与失败可理解 |
| 三 | 购物车、地址、优惠券选择、结算 | 操作真实落库，账号变化清理数据，按钮状态与后端条件一致 |
| 四 | 本人订单列表、详情、支付取消、确认收货 | 分页与状态正确，重复点击受控，网络未知结果保留原幂等购买意图 |
| 五 | 管理侧商品、分类、活动、券和订单发货 | ADMIN可完成已有管理操作，普通用户进入受限页面得到正确反馈 |
| 六 | Agent聊天、逐段输出、停止、新会话、清空、来源 | reset撤销草稿，done确认成功保存，断开或错误有明确状态，身份变化中止旧流 |
| 七 | 整体浏览器联调、响应式、构建产物与运行说明 | 用户与管理员主要路径完整，控制台问题得到处理，前端交付步骤可复现 |

每阶段交付后按用户节奏讲解。短文件合并，长文件单独讲，说明代码段与主要变量，并用文件、函数名串起请求流程。新增SQL和测试源码沿用用户约定处理。

## 第一阶段的具体任务

- [x] 核对Node与包管理器可执行路径、版本及npm镜像连通性，确认可复用现有Node24.13.0/npm11.6.2。
- [x] 初始化frontend，确定兼容依赖并生成锁文件，验证最小页面能启动、类型检查和构建通过。
- [x] 实现types/auth.ts、api/http.ts和api/auth.ts，建立CurrentUser、CsrfToken类型及登录、读取身份、退出函数。
- [x] 实现stores/auth.ts，管理未加载、正在读取、匿名、已登录及读取失败状态，合并同时发生的身份读取。
- [x] 实现router/index.ts与AppLayout，增加登录、普通首页、管理首页、403与404路由，按当前身份决定可访问页面。
- [x] 实现登录表单的必填提示、提交中状态、错误反馈和成功导航，完成退出后的页面与状态清理。
- [x] 处理迟到响应与身份变化，旧me请求或旧401不能覆盖新登录结果，账号相关写操作在CSRF准备后再次核对身份。
- [x] 验证真实Session/CSRF链路和路由边界，记录实际检查结果及待解决问题。

## 第一阶段的具体检查

1. 匿名直接访问受保护页面进入登录页。初次me返回401按匿名处理。
2. 登录成功后显示服务端返回的用户名与角色。错误密码得到明确提示，提交中的重复点击受控。
3. 刷新受保护页面能恢复身份。读取身份时连接失败显示可重试状态，避免误判登录成功或失效。
4. USER通过手写地址访问管理路由得到受限提示。ADMIN能够进入管理首页。
5. 退出完成后返回登录流程，后退及刷新均不能恢复旧账号页面内容。
6. CSRF获取失败时终止写请求，403提示刷新或重新操作。登录与退出后的下一次写操作重新获取CSRF。
7. 登录、退出、刷新和身份读取交错时，旧响应不能复活旧用户。会话过期后的受保护请求引导重新登录。
8. 窄屏布局、键盘提交、焦点、表单标签和错误信息可用。类型检查与生产构建通过。

这些检查使用测试账号和隔离测试数据，在实施前核对数据库与服务状态。可控响应验证异步边界，真实浏览器联调验证Cookie、CSRF、页面行为和后端权限。

## 后续阶段需要继承的行为

订单创建的requestId及未知结果处理参考原有api.js的placeOrder。刷新、重试和网络中断后应沿用原购买意图，避免重复下单。

Agent使用POST与fetch读取SSE，事件为start、delta、reset、done、error。来源展示依据本次实际返回的knowledgeSources。当前服务端会话接口支持创建、问答与清空，页面刷新后的完整历史读取及会话列表需要后续单独设计。

现有商品维护支持信息修改及上下架，活动和优惠券管理以已实现接口为准。页面操作与API清单逐一对应，新增业务动作先确认后端能力。

## 当前准备结论

源码、接口与环境盘点已完成。日常Node/npm可用，Java/Maven与MySQL已核实。正式实施从选定Node可执行路径及初始化frontend开始，真实后端联调前检查并启动已有Redis、RabbitMQ和应用实例。

先前“npm未识别”的记录已修正。该现象来自Codex受限环境无法访问nvm目录，授权环境确认原先两套Node均配有npm。用户随后要求精简版本，已切换到24.13.0并卸载20.20.2。卸载前核对旧版本仅有自带npm/Corepack，当前无Node20进程，Yarn位于独立全局目录。nvm切换链接曾短暂未就绪，重新核对链接稳定后完成卸载与版本验证。环境核对结果记录于2026-11-16，包管理器查询使用tools/npm-env-check-cache作为缓存，SQL检查为只读查询。

阶段一的运行结果已记录，后续实施任务按对应阶段细化。2026-11-16的端口与环境表保留为准备时点快照，实际使用前重新核对。


## 第二阶段实施记录

- [x] 按RegistrationService核对用户名、密码与重复用户名语义，新增注册页，成功后由用户登录。
- [x] 使用GET /api/categories与/api/products/search实现每页12项分类、名称筛选及分页，处理加载、空态与重试。
- [x] 新增公开活动列表、详情和优惠券规则，读取当前账号领券状态并调用真实领券接口。
- [x] JSON写操作沿用CSRF和身份代数保护；账号变化清空所有权，旧页面请求不能覆盖新结果。
- [x] 保留账号与管理路由保护，增加游客浏览导航及登录入口。
- [x] 15项前端检查、类型检查与构建通过；真实测试库浏览器验证及390像素布局检查完成，测试数据已清理。

本阶段沿用原分支实施，不新增依赖或数据库结构。游客领券先登录，再由导航回到活动；自动返回原活动不属于本次交付。具体文件、函数、验证范围与下一阶段见学习记录40。


## 第三阶段实施记录

- [x] 核对购物车最终数量PUT、地址DTO、普通/活动下单及购物车结算协议，未扩展后端不支持的购物车活动券。
- [x] 完成地址增删改与默认、选购页、购物车多商品结算和本人订单创建结果页。
- [x] 持久化购买标识与原参数，支持未知结果刷新恢复、原参数重试、账号隔离及明确失败处理。
- [x] 20项前端检查、类型检查、构建与生产预览通过；真实Chrome及数据库验收、移动端布局检查完成。
- [x] 复现并修复焦点身份刷新覆盖购物车草稿的问题，测试数据和临时实例清理。

细化计划见2026-11-19-vue-checkout.md；实现与讲解地图见学习记录41。当前不自动提交或推送，支付、取消、确认收货等留第四阶段。


## 第四阶段实施记录

- [x] 核对本人订单分页、六种状态、支付截止、取消与收货的真实接口规则。
- [x] 完成列表/筛选、完整详情与三个状态操作，兼容旧下单结果入口。
- [x] 未知操作先读取确认，查询实际匹配订单可解除待确认购买，保留账号/路由隔离。
- [x] 25项前端检查、构建、真实Chrome/测试库与构建预览通过；修复并发读取被作废后busy残留。
- [x] 清理随机测试数据和临时实例，未提交或推送。

细化计划见2026-11-20-vue-orders.md，实现与教学地图见学习记录42。管理员业务页留第五阶段。


## 第五阶段实施记录

- [x] 核对已有分类/商品/活动/券/管理订单接口及DTO边界，增加PATCH支持。
- [x] 完成六个受ADMIN保护的管理页、管理中心和导航入口。
- [x] 统一读取/写入/未知结果核对，保留同账号草稿，身份或角色变化清空私有数据。
- [x] 30项前端检查、类型检查和构建通过，真实业务/权限/移动端与构建预览验收完成。
- [x] 清理随机样本和临时实例，未提交推送。

详细计划见2026-11-21-vue-admin.md，代码与教学地图见学习记录43。Agent聊天页面留第六阶段。
