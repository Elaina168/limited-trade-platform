# 交易业务 Agent 首期设计

日期：2026-10-31。

状态：用户已于2026-10-31确认方案并选择DeepSeek；首期代码已实现，48项定向检查与离线打包通过，4项真实DeepSeek场景通过。本文保留设计决策与开工基线，实际使用见`docs/learning/33-trade-agent.md`。

## 目标与首期演示

求职方向为 Java 后端与 Agent 应用开发。复用现有交易工程，以“本人订单咨询＋交易规则解释”建立真实工具调用闭环，后续再扩展检索增强生成、多轮会话及流式输出。

典型问题：“查一下我最近的待支付订单，告诉我支付期限和取消规则。”

完成标准：模型能够选择订单和规则工具；应用在登录用户范围内查询真实数据；模型使用查询结果回答；调用过程可追踪，越权、工具失败和超预算能够明确终止。

首期不包含订单写操作、前端聊天页面、持久会话、向量库、Embedding、MCP、多 Agent 或 Python 服务。它是最终简历项目的第一阶段，不单独宣称已经具备完整 RAG 或长期记忆。

## 当前事实与可复用代码

- 分支：`codex/bootstrap-boot`。开始检查时工作区干净。
- `pom.xml`：Java 21、Spring Boot 3.5.16、MyBatis Starter 3.0.5；没有 Spring AI 依赖。
- 实测工具：JDK 21.0.10，位置 `C:\Program Files\Java\jdk-21.0.10`；Maven 3.9.16，位置 `tools/apache-maven-3.9.16`。
- `.mvn/maven.config` 将 Maven 缓存设为项目内 `tools/maven-repository`；检查时该缓存没有 `org/springframework/ai` 目录。
- `src/main/java/com/elaina/limitedtrade/auth/SecurityConfig.java`：已有 Session 登录、默认 CSRF、登录用户订单权限以及默认拒绝未知接口。
- `src/main/java/com/elaina/limitedtrade/order/OrderController.java`：已提供 `GET /api/orders`、`GET /api/orders/{id}`。
- `src/main/java/com/elaina/limitedtrade/order/OrderService.java`：`listUserOrders(long userId, int page, int size, String status)` 和 `getUserOrder(long userId, long id)` 已按用户范围查订单；详情查不到时返回404。
- `OrderResponse` 包含金额、状态、`expiresAt`，也包含地址、运单、`requestId` 等首期咨询不需要的数据，不能直接整体发送给模型。
- `src/test/java/com/elaina/limitedtrade/order/UserOrderPageApiTest.java` 有分页、状态筛选和用户归属测试，使用 MySQL 测试库。
- `OrderService` 创建订单时保存十五分钟支付期限；支付、取消和超时关闭使用现有事务逻辑。规则资料实施时还须对照 Mapper 条件和相关测试，不单凭注释转写。

## 方案取舍

1. Java＋Spring AI：与现有认证和 Service 在同一进程内集成，符合 Java 后端求职目标。用户已确认采用该首期方案。
2. Java＋FastAPI＋LangGraph：利于深入 Python AI 工程，但增加跨服务身份、通信和部署成本，首期暂缓。
3. Java 直接手写模型 HTTP 协议：依赖少，但会重复处理模型消息和工具协议。首期使用 Spring AI 的协议与工具抽象，自己控制有限循环。

不修改 Spring Boot 版本，不拆分现有交易系统，不替换认证和数据库。

## 技术兼容与新增资源

核对官方 1.1 文档：Spring AI 1.1.8 支持 Spring Boot 3.4.x、3.5.x；当前默认 2.0 文档要求 Boot 4，不适用于直接照搬。

拟引入 `org.springframework.ai:spring-ai-bom:1.1.8` 和 `org.springframework.ai:spring-ai-openai`（由 BOM 锁版本）。首期采用模型模块显式配置，避免模型自动配置要求凭据而影响原有业务启动；实际 API 以锁定版本的源码和编译验证为准。

用途是调用支持工具调用的模型并处理消息、工具定义和结果；依赖缓存仍位于 `tools/maven-repository`，不安装系统软件，不下载本地模型。新增传递依赖数量和磁盘占用尚未解析，不能承诺准确下载体积。真实推理需要联网，费用由用户最终选择的服务和实际调用决定。

用户已选择DeepSeek，本轮按官方文档使用`https://api.deepseek.com`与`deepseek-flash`，明确关闭思考模式，并完成真实工具调用样本验证。密钥由用户填写到被Git忽略的本机外部配置，未进入聊天或版本文件。

## 调用链与身份边界

拟新增 `POST /api/agent/chat`，属于新接口设计，并非现有接口。

请求仅包含 `message`，首期为单次请求内的多步工具调用；不接受用户ID、角色、任意历史系统消息或客户端工具定义。限制问题长度为2000个字符。

流程：Session＋CSRF → Agent Controller → Agent Service → Spring AI ChatModel → 应用控制的工具执行 → OrderService／规则资料 → 模型回答 → JSON响应。

