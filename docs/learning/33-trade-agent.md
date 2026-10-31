# 第三十三阶段：DeepSeek交易业务Agent

日期：2026-10-31。目标是Java后端与Agent应用开发，先实现本人订单咨询与交易规则解释。

## 本期功能

新增`POST /api/agent/chat`。一个请求内，DeepSeek可以提出只读工具调用，Java执行真实业务查询并把结果交回模型，直到给出回答或触及预算。

三个工具：`list_my_orders`查询本人订单分页，`get_my_order`查询本人订单摘要，`get_trade_rules`提供PAYMENT、CANCEL、TIMEOUT规则资料。

首期没有跨请求会话记忆、SSE、向量检索、MCP、前端聊天页面或业务写工具。规则工具是经过源码核对的小型资料查询，不能描述为已实现向量RAG。

## 为什么这样分工

模型负责理解问题、选择工具和整理回答。Spring Security决定登录身份，OrderService与Mapper决定订单归属；价格、状态和截止时间来自本次查询。模型没有数据库连接，也不能凭自报userId获得其他用户权限。

主要调用链：

```text
Session＋CSRF
  → AgentController.chat
  → TradeAgentService.chat
  → DeepSeek Chat Completions提出工具调用
  → 校验工具白名单、JSON参数和调用预算
  → TradeAgentTools＋服务端ToolContext
  → OrderService.listUserOrders/getUserOrder → Mapper/MySQL
  → 只返回订单摘要或规则资料
  → DeepSeek根据真实结果回答
```

`ToolContext`传递可信用户ID，不作为模型工具参数。代码拒绝模型附加的`userId`等字段。工具摘要不包含地址、手机号、运单和requestId。

待支付并不一定还可以付款。例如联调夹具中的9301、9305仍为PENDING_PAYMENT，但expiresAt早于查询时间；模型已在实际回答中解释不能据状态承诺可支付。真正支付仍由既有接口当次校验。

## 技术与配置

- Java21、Boot3.5.16保持不变。
- Spring AI1.1.8，使用`spring-ai-openai`模型模块，显式配置客户端，不引入模型Starter自动配置。
- DeepSeek官方地址`https://api.deepseek.com`，接口`/chat/completions`。
- 本轮使用`deepseek-flash`，请求明确设置`thinking.type=disabled`；模型名称可配置。
- 默认关闭AI。开启后缺少密钥会启动失败，不静默返回伪造答案。

新环境先参考`config/agent.properties.example`建立自己的`config/agent.properties`；本机已有私有文件，不要用示例覆盖它。

```properties
app.agent.enabled=true
app.agent.api-key=${DEEPSEEK_API_KEY:}
app.agent.base-url=https://api.deepseek.com
app.agent.model=deepseek-flash
```

也可以仅在被Git忽略的本机文件中填写真实密钥。不要把密钥写入命令行、测试文件、聊天或提交。普通Java进程不会自动加载任意`.env`文件。

`application.yml`按可选配置导入本机agent.properties；该文件已加入`.gitignore`。环境变量方案需要同时设置`AGENT_ENABLED=true`及`DEEPSEEK_API_KEY`。若私有文件已显式设置同名属性，则应直接修改该文件或用非敏感命令行开关覆盖启用状态，避免误以为占位符会覆盖配置文件。

复用原有数据库和RabbitMQ外部配置，启动方式与既有工程一致。已有本机密钥配置但保留关闭时，可使用：

```powershell
java -jar target/limited-trade-platform-0.0.1-SNAPSHOT.jar --app.agent.enabled=true
```

这不会把密钥放入命令行。测试配置里禁用的后台任务不等于日常应用默认关闭；正常运行仍需要原有业务依赖。

## 如何调用

启动并启用AI后，进入`/api-docs.html`，先使用现有账号登录，再展开“交易助手”。在线调试页沿用Session并自动处理CSRF；API客户端需要保留登录Cookie，并在登录后重新获取CSRF。

请求示例：

```json
{"message":"请查询我最近的待支付订单，并分别解释支付规则和取消规则，不要执行任何操作。"}
```

响应字段：

| 字段 | 含义 |
|---|---|
| answer | 模型组织的中文回答 |
| queriedAt | 请求开始时的服务器本地时间；订单列表工具还返回实际查询时间 |
| toolsUsed | 实际成功执行的工具，保留执行顺序和重复调用 |
| sources | 本次规则工具实际返回的资料，不信任模型自行生成的链接；没有查规则时为空 |
| modelCalls/toolCalls | 模型和工具调用次数 |
| totalTokens | 模型返回的本次累计Token用量；缺少用量时为null，不是精确费用 |
| durationMs | 本次助手执行耗时 |

