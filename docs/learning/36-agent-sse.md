# 第三十六阶段：Agent SSE真实流式输出

日期：2026-11-06。基于既有Java Agent、RAG和MySQL多轮会话，新增真正的模型增量输出。DeepSeek按stream模式生成文字，经Spring MVC SseEmitter推送给客户端。没有将完整答案人为切片，没有新增依赖或数据库表。旧单轮、多轮JSON接口继续使用原执行逻辑。

## 新增及修改

新增两个生产类型：

- AgentStream：一条流的事件回调、取消标志、上游Subscription和工作线程；取消与保存通过同一锁决定先后。
- AgentSseService：最多8个并发流，无等待队列；管理SseEmitter、后台任务、错误及终止。

修改现有代码：

- TradeAgentService：新增chatTurnStream，和chatTurn共享runTurn执行循环；streamModel调用OpenAiChatModel.stream，createModel复用客户端创建；聚合结果仍走既有工具白名单、可信身份、参数校验和预算。
- AgentConversationService：将领取/保存/失败释放提取为prepare、finish、release、releaseAfterFailure，JSON路径和stream路径共用；PreparedTurn替代旧内部Ticket，含本次用户、会话、问题、版本、令牌和历史快照。
- AgentConversationController：新增stream方法，原接口保留。
- SecurityConfig：新增流式POST登录规则，允许服务器内部ASYNC重派发，初始REQUEST仍按Session/CSRF校验。
- deploy/nginx/server-common.conf：仅流式路径关闭响应缓冲及缓存，读取超时310秒，覆盖配置允许的最长300秒请求；其他路径仍30秒。

没有修改本机config/agent.properties中的密钥或开关，没有执行DDL或迁移开发库。既有开发库014会话迁移仍是运行前置条件，本轮只用测试库验证。

## 类与方法调用链

```text
AgentConversationController.stream
 → AgentSseService.open
    → AgentConversationService.prepare：本人、过期、并发检查并领取生成资格
    → 返回SseEmitter，进入受限后台任务AgentSseService.execute
       → start
       → AgentConversationService.stream
          → AgentHistory.messages
          → TradeAgentService.chatTurnStream
             → runTurn
             → streamModel
                → OpenAiChatModel.stream
                → MessageAggregator.aggregate：保留完整结果，同时逐帧转发delta
             → 若模型提出工具调用：reset
                → ToolCallingManager.executeToolCalls
                → 既有订单/RAG工具
                → 新一轮streamModel
             → 返回完整AgentTurn
          → AgentStream.commit
             → AgentConversationService.finish：核对令牌/版本，裁剪并保存完整历史
       → done：AgentChatResponse
       → 结束连接，释放并发名额
```

开始生成前检查失败仍返回HTTP400/401/403/404/409/503，未进入模型调用。工具与模型网络请求不在数据库事务中。当前来源只由本次实际工具执行收集，不继承历史来源。

## API和事件协议

新增POST /api/agent/conversations/{conversationId}/messages/stream。请求JSON仍是{"message":"问题"}，最多2000字符；登录Cookie和CSRF头沿用现有方式。响应为text/event-stream，含Cache-Control: no-cache和X-Accel-Buffering: no。

未来页面需要使用fetch发送POST并读取响应流；原生EventSource只能GET，不直接适用于此接口。本阶段没有新增页面。

| event | JSON data | 客户端含义 |
|---|---|---|
| start | {} | 本次流已开始 |
| delta | round与text | 将本轮增量追加到暂存正文 |
| reset | round与空text | 当前模型轮次转为工具调用，清除暂存正文 |
| done | 完整AgentChatResponse | 保存成功，以最终answer为准并展示本轮来源 |
| error | code与message | 请求失败，部分正文不能当成功答案 |

示例报文：

```text
event:delta
data:{"round":2,"text":"订单已关闭。"}

event:done
data:{"answer":"订单已关闭。",...}

```

data先做JSON编码，正文中的换行转成JSON转义，不破坏SSE帧边界。不会将工具JSON参数增量或原始工具结果直接推送给客户端。start/delta不代表历史已保存，done只在保存成功后发送。

## 完整性、预算与取消

一个模型轮次可能先说“我来查询”再提出工具调用，因此前面的delta只能暂存。检测到完整工具调用后发送reset，聚合完成的工具参数才交给原工具执行器；不逐段执行JSON参数，也不执行未注册工具。最终stop且正文非空才返回完整轮次，截断、空流、超时等均失败，不保存半轮。去除被reset的文字后，最终轮次delta拼接结果必须等于最终answer。

Spring AI1.1.8启用streamUsage时对输入帧做一帧前瞻；增量可能稍后一个帧到达，这仍是真实上游流。MessageAggregator会合成缺失用量的0，EmptyUsage.getNativeUsage又是空Map而非null；当前显式识别EmptyUsage，只使用上游实际Usage。独立usage尾帧可能覆盖聚合完成原因，因此从真实帧另外保存最后一个非空完成原因。缺少供应商usage时totalTokens为null，不猜测Token数。

