# 限量集市 Vue前端

已实现基础布局、登录退出、身份恢复、USER/ADMIN路由及错误反馈；第二阶段增加注册、商品分类筛选分页、活动浏览与领券。开发环境使用Vite代理访问现有Java接口。

## 运行

使用nvm中已确认的Node24.13.0和npm11.6.2，在frontend目录执行。

```powershell
npm.cmd ci
npm.cmd run dev
```

页面入口为http://127.0.0.1:5173，默认将/api代理到http://127.0.0.1:8080。使用现有后端账号登录，后端提供Session与CSRF校验。

后端监听其他地址时，在同一终端设置目标再启动。

```powershell
$env:BACKEND_URL='http://127.0.0.1:18739'
npm.cmd run dev
```

BACKEND_URL由vite.config.ts在启动时读取。该环境变量只用于本机代理目标。

## 检查与构建

```powershell
npm.cmd test
npm.cmd run build
```

test使用Node内置测试运行器验证身份、CSRF和异步请求边界。build依次执行vue-tsc和Vite构建，输出到dist。浏览器联调记录见docs/learning/39-vue-auth-foundation.md（相对仓库根目录）。

## 文件调用流程

main.ts安装Pinia、组件和路由。router/index.ts先调用stores/auth.ts恢复身份，再决定访问位置。登录页调用auth.login()，经api/auth.ts与api/http.ts获取CSRF、发送表单并读取me。App.vue根据身份变化导航，AppLayout.vue显示当前账号与内容。

接口返回直接对应后端DTO。登录与退出的204作为空响应处理。登录密码只在表单与当前请求中使用，用户信息通过me恢复。Element Plus按本阶段使用范围注册ElInput、ElButton和ElAlert。


## 商品与活动入口

- /register：创建账号后前往登录，不自动登录。
- /products：游客可浏览，支持名称与分类筛选、每页12项分页。
- /activities及/activities/:id：游客可浏览活动与优惠券；登录后可领券。

账号概览/和管理中心/admin继续受身份或角色保护。业务接口通过api/catalog.ts调用，JSON写操作使用http.ts的postJson获取当前CSRF。第二阶段文件与联调记录见仓库docs/learning/40-vue-market-browsing.md。


## 购物车与结算

第三阶段已接入/cart、/addresses、/purchase/:productId和/checkout/result/:id，均要求登录。商品页进入选购后可填写数量并保存到购物车，也可直接购买；活动详情进入活动购买并选择符合条件的本人券。购物车多商品结算不使用活动券。

待确认的下单请求按账号保存在 sessionStorage。系统会使用原参数和原请求标识自动恢复，并在页面顶部显示处理状态或已恢复的订单。确认前暂停新购买；关闭标签页后不保证保留会话存储。成交金额与地址以订单结果为准。

源码与浏览器验收见docs/learning/41-vue-cart-checkout.md（相对仓库根目录）。


## 本人订单管理

第四阶段已接入/orders列表与/orders/:id完整详情，支持每页10笔、六种状态筛选、模拟支付、取消及确认收货。订单创建成功进入详情；原/checkout/result/:id入口继续共用同一详情。

超过支付截止时间会禁用模拟支付，实际关闭状态以服务器为准。操作响应未知或失败后先刷新确认状态，不自动重复POST。订单列表/详情实际查到当前待确认购买的requestId时，可解除该笔购买的未知状态。模拟支付不产生真实扣款。

源码与验收记录见docs/learning/42-vue-order-management.md。管理员发货页面将在第五阶段接入。


## 管理员业务页面

第五阶段提供/admin/categories、/admin/products、/admin/activities、/admin/coupons、/admin/orders和/admin/orders/:id，全部需要ADMIN。管理中心和侧栏均有入口。

分类支持新增/重命名；商品新建为下架，支持资料修改和PATCH上下架，编辑不修改库存；活动和券按现有接口只创建与查看。管理订单支持状态/用户筛选、详情和已支付有地址订单的人工物流登记，买家可继续确认收货。

未知写结果先刷新核对，不自动重发。管理员身份/角色变化清空数据和草稿；同账号焦点身份确认保留草稿。源码与验收见docs/learning/43-vue-admin-management.md。


## Vue交易助手

第六阶段提供登录后的/agent：POST SSE增量回答、停止、新会话、确认清空、实际规则来源。只按用户保存会话编号，刷新不重载旧消息；断流不自动重发，退出与身份变化停止旧请求。当前42项前端测试通过。详见docs/learning/44-vue-agent-chat.md。

真实DeepSeek/RAG已在测试库联调。开发库已于2026-11-22执行014迁移并核对两张会话表；日常运行仍需启用服务端Agent配置，默认enabled=false未修改。前端不保存模型密钥。


## 整体运行与演示

第七阶段已完成真实Java/MySQL/Redis/RabbitMQ业务联调、开发库DeepSeek/RAG、构建预览及390px检查。启动与演示顺序见[本机运行说明](RUNNING.md)，完整证据见仓库docs/learning/45-vue-integration-delivery.md。Vue目前独立运行，不随JAR提供；preview仅用于本机构建检查。
