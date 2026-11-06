# 第三十七阶段：交易助手聊天页面

日期：2026-11-06。用户尚未学习前端，本阶段先提供可运行的参考实现，暂不展开前端源码教学；后续学完后再配合重新设计和实现项目的前端。本阶段不引入React、Vue、构建工具或第三方Markdown库。

## 已新增功能

主导航增加“交易助手”，路由为/#agent。登录后可以创建会话、连续提问、查看逐段生成的回答、停止生成、清空历史，以及展开本轮实际规则来源。原有商品、活动、购物车、订单和管理页面保持现有入口。

聊天区显示用户与助手消息，助手状态包括准备中、生成中、完成、已停止和未完成。停止、断流或服务失败时，部分正文不会标成完整答案，也不显示该半轮的规则来源。source文本、用户文字和模型回答均作为文本DOM显示，保留换行，不执行HTML；Markdown标记可能原样显示，未实现完整Markdown渲染。

桌面与390像素窄屏完成实际布局检查，没有横向溢出。问题输入最多2000字符，Enter发送、Shift＋Enter换行，组合输入期间不会按普通Enter提交。生成时禁用发送、新建和清空，保留停止按钮；退出或离开页面会中止当前读取。

## 文件与流程

新增两个生产前端模块：

| 文件 | 作用 |
|---|---|
| assets/sse.js | 流式UTF-8解码、SSE分帧、事件校验、完成/失败/取消语义 |
| assets/agent.js | 聊天页面、消息状态、会话创建/清空、流事件显示、来源展开和页面清理 |

修改assets/api.js：抽取共享HTTP响应处理，新增streamWrite；write增加可选AbortSignal参数，原调用保持兼容。修改app.js：接入#agent、登录门槛和路由/身份清理。修改index.html导航、styles.css样式。本阶段没有修改Java业务逻辑、接口或DDL；仅扩充静态资源访问的关联验证。工作区仍包含先前SSE阶段的未提交改动。

```text
app.js navigate
 → agent.js agentPage
 → submit
    → api.js write：必要时创建会话
    → api.js streamWrite：取CSRF，发送流式POST
       → sse.js readAgentStream
       → start/delta/reset/done回调
    → agent.js update：更新本轮文字、状态和可信来源

清空：changeConversation(true) → api.js write(DELETE) → 成功后清空显示
离页：disposeAgentPage → AbortController.abort
身份切换：resetAgentIdentity → 中止旧流、清空消息与会话引用
```

## 会话和错误处理

页面内存只展示最近12条消息（最多六组问答，包含失败问答），正文和来源不写入浏览器存储。sessionStorage只按用户ID保存当前conversationId，避免普通刷新不断新建会话。刷新后会明确提示“已接续上次会话，之前的消息不在当前页面显示”，不假装已恢复历史；服务端仍可利用此前成功上下文。

新会话在服务器创建成功后才切换编号和清空显示；清空使用页面内确认，DELETE成功才移除显示记录并保留编号。失败不假装已清空。退出/账号切换清除当前身份的会话引用和消息内存，迟到事件通过身份代数及页面有效性检查拒绝更新。路由离开时取消读取，再回来会把未完成任务标记为已停止。

401沿用现有登录失效处理；403提示凭据/权限；会话404提示过期或不可访问并移除无效引用，首次创建404提示助手当前不可用；409提示正在处理或状态变化，创建时的409提示会话上限；502/504提示助手未完成。没有自动重放模型请求。用户停止后如服务端仍在取消，立即再问可能收到409，需要稍后手动操作。

只有done将回答置为完成，并从实际响应的knowledgeSources或旧sources展示来源。delta追加暂存文字，reset清除当前工具轮次的临时文字。EOF没有done、未知事件或损坏JSON明确失败。停止/断开时提示回答是否已保存尚未确认，沿用后端“提交后done未达客户端”的网络边界。

## 验证记录

