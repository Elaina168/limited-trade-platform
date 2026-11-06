# 交易助手页面实施计划

> 使用writing-plans、executing-plans与必要的测试驱动流程，当前会话自行实施；用户已授权先写前端，暂不展开前端源码教学，后续学完再一起重做。

**Goal:** 在现有原生页面中接入真实SSE聊天与会话操作。
**Architecture:** 新agent.js页面模块＋sse.js帧解析器，api.js扩展流式POST；app.js负责路由和身份生命周期。复用已有后端，无新增框架或DDL。
**Spec:** docs/superpowers/specs/2026-11-06-agent-chat-page-design.md（用户已确认先实施）。

## 边界与审阅重点

- 保留SSE阶段未提交改动，不自动提交推送、不改本机密钥、不迁移开发库。
- 部分正文不等于完成；只有done展示可信来源，reset清除暂存正文，EOF无done明确失败。
- UTF-8/CRLF跨网络块正确解析，取消覆盖CSRF和流读取；不自动重放请求。
- 路由、登录身份变化中止旧流，旧请求不能写入新账号页面；仅会话ID可入sessionStorage，正文只留当前用户内存。
- 所有模型、用户和来源文字使用文本DOM，不执行HTML；窄屏、键盘和生成状态可用。

## Task 1：流式网络入口

- [x] Node内置测试先RED：中文逐字节、LF/CRLF、多行data、reset/done/error、无done EOF、未知事件、AbortSignal、身份切换。
- [x] 实现assets/sse.js的readAgentStream与协议错误类型；api.js共享HTTP响应检查并增加streamWrite(path,data,onEvent,signal)，沿用Session/CSRF和身份代数。
- [x] Node定向检查GREEN，不安装测试框架。

## Task 2：页面与生命周期

- [x] 新增agent.js，页面内存保存消息，sessionStorage只保存按用户隔离的conversationId。实现创建、流式发送、停止、清空、来源折叠及专属错误说明。
- [x] index.html新增导航，app.js路由及身份清理接入，styles.css补充聊天布局/窄屏和textarea样式。
- [x] 路由离开、退出或切换身份时终止活动请求；新建/清空成功后才替换当前状态，停止后的不确定结果可见。

## Task 3：真实浏览器与交付

- [x] 盘点已有Node/浏览器工具，复用可用运行时。通过可控流验证可见增量、reset、错误、取消、XSS文本、刷新/身份隔离。
- [x] 在测试库/profile启动临时实例，验证真实登录/CSRF、创建、少量DeepSeek流式问答、追问与清空，结束清理测试数据和本阶段进程。
- [x] Node及必要后端关联检查、离线package、UTF-8/密钥检查，保留截图和证据层，不把替身或静态检查当真实部署。
- [x] 更新37阶段文档、README和roadmap；完成后停止，先讲功能和如何体验，暂不讲前端源码。

## 决策记录

- 用户尚未学习前端，已明确授权先实现作为后续学习参考，不重复请求页面实现许可，不新增React/Vue等框架。
- 解析器先观察缺模块的RED，随后Node8项检查GREEN，涵盖分块编码/协议/取消/身份。复用已有Node24与内置浏览器，没有安装npm包或浏览器。
- 可控浏览器验证完成/停止/异常EOF/409/来源/文本XSS/清空/刷新/离页和账号隔离。原生confirm阻塞自动化后改为页面内确认，并复测通过。
- 真实临时JAR使用test profile及测试库，注册接口创建账号后由浏览器登录并完成两轮DeepSeek、RAG来源和清空。请求分别3092/4681Token，合计7773；清空后核对零历史，账号和会话已清理，两项本阶段服务进程已停止。
- 最终106项Java定向检查与离线package通过，Node8项通过。截图在忽略目录tools/screenshots；未验收Nginx代理或Docker，未执行开发库DDL，没有自动提交推送。
- 自行审阅了DOM文本显示、done来源边界、AbortSignal覆盖、身份代数、路由清理、浏览器存储和旧JSON API兼容，未启动子代理。当前无Markdown渲染或服务端历史读取，已向阶段说明记录。