回答文本仍由模型生成；来源清单来自执行器，不表示系统已自动逐句证明回答正确。效果需要固定任务和人工核对共同评价。

## 执行限制与错误

默认每次最多4次模型调用、4次工具调用，每次模型请求超时10秒，请求执行截止45秒，单次输出最多1024Token，关闭自动重试。多工具响应逐项计数，不会用一次模型响应绕过工具预算。

网络超时取单次限额和剩余截止时间的较小值。45秒也用于步骤间检查，不代表可以强制中断任意正在执行的数据库查询。本机db.url未配置JDBC超时参数，MyBatis未显式设置语句超时；因此不能宣称已建立整个请求的硬实时截止保证。

| 状态 | 情况 |
|---|---|
| 400 | 问题为空/超过2000字符、工具参数无效 |
| 401/403 | 未登录、CSRF缺失或现有权限拒绝 |
| 404 | 查询订单不存在或不属于本人；AI关闭时也没有聊天Controller |
| 502 | 模型服务失败、未知工具、模型结果不完整或无效 |
| 503 | 调用预算耗尽或订单数据查询失败 |
| 504 | 模型调用超时或超过执行截止时间 |

错误延续既有HTTP状态语义，不承诺统一JSON错误结构。日志使用既有traceId，记录`agent_request`、`agent_tool`、次数与耗时，不输出密钥、问题全文、完整订单或上游错误正文。

## 验证证据

配置、工具、WebMvc与本机模型HTTP替身测试覆盖：默认禁用、缺凭据、Session/CSRF、可信用户、字段投影、工具往返、必填/额外参数、未知工具、单次和多工具预算、模型与数据库失败、超时。HTTP替身测试验证协议与执行链，不证明真实模型决策能力。

真实MySQL测试复用既有测试库与事务回滚夹具，验证本人7301只读到9305、9303、9301，查询属于7302的9302返回404。关联的22项本人订单分页检查已通过。未启动Redis/RabbitMQ或改动开发库数据。

首次DeepSeek真实联调4项通过：

| 固定任务 | 实际结果 |
|---|---|
| 待支付订单＋支付和取消规则 | 2次模型调用、3次工具调用；正确返回9305/9301及两类规则，并指出期限已过 |
| 已支付订单为空 | 2次模型调用、1次工具调用；明确没有匹配记录 |
| 查询他人订单9302 | 1次模型调用后执行器返回404，无后续答案生成 |
| 要求立即取消订单 | 1次模型调用、0次工具调用；明确不能执行取消 |

样本耗时约0.8～3.0秒，仅为本机当次观察，不是性能基准或总体准确率。结果与记录在被忽略的`tools/agent-live-results.json`、`tools/agent-live.log`；日志中四次请求累计返回6366Token，不折算或虚构费用。

最终代码补入请求查询时间后，额外复核一次“待支付＋两类规则”真实场景，通过；仍为2次模型、3次工具调用，返回PAYMENT/CANCEL来源，使用2879Token。该次记录独立保存为`tools/agent-live-final.json`和`tools/agent-live-final.log`，未覆盖首次四个样本。两轮共计9245Token，仍不作为准确费用或性能基准。

本地定向验证命令：

本轮最终执行下述命令，48项通过，0失败、0错误、0跳过，离线打包成功。没有执行全量回归。

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress "-Dtest=AgentConfigurationTest,AgentToolsTest,TradeAgentProtocolTest,AgentApiTest,AgentOrderIntegrationTest,UserOrderPageApiTest" package
```

真实模型用例默认不运行；显式运行会访问DeepSeek并产生费用：

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress "-Dtest=AgentDeepSeekLiveTest" -Dagent.live=true test
```

## 简历与后续

当前可以围绕真实只读工具、服务端权限、有限执行循环、数据投影、规则来源和小样本模型联调讲解。不能写已实现完整RAG、多Agent、长期记忆、生产部署或大规模准确率提升。

本期8个顶层Java类型及Controller→Agent循环→ToolContext→OrderService调用链已完成源码讲解，配置和数据结构等短类合并讲解。按用户确认，一次推进一个阶段：明确范围、完成实现与必要验证，再讲作用和实际代码并答疑；不自动进入下一阶段。下一阶段为RAG规则知识库的检索与评价。

## 依据

- [DeepSeek首次API调用](https://api-docs.deepseek.com/)
- [DeepSeek工具调用与非思考模式](https://api-docs.deepseek.com/guides/tool_calls/)
- [Spring AI1.1工具执行与ToolContext](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html)
