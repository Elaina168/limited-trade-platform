# 第三十九阶段 Vue前端基础与身份联调

日期为2026-11-17。第一阶段已实现frontend工程、布局、登录退出、身份恢复、角色路由、403/404和连接失败反馈。

## 技术与文件

使用现有nvm Node24.13.0、npm11.6.2。锁定Vue3.5.45、TypeScript5.9.3、Vite8.3.8、plugin-vue6.0.11、Vue Router4.6.3、Pinia3.0.4、Element Plus2.14.8及vue-tsc3.1.4。package-lock.json记录完整依赖，node_modules与dist加入忽略规则。

| 文件 | 作用 |
|---|---|
| frontend/vite.config.ts | Vue编译、固定5173开发端口、/api代理及BACKEND_URL |
| frontend/src/main.ts | 创建应用，安装Pinia、路由并注册用到的Element Plus组件 |
| frontend/src/types/auth.ts | 当前用户、登录输入、CSRF字段和身份状态类型 |
| frontend/src/api/http.ts | fetch、Cookie、CSRF、204响应、错误提示、请求超时和身份代数检查 |
| frontend/src/api/auth.ts | 登录、读取身份、退出接口及身份返回边界检查 |
| frontend/src/stores/auth.ts | 用户与身份状态、合并读取请求、登录退出、失败确认和旧响应隔离 |
| frontend/src/router/index.ts | 登录、账号、管理、403、404与连接失败路由 |
| frontend/src/App.vue | 监听身份变化导航，窗口重新获得焦点时刷新身份 |
| frontend/src/layouts/AppLayout.vue | 侧栏、顶栏、账号、退出和移动端菜单 |
| frontend/src/views | 登录、账号概览、管理身份概览及错误页面 |
| frontend/src/styles/main.css | 深蓝/浅色布局、组件样式和窄屏适配 |

采用fetch以衔接后续SSE。样式使用本地字体回退，Inter优先。管理首页本阶段展示实际账号身份，商品、订单等管理功能按第五阶段实现。

## 正常流程

进入页面后，router.beforeEach()调用auth.restore()，经currentUser()请求GET /api/auth/me。正常返回后依据角色访问页面，401进入登录，网络或服务失败显示可重试反馈。

LoginView.submit()调用auth.login()，先获取CSRF，再以表单提交POST /api/auth/login。204后读取me并保存用户信息，App.vue根据身份导航至账号概览或管理首页。密码在本次提交结束时清空。

AppLayout中的退出按钮调用auth.logout()，获取当次CSRF并提交退出。成功后清理身份并导航到登录。退出结果不确定时读取me确认，并保留错误提示。浏览器重新获得焦点时也读取me，以发现会话失效或其他标签页切换账号。

HTTP层使用revision检查请求所属身份。登录、退出、过期及读取到不同账号/角色时更新revision，旧请求返回后无法修改新身份。store同时保留对应状态，展示加载或失败反馈。

## 验证结果

Node身份回归12项通过，类型检查和生产构建成功。回归覆盖登录表单与CSRF、初次401、重复身份读取、错误密码、旧成功与旧401、CSRF期间切换、退出令牌更新、连接恢复、403提示及退出确认竞态。

代码复核发现两个竞态后，新增用例先观察失败，再修正。焦点刷新读到其他账号时需要推进身份代数，退出失败后的迟到身份确认也不能给新账号附加旧错误。

构建初次全量导入Element Plus产生大块提示，随后改用三个组件及对应样式，最终构建无该提示。最终入口JS约73.95KB、CSS约49.65KB，均为当次未压缩传输前的构建文件大小，其他公共及路由文件另计。

真实Chrome157.0.8103.52完成浏览器检查，汇总包含14组断言流程。

- 匿名直接访问管理路由，必填提示，错误密码与密码清空。
- 真实Cookie/Session登录，刷新恢复，USER侧隐藏管理入口。
- USER手写管理路由访问受限，真实管理API返回403。
- 界面退出，后退与刷新维持匿名，服务端退出后焦点刷新识别失效。
- 390像素宽度无横向溢出，菜单展开与Escape关闭，404反馈。
- ADMIN登录进入管理首页，刷新恢复，管理API返回200。
- 可控503下连接失败与恢复，CSRF403阻止登录POST，登录成功后me暂时失败时只重读身份即可恢复。

其中503及CSRF403通过浏览器请求拦截验证，正常身份、表单、Cookie、退出和权限由真实后端提供。脚本结束时记录的pageErrors为空。桌面登录、移动登录、移动账号、桌面管理截图已逐张检查主要布局。

最终构建产物另在4173预览入口检查，受控延迟me请求时显示首次连接提示，匿名访问/admin进入登录页，回车触发表单校验，390像素宽度无横向溢出，pageErrors为空。记录见tools/vue-preview-summary.json，截图见tools/screenshots/vue-login-production.png与vue-login-mobile.png。

## 数据与实例

本次使用现有可运行JAR启动临时18739实例，显式传入测试数据库URL，并关闭消息消费、定时任务、缓存和Agent。application-test.yml位于测试资源目录，JAR联调通过明确启动参数指定实际设置。

初次临时实例的标准Rabbit自动启动参数未覆盖项目监听器自身的autoStartup，出现连接重试。该实例仅完成ping读取，随后已结束。第二次启动明确设置app.order-message.enabled=false及其他对应开关，完成后续验证。

注册接口创建随机USER及待提升角色的测试账号。只读核对它们位于limited_trade_platform_test、开发库数量为0，随后仅将本次账号之一改为ADMIN。全部检查后按确切ID和用户名清理，两条测试账号残留为0。测试账号密码仅在验证进程内使用。

本机证据位于被忽略的tools目录，包括vue-auth-browser-summary.json、vue-auth-browser.log、vue-auth-cleanup.json、vue-auth-backend-console.log、vue-auth-race-red.log及screenshots/vue-*.png。测试账号编号的自增分配可能留有正常间隔。

验收后5173开发服务、4173预览及18739后端临时实例均已结束，并核对这三个端口无监听。

## 运行与后续

从frontend执行npm.cmd ci、npm.cmd run dev。默认代理到后端8080，其他地址通过BACKEND_URL设置。构建执行npm.cmd run build，身份检查执行npm.cmd test。

当前交付为第一阶段的账号入口与页面基础。第二阶段安排注册、商品与活动。下一步教学按文件与函数分段，先从工程入口、组件和请求链路讲起。