默认单次模型10秒、一次Agent执行45秒、模型/工具各最多4次，仍沿用AgentProperties。blockLast使用“剩余总时限与模型超时的较小值”，限制绝对持续时间；持续到达增量也不能无限续命。没有为了流式再追加一轮润色调用。SseEmitter额外5秒用于容器生命周期收尾，不扩大模型预算。

每个Java实例最多8个流式任务，执行器使用SynchronousQueue，不排队；已满返回503。该上限不是Redis集群共享限额。worker使用Java21虚拟线程，仍受8个名额约束。

容器检测连接断开、写入失败或超时后，取消上游订阅/工作任务，不再执行新工具，释放本次生成资格。网络断开只能在容器感知或后续写入失败时发现，不承诺物理断开瞬间取消，也不保证供应商已停止计费。清空会话通过既有revision/令牌检查阻止旧请求提交或done。

取消和数据库提交按AgentStream的锁确定先后：取消先发生则不保存；提交先完成而随后连接断开时，数据库可能已有完整历史，但客户端没收到done。本阶段不实现两端原子提交、自动重试、幂等消息、断点续传或事件重放。

## 验证证据

本机可控上游流验证：首段到达时上游尚未完成；工具参数分片最终只执行一次；工具轮次reset；缺usage返回null；截断失败；未知工具拒绝；模型次数预算；连续输出仍受总deadline限制；取消后不执行工具。

真实测试MySQL验证prepare前检查、同会话并发、完整成功保存、取消不保存、清空使旧请求失效，复用了前阶段归属/过期/接管/历史裁剪用例。MockMvc验证登录、CSRF、消息和UUID校验、可信用户及异步dispatch响应。

真实随机端口Tomcat＋Java HttpClient＋Session/CSRF＋测试MySQL、可控模型替身验证三条链路：

1. 客户端收到首段后，模型仍在等待客户端测试信号；此时数据库无新历史。放行后收到done，历史已提交。增量中的换行正确JSON转义。
2. 客户端关闭连接，生成任务结束、令牌释放，历史没有半轮记录。
3. 增量后模型失败：HTTP已是200，通过error事件code502终止，没有done，没有新增历史。

真实DeepSeek＋本地BGE＋测试MySQL，两个连续用户请求通过：

| 请求 | 实际工具 | 最终回答增量数 | 最终回答首段/保存结束 | Token |
|---|---|---|---|---|
| 本人9301＋支付规则 | get_my_order、search_trade_rules | 296 | 1815/3102毫秒 | 3127 |
| “那它现在是什么状态” | 再次get_my_order，读到更新后的CLOSED | 96 | 1965/2305毫秒 | 4308 |

累计7435Token，仅为两次本机观察，不是性能或总体准确率基准。首问在工具前还有被reset的暂存增量，不计入表中最终回答增量数。事件/时序/响应存被Git忽略的tools/agent-sse-live-results.json，日志为tools/agent-sse-live.log。此真实模型用例验证Agent流和会话保存，不是经过Nginx的端到端真实模型验收；HTTP传输另由上述真实Tomcat测试验证。

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress "-Dtest=AgentSseDeepSeekLiveTest" "-Dagent.sse.live=true" test
```

最终定向回归103项通过，0失败、0错误、0跳过，离线package成功，未执行全量回归、未重复旧真实模型场景：

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress "-Dtest=AgentConfigurationTest,AgentToolsTest,TradeAgentProtocolTest,AgentApiTest,AgentOrderIntegrationTest,UserOrderPageApiTest,BgeEmbeddingModelTest,RuleDocumentsTest,RuleKnowledgeBaseTest,RuleRetrievalEvaluationTest,AgentHistoryTest,AgentConversationIntegrationTest,AgentConversationApiTest,SecurityWorkflowTest,TradeAgentStreamTest,AgentSseServiceTest,AgentSseApiTest,AgentSseHttpTest" package
```

Nginx1.30.6对single、cluster、trade-drill三个配置的nginx -t均通过；未启动/重载代理验证流式请求，不把语法检查写成代理运行验收。未部署Docker。

## 学习与边界

先讲上述类＋方法的总流程，再讲AgentStream、AgentSseService和原Service/Controller的接入改动；短类合并、长类单独，每段只解释大概作用与主要变量，SQL和测试文件不展开。代理配置与安全配置改动也覆盖。实现验证完成后停下，不自动开发页面或提交推送。

可以据实际实现讲解Spring MVC SSE、真实模型增量、工具轮次汇总、取消传播、会话提交与客户端完成边界。没有实现完整聊天前端、消息重放、多主机容量治理或生产规模验收。