Node使用本机已有v24.22.1，无新增依赖。8项内置测试通过：中文UTF-8逐字节与CRLF跨块、多行data、reset/done/error、缺done、坏JSON/未知事件、AbortSignal、CSRF及same-origin、身份变化禁止POST/拒绝迟到事件、401不重放。

```powershell
node --test tests/agent-stream.test.mjs tests/agent-api.test.mjs
```

本机可控页面契约服务tests/chat-page-fixture.mjs用于真实浏览器验证，不作为数据库或模型证据。浏览器检查通过：匿名登录门槛、流式生成与reset、来源展开、停止、EOF未完成、HTTP409、新会话、页面内清空确认、刷新接续提示、离页取消、退出及另一账号不显示旧消息。返回的<img onerror>字符串作为文字显示，DOM内没有生成img元素，没有脚本弹窗。模拟服务统计5个流均已关闭。最初使用window.confirm导致自动化控制阻塞，随后改成页面内确认并重新验证通过。

真实后端另在127.0.0.1:18738启动临时JAR，明确使用test profile及application-test.yml中的limited_trade_platform_test；Agent/RAG只通过启动参数启用，没有改本机密钥或默认开关，没有向开发库写入数据。

通过注册接口创建临时测试账号，再由真实浏览器登录，完成真实Session/CSRF、会话自动创建、DeepSeek流、规则来源展开和连续追问：

| 问题 | 实际结果 |
|---|---|
| 查询本人待支付订单＋支付期限规则 | list_my_orders与search_trade_rules各一次；本人订单为空，正确解释截止时刻不能付款，页面显示完成与3个真实来源 |
| 追问超时库存回补及重复处理 | 本轮再次search_trade_rules；正确解释一次回补及幂等，显示完成与本轮来源 |

当次两次请求分别2次模型调用，耗时3638/2624毫秒，Token3092/4681，累计7773Token；仅为当次小样本，不是性能或总体准确率。最终浏览器控制台无错误。结果汇总为tools/chat-page-live-summary.json，完整本机服务日志为tools/chat-page-live-server.log，均被Git忽略。

真实页面清空后，直接核对测试库历史轮次数为0；随后退出，删除仅本阶段创建的测试账号及其会话，确认残留账号/会话均为0。临时Java实例与页面契约Node服务均已结束。清理证据在tools/chat-page-cleanup.json。没有执行开发库014迁移，也没有启动或重载Nginx/Docker；本阶段浏览器走本机Java直连。

截图（仅本阶段测试账号与数据）：

- tools/screenshots/agent-chat-desktop.png：真实模型回答及规则来源。
- tools/screenshots/agent-chat-mobile.png：390像素窄屏版本。

Java关联检查106项通过，0失败、0错误、0跳过，离线package成功，包括原Agent/RAG/多轮/SSE以及静态资源访问；不是全量业务或全站浏览器回归。命令如下，不重复调用真实DeepSeek：

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress "-Dtest=AgentConfigurationTest,AgentToolsTest,TradeAgentProtocolTest,AgentApiTest,AgentOrderIntegrationTest,UserOrderPageApiTest,BgeEmbeddingModelTest,RuleDocumentsTest,RuleKnowledgeBaseTest,RuleRetrievalEvaluationTest,AgentHistoryTest,AgentConversationIntegrationTest,AgentConversationApiTest,SecurityWorkflowTest,TradeAgentStreamTest,AgentSseServiceTest,AgentSseApiTest,AgentSseHttpTest,WebUiIntegrationTest" package
```

## 如何体验与后续

在已有数据库准备正确、Agent启用的正常本机实例中，打开首页，进入“交易助手”，登录后提问即可。应用默认仍关闭Agent；助手不可用时页面会提示，不在普通用户流程中要求填写密钥。开发库会话表需先按既有014脚本核对迁移状态，本阶段未代为执行。

本实现保留原生模块结构，方便以后一起重做。尚未实现服务器会话列表/历史读取、刷新后完整消息恢复、Markdown渲染、消息重放或未知结果自动对账；扩大评估、代理路径和部署另做阶段验收。本次不展开前端源码讲解，不自动提交推送或推进下一阶段。