- Controller 从 `PlatformUserDetails` 取得用户ID；通过服务端 `ToolContext` 传给执行器，不作为模型可填写的工具参数。
- 工具只在当前请求中取得身份，不将某个用户的上下文保存到单例可变字段。
- 工具直接复用 Service，不在同一个进程中绕回调用自己的 Controller。
- Controller 原有参数校验不会自动覆盖工具入口。模型提供的页码、条数、状态和订单ID在工具边界校验，再调用 Service；用户ID始终由服务端决定。
- 工具投影只包含订单ID、状态、商品名称、购买数量、金额及必要时间，不包含地址、手机号、会话凭据和 `requestId`。
- 订单不存在或不属于本人保留同样的404语义，不向模型提供其他用户的数据。

## 工具范围

以下名称是拟新增命名，不代表代码已经存在。

| 工具 | 输入 | 输出与约束 |
|---|---|---|
| `list_my_orders` | `page`、`size`、可选`status` | 本人订单摘要及分页总数；页码从1开始，工具每页最多10条；状态沿用现有六种状态 |
| `get_my_order` | `orderId` | 本人单笔订单摘要；调用现有归属查询 |
| `get_trade_rules` | 受限主题 | 经过源码核对的支付、取消、超时规则，附规则ID和仓库来源 |

规则工具首期使用随代码维护的小型规则资料，准确称为“规则资料查询”，不称向量 RAG。引用由服务端从实际返回的规则中整理，拒绝把模型编造的路径或引用直接当成来源。

待支付列表查询不代表承诺仍可付款：应同时展示数据中的截止时间及查询时间，并说明实际支付仍以既有支付接口当次校验为准。不得根据模型判断改变订单状态。

## 模型循环与失败行为

关闭 Spring AI 模型内部自动工具循环，使用其工具执行能力完成显式的有界循环；在每次模型请求和工具执行前检查预算。

首期建议默认值（实现选择，可通过配置调整）：

- 最多4次模型调用、4次工具执行；多工具响应逐项计数，在执行前检查剩余额度。
- 单次模型网络超时10秒，整次请求使用45秒执行截止时间，自动模型重试关闭，单次输出最多1024 Token。
- 总截止时间通过各步骤前检查及剩余网络超时落实；不能把“检查截止时间”称为可以强行中断任意正在执行的 JDBC 查询。数据库调用仍受其实际连接和查询超时约束，实施时核对有效配置并如实报告。
- 输入长度、结果条数、输出 Token 和调用次数共同约束规模；不声称已经实施精确的总 Token 或金额硬上限。
- 未知工具、无效参数及缺少工具事实的问题明确失败或要求用户补充信息，不执行任意SQL、Shell或URL。
- 空订单集合属于正常结果；权限拒绝、业务查询失败、模型服务失败分别记录和返回，不转换成“没有订单”。
- 保留既有401、403、404语义；外部模型异常映射502、超时504、预算耗尽返回明确的503错误，不返回伪造成功答案。
- AI 模块默认关闭。关闭时不创建模型客户端、不要求模型凭据；原有业务继续使用。开启后缺少必要配置应明确失败。
- 日志记录请求关联标识、工具名、调用次数、耗时和结果类型；不记录完整提示词、订单明细、密钥或原始上游错误正文。

## 拟修改范围

- `pom.xml`：增加经确认的 Spring AI BOM 和模型模块。
- `src/main/java/com/elaina/limitedtrade/agent/`：Controller、Service、工具及必要DTO和配置，具体拆分以实现最小需要为准。
- `SecurityConfig`：仅放行登录用户访问新增的聊天POST入口，保留CSRF。
- 项目资源：加入已核对的规则资料和非敏感配置示例，不默认开启AI。
- 测试：新增Agent权限、工具数据投影、循环和外部模型协议测试；复用现有测试方式。
- 文档：记录配置、调用示例、模型真实联调结果与限制。实施时同步路线中的旧AI状态，不提前写完成。

首期以API和Swagger演示，不为此改造前端或增加数据库表。

## 验收与证据分层

1. 无AI配置时原有应用配置可加载，新增模块不会强制要求密钥。
2. 未登录、有登录无CSRF、登录且有CSRF分别符合现有安全约定。
3. 工具测试验证本人范围、他人订单404，以及模型可见结果没有地址和手机号。
4. 使用本机受控模型HTTP替身返回预设工具调用，测试真实Spring AI协议解析、Service查询和工具结果回传；明确它不能证明真实模型能正确选工具。
5. 固定覆盖无订单、工具参数错误、未知工具、工具连续调用超预算、模型超时和模型失败。
6. 对选定真实模型至少验证订单＋规则的多工具问题、无订单、拒绝越权和不支持的问题，记录工具轨迹、用量与耗时；没有凭据时该层保持未运行。
7. 只做本期关联的最小检查，完成后停止；不自动跑全量故障演练。

后续向量RAG引入时另建检索评价集并比较基线；多轮、SSE、MCP分别在对应阶段设计，不将首期测试结果外推为这些能力已完成。

## 本轮基线验证

2026-10-31在项目根目录执行：

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress -DskipTests package
```

结果：BUILD SUCCESS，完成离线打包；Tests are skipped，未执行测试，也未验证数据库或模型服务。没有下载依赖或启动服务。

## 执行状态

方案及依赖已获确认，实施和本期验证已完成；不自动进入下一阶段，不提交、推送或部署。默认关闭AI，启用方式与实际验证记录见第三十三阶段说明。

## 官方依据

- [Spring AI 1.1入门与兼容范围](https://docs.spring.io/spring-ai/reference/1.1/getting-started.html)
- [Spring AI 1.1工具调用、ToolContext与应用控制工具执行](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html)
