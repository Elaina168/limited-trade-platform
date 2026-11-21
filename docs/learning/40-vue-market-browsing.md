# Vue前端第二阶段：注册、商品与活动领券

实施日期：2026-11-19。基于第一阶段的Session、CSRF、身份恢复和角色路由，接通现有Java接口。本阶段不改Java业务、不改表结构、不增加依赖。

## 本阶段交付与选择

游客可以访问商品、活动列表及活动详情；账号概览、管理中心和访问受限页继续保留登录要求。游客领券入口进入登录页，登录后通过导航回到活动领取；本阶段没有添加登录后自动回到原活动的跳转逻辑。

注册只创建USER，HTTP201成功后显示登录入口，不把注册响应写成登录身份。用户名按3至50位字母、数字或下划线校验；密码至少8个Java/JavaScript UTF-16字符，UTF-8编码最多72字节，另外检查确认密码。用户名重名409单独提示。

商品使用真实分类与搜索分页，每页12项。名称中的百分号、下划线作为普通字符提交，由后端转义处理。搜索后回到第一页；加载、空结果、失败重试、上一页和下一页分别显示。商品接口没有图片字段，因此卡片使用名称首字作为视觉标记。

活动列表链接到详情，详情展示关联在售商品、活动时间、剩余名额与优惠券规则。商品若已不在在售列表中，显示商品暂不可展示。详情每秒更新本机时间相关的活动和领券标签，后端仍是领取资格的最终判断依据。

已登录时同时读取我的券，已领券禁用按钮；未开领、结束、领完也禁用。领取期间阻止重复点击和其他领券操作，身份确认期间也禁止领取。成功响应更新当前券和所有权，不猜测剩余数量；服务端重复领取返回原记录。409提示刷新活动，其他已知HTTP错误沿用公共错误反馈及追踪编号。

## 新增文件

| 文件 | 作用 |
|---|---|
| frontend/src/types/catalog.ts | 分类、商品搜索分页、活动、优惠券和已领券类型，对应真实Java返回字段 |
| frontend/src/api/catalog.ts | categories、searchProducts、products、activities、activity、activityCoupons、myCoupons、claimCoupon接口函数 |
| frontend/src/views/RegisterView.vue | 注册输入、验证、提交、成功和失败反馈 |
| frontend/src/views/ProductsView.vue | 搜索输入、已提交筛选条件、页码与商品结果 |
| frontend/src/views/ActivitiesView.vue | 活动列表及详情链接 |
| frontend/src/views/ActivityDetailView.vue | 活动与商品展示、券列表、当前账号所有权和领券 |

## 修改文件

- api/http.ts增加postJson：取当次CSRF，再发送JSON POST，读取JSON结果并核对身份代数；通用超时提示改为刷新相关数据确认结果，适用于登录和业务写操作。
- router/index.ts增加注册与三个业务路由，将登录要求从整个布局移动到受保护子路由；已登录用户访问注册页回到对应账号首页。
- App.vue把注册页纳入已登录后的跳转判断。
- AppLayout.vue增加商品与活动导航、游客登录注册入口和游客空间文案，允许公开业务页渲染。
- LoginView.vue增加注册与游客浏览入口。
- styles/main.css增加筛选栏、卡片、分页、详情、注册与窄屏样式。
- tests/auth.test.ts新增JSON写入、CSRF期间身份切换、搜索参数编码三项检查；README、实施计划和roadmap同步进度。

## 主要变量与请求流程

RegisterView的username、password、confirmation是当前表单；busy防止重复提交；error显示错误；created决定成功提示。submit验证后调用postJson('/api/auth/register')，服务器成功创建账号后显示前往登录。密码和确认密码在提交结束后清空。

ProductsView的keyword和categoryId是尚可编辑的输入；appliedKeyword和appliedCategory是本次已提交的筛选条件，翻页继续使用它们；page保存请求页码，result保存后端分页结果。search保存条件并回到第一页，changePage改变页码，load经categories和searchProducts读取分类与商品。

ActivitiesView的load调用activities取得列表。ActivityDetailView的load从route.params.id取活动编号，然后并行调用activity、activityCoupons、products，以及已登录时的myCoupons。detail保存活动，product保存关联在售商品，coupons保存券模板，owned保存当前账号已领券。now和phase用于时间状态；claimLabel决定按钮标签，claiming保存正在领取的券ID，notice显示本次操作结果。claim调用claimCoupon，再经postJson发送带CSRF的请求，成功后用返回的UserCoupon替换对应所有权和券模板。

