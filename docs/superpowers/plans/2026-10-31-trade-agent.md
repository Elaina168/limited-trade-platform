# 交易业务 Agent 实施计划

> 执行方式：使用 executing-plans，在当前会话自行实现；遵从用户不主动启动子代理、不自动提交推送的要求。

**Goal:** 使用 DeepSeek 完成有权限边界的本人订单咨询与规则解释。

**Architecture:** 同一 Spring Boot 进程复用 OrderService；Spring AI 负责模型协议和工具抽象，应用控制循环预算。默认关闭AI，启用时从外部配置读取凭据。

**Tech Stack:** Java 21、Spring Boot 3.5.16、Spring AI 1.1.8、DeepSeek Chat Completions。

**Spec:** `docs/superpowers/specs/2026-10-31-trade-agent-design.md`，用户已于2026-10-31确认并选择DeepSeek。

## Global Constraints

- 不升级Spring Boot、不改认证或交易写路径、不增加数据库表。
- 仅三个只读工具；当前用户身份由服务端传入，不发送地址、手机号、requestId。
- 最多4次模型调用、4次工具执行，默认单次网络10秒、总截止45秒、单次输出1024 Token。
- 首期单轮API，不实现向量RAG、SSE、持久记忆、MCP或前端。
- 复用当前适合的checkout及项目Maven缓存，不创建新worktree；不提交、推送或部署。

## Review Focus

- 模型伪造用户ID或工具名：不能越权或调用白名单外能力。
- 同一响应请求多个工具：逐项计数，不绕过工具预算。
- 空结果与查询失败：不能统一转成没有订单。
- 真实订单含收货信息：工具投影不得泄漏。
- 关闭AI及缺少凭据：默认业务不依赖密钥；开启时配置错误明确失败。

## Task 1：依赖与配置

Files: `pom.xml`、`.gitignore`、`src/main/resources/application.yml`、`config/agent.properties.example`、`agent/AgentProperties.java`、`agent/AgentConfiguration.java`、`AgentConfigurationTest.java`。

接口：`app.agent`绑定启用、凭据、模型和预算；仅开启时创建`OpenAiChatModel`，不注册全局模型自动配置。

- [x] 写配置测试，断言默认关闭无需凭据、开启缺密钥失败、合法配置创建客户端。
- [x] 执行测试记录RED，增加BOM与模型模块及必要配置。
- [x] 运行配置测试至GREEN。

## Task 2：只读工具与规则

Files: `agent/TradeAgentTools.java`、`agent/AgentOrderSummary.java`、`agent/TradeRule.java`、`AgentToolsTest.java`。

接口：`list_my_orders(page,size,status,ToolContext)`、`get_my_order(orderId,ToolContext)`、`get_trade_rules(topic)`；复用`OrderService.listUserOrders`与`getUserOrder`，规则固定为PAYMENT/CANCEL/TIMEOUT主题。

- [x] 测试用户上下文、投影、页码/条数/状态校验、他人订单404和规则来源，先观察RED。
- [x] 核对Mapper条件，实施摘要与规则，运行至GREEN。

## Task 3：有界循环与API

Files: `agent/TradeAgentService.java`、`agent/AgentController.java`、必要请求响应record、`SecurityConfig.java`、`TradeAgentProtocolTest.java`、`AgentApiTest.java`。

接口：`POST /api/agent/chat`，请求`message`；响应包含回答、已执行工具、实际规则来源、调用次数、Token用量和耗时。

- [x] 使用本机HTTP模型替身测试工具往返、预算、未知工具、参数错误、超时与上游失败；先观察RED。
- [x] 使用Spring AI解析协议，显式循环并通过ToolContext传递可信用户；工具失败保留状态，模型异常502、超时504、预算503。
- [x] WebMvc测试匿名401、缺CSRF403、合法输入200、空问题400；默认禁用入口不开放。
- [x] 运行本期测试至GREEN；环境可用时增加真实MySQL本人归属集成验证。

## Task 4：文档、验证与审阅

Files: `docs/learning/33-trade-agent.md`、`docs/roadmap.md`、`README.md`、专项计划中的旧AI状态。

- [x] 写DeepSeek配置、调用示例、规则与实时数据边界和真实模型验收步骤。
- [x] 运行本期关联测试和离线打包；不自动全量回归。
- [x] 自行按权限、数据投影、预算、失败传播审阅改动，修复发现的问题。
- [x] 记录实际测试数量和未验证层次；用户提供本机密钥后完成真实模型验收样本。

## 执行记录

- 2026-10-31：用户确认设计和依赖下载，指定DeepSeek。官方文档当前模型为`deepseek-flash`，使用非思考工具调用模式，地址`https://api.deepseek.com`、路径`/chat/completions`；保留配置可替换。
- Ruling: 直接在当前checkout实现，用户要求的当前工程与既有本机依赖均在此；不因技能默认流程另建工作区或提交。
- Ruling: 不再次询问执行方式，用户已确认方案开始实施；遵从当前会话自主连续实现的授权。
- Task 1/2: complete，缺少类的RED已记录，配置与工具共8项GREEN。
- Task 3: complete，协议与API缺失的RED已记录；核对Spring AI字节码后使用`STOP.name()`判断完成。边界RED证明缺失topic及额外身份参数需在工具入口拒绝；日期RED证明需要ISO序列化。修复后13项协议、4项API与1项真实MySQL检查通过。
- Task 4: complete，最终48项定向测试和离线package通过；原有22项订单分页验证包含在48项中。另行4项DeepSeek真实样本通过，未把该样本外推为总体准确率。
- Final review: self-review，遵从用户不主动启动子代理的要求，无独立评审。核对权限、工具字段投影、循环预算、故障传播和外部配置；审阅新增的请求时间用例先RED，再把查询时间加入系统上下文，避免详情咨询缺少当前时间依据。模型返回畸形完成的用例已验证502。
- Ruling: JDBC未显式设置查询/网络超时；保持本次不修改原有数据库访问配置，在运行说明中明确45秒不是可中断任意JDBC操作的硬实时保证。
- UTF-8检查通过，真实密钥未出现在可版本化文件或本期检查日志中；私有配置已确认被Git忽略。未提交、推送或部署。
- 最终时间上下文修复已由48项定向测试覆盖，并额外用1项DeepSeek组合场景复核通过；新结果保存在`tools/agent-live-final.json`，未覆盖首次4项真实结果。所有必要验证完成后停止。
- 2026-10-31交付更新：用户在源码讲解完成后明确授权将今天的Agent阶段提交推送至既有`codex/bootstrap-boot`分支。前述“不提交推送”描述实现验收时的边界，本次授权覆盖该阶段代码、测试和文档；仍不包含本机密钥、日志、依赖缓存或构建产物。