三个业务页的sequence是当前加载编号。新请求、账号变化或组件销毁都会使旧编号失效，迟到结果不能覆盖新页面。业务页同时监听auth.user?.id与auth.status，只在authenticated或anonymous时重新读取；这样退出失败后即使恢复同一账号，也会补载被作废的旧请求。切换账号后重新读取；详情在重新读取时立即清空owned与detail。公共HTTP层另用sessionRevision防止旧账号请求及旧401干扰新账号。两层分别解决页面请求先后顺序和身份切换。

完整领券流程：ActivityDetailView.claim → api/catalog.ts.claimCoupon → api/http.ts.postJson → GET /api/auth/csrf → POST /api/coupons/{id}/claim → CouponController.claim → CouponService.claim → 返回UserCouponResponse → 当前页面更新owned与coupons。后端以Session确定用户；按钮禁用仅提供交互反馈，不能替代服务端鉴权和库存事务。

## 验证证据

Node24.13.0与npm11.6.2运行npm test，15项通过，其中原12项身份回归继续通过。npm run build完成vue-tsc及Vite构建，无新增依赖，入口JS约75.48KB、CSS约51.18KB，其他公共块与路由块另计。git diff --check通过。

Chrome无头浏览器连接Vite5173和真实Java18739实例，显式指定limited_trade_platform_test。Java实例关闭Agent、缓存、消息消费与定时任务，不需要启动Redis或RabbitMQ。

本次浏览器检查（正常业务走真实链路，明确标注的故障使用拦截）：

1. 游客分类筛选返回13件在售商品，第一页12件、第二页1件，下架商品被排除；百分号名称搜索得到真实空结果。
2. 表单非法用户名反馈；注册HTTP201后me仍为401；重名409提示；使用新账号真实登录。
3. 活动列表进入详情；未开领、领完、结束的券禁用；成功领券、重复领取保持同一份配额扣减、刷新后仍已领取。
4. 退出清除所有权；第二账号独立显示可领取并成功领取。
5. 人为拦截领取响应为CSRF403，页面显示追踪编号，恢复真实请求后重试成功；商品503失败及恢复后的重试。
6. 延迟旧商品搜索响应，新筛选结果先返回后仍保持最新结果。
7. 服务端先真实退出而页面尚未获知时点击领取，401清除账号和可领取按钮，详情恢复游客入口。
8. 复核发现加载中退出失败且恢复同一账号时，旧请求被身份代数作废，但原先只监听用户ID导致页面空白。使用受控身份与延迟数据请求在三个业务页均复现失败；增加身份状态监听后，三个页面均自动补载成功。修复后重跑真实业务、账号隔离、失效Session和搜索交错检查，全部通过。
9. 无效活动编号及真实404；390像素商品与详情无横向溢出，浏览器pageErrors为空。桌面详情与移动商品截图已检查。

构建产物另在4173预览，确认商品、活动与注册公开路由可访问，游客访问账号概览、管理中心与访问受限页会进入登录，390像素布局无横向溢出，pageErrors为空。验证结束后，5173、4173与18739临时实例已停止。

403、503为受控故障；正常业务、重复领取、注册冲突、404与失效Session走真实后端。使用已有JAR，Java源码本次未修改。没有为本阶段重跑无关Java全套测试。

两轮真实验收每轮只新增一个随机命名分类、14件商品（13件在售）、一个活动、4种券和3个随机账号。每轮结束后按确切ID和名称清理测试数据，账号残留为0，开发库相应账号为0。自增ID间隔正常保留。临时浏览器脚本、测试记录和截图在被忽略的tools/vue-market-*及tools/screenshots/vue-market-*中。

## 接下来的学习与阶段

建议先合并讲types/catalog.ts、api/catalog.ts和http.ts的新方法，再分别讲注册页、商品页；活动列表与活动详情结合一次真实领券串起来。最后回顾路由、布局、登录入口与样式改动。SQL和测试源码不纳入讲解。

本阶段交付后，下一阶段才是购物车、地址、优惠券选择与结算。本次没有提交或推送Git。
