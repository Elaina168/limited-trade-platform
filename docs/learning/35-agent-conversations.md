# 第三十五阶段：Agent多轮会话

本阶段为已有只读Agent增加MySQL会话记忆，支持“查我的待支付订单”之后继续问“第一笔现在什么状态”。历史帮助理解指代，当前业务事实仍重新调用本人订单工具。没有新增依赖、数据库服务、SSE或前端页面。

## 新增代码与调用链

新增6个生产类型：

| 类型 | 作用 |
|---|---|
| AgentTurn | 返回本轮最终响应与本轮完整消息，供会话层保存 |
| conversation/AgentHistory | 序列化消息角色、正文、工具调用及结果，按完整轮次裁剪 |
| conversation/AgentConversation | 数据库会话记录，包含归属、版本、生成资格和过期时间 |
| conversation/AgentConversationMapper | 会话与轮次的MyBatis数据库操作 |
| conversation/AgentConversationService | 创建、领取生成资格、调用Agent、保存成功轮次及清空 |
| conversation/AgentConversationController | 三个新HTTP接口，使用认证主体，不接受客户端身份或历史 |

其他改动：TradeAgentService.chatTurn接收服务器历史并返回AgentTurn，旧chat委托空历史以保持原单轮接口；SecurityConfig显式允许新接口且要求登录；sql/014_agent_conversation.sql新增两张表；全新容器的初始化清单加入014脚本。容器文件仅静态调整，未进行真实Docker部署。

```text
AgentConversationController.chat
 → AgentConversationService.chat
 → 短事务：清理本人过期会话、锁定本人会话、领取生成令牌、读取历史
 → AgentHistory.messages：反序列化完整历史
 → TradeAgentService.chatTurn：当前系统消息＋历史＋当前问题
 → 既有DeepSeek/只读工具执行循环
 → 返回AgentTurn
 → 短事务：再次核对令牌、版本及到期时间，保存与裁剪历史
 → 返回AgentChatResponse
```

模型网络请求在会话短事务外。成功保存之前不向调用者返回成功。历史保存失败时错误向上传播，不静默丢失记忆。

## API

Agent总开关app.agent.enabled=true时启用，继续使用已有config/agent.properties中的DeepSeek配置。RAG仍是独立开关；无RAG也可以多轮使用旧规则工具。本轮没有修改本机密钥和默认开关。

全部新接口要求Session登录与CSRF：

| 方法与路径 | 请求与响应 |
|---|---|
| POST /api/agent/conversations | 无需请求体；201，返回conversationId和expiresAt |
| POST /api/agent/conversations/{conversationId}/messages | JSON：{"message":"第一笔现在什么状态？"}；200，返回AgentChatResponse |
| DELETE /api/agent/conversations/{conversationId}/messages | 清空历史并使旧生成资格失效；204 |

conversationId是创建时由服务器生成的UUID；客户端保存并在后续URL中使用。它表示Agent对话，不是登录Session的JSESSIONID。消息沿用ChatRequest校验：非空、最多2000字符。不存在、过期或他人的会话统一404，未调用模型；并发生成、版本失效或超过十会话上限返回409。格式错误UUID返回400。其他模型、工具和超时错误沿用原Agent处理。

原POST /api/agent/chat仍为单次无记忆问答，不会自动生成会话。新响应复用AgentChatResponse，conversationId由创建响应提供，不新增到每次聊天响应中。不提供会话列表、历史读取或管理界面。

## 数据表与迁移

014脚本依赖既有app_user表，按013之后执行：

- agent_conversation：id、user_id、revision、generation_token、busy_until、expires_at。
- agent_conversation_turn：conversation_id、turn_no、messages_json；会话删除时级联删除轮次。

本轮只在limited_trade_platform_test新建了两表；没有在开发库执行DDL。启动开发服务前，连接开发库执行sql/014_agent_conversation.sql一次。脚本不包含DROP、不覆盖现有表；不要在已迁移的库重复运行。不改变交易表、用户或订单业务数据。新容器空数据卷会通过初始化清单执行014，已有数据卷需要单独迁移。

## 记忆与并发规则

保存最近6个完整成功轮次，历史JSON总长度上限24,000个Java字符串字符单位，不是精确Token预算。工具调用和结果保持配对，SystemMessage不保存，每次重新构造当前系统提示与查询时间。客户端不能提交历史或系统角色。

裁剪从最新轮次向前选择连续完整轮次，达到条数或字符上限就停止。若最新单轮本身超过24,000字符，会清空后续可用历史，避免跳过最新问题却继续使用更早上下文；当前回答仍正常返回。需要后续追问时用户可能要再次明确订单。无需长期摘要，本阶段没有压缩模型或语义记忆。

只保留成功完成的轮次。工具错误、模型失败、超时或存储失败不保存半轮；已有成功历史保留。存储包含用户文字和必要订单摘要，工具依旧通过AgentOrderSummary过滤地址、手机号、运单与requestId；用户自己在问题中输入的信息也会随用户消息保存，不能宣称自动实现了个人信息脱敏。

每用户最多10个未过期会话，创建时使用既有AppUserMapper.lockById锁定用户行，防止并发突破上限。会话创建、成功问答、清空后有效期为24小时；访问或创建时清理当前用户过期数据，没有后台全局清理任务，也没有专门的会话删除接口。数据库备份中的历史按既有备份策略保留。

同一会话领取唯一generation_token，期限为当前requestTimeout加30秒。有效期内的并发请求返回409；进程中断后资格到期可被新请求接管。提交时检查本人归属、会话过期、revision、令牌和资格有效期。清空会删除历史、增加revision并释放资格，因此旧请求不能写回旧历史或返回旧结果。失败时只按自己的令牌释放资格，不影响后来接管的请求。不同会话可独立执行。

这些数据库检查确定性执行；模型是否按提示重新查询、是否正确理解指代仍具有概率性。没有把模型提示当作授权保障。当前来源数组只收集本次实际执行的规则工具结果，历史规则不混进本次sources或knowledgeSources。

## 验证与实际问题

真实测试MySQL覆盖：归属、过期清理、历史重载、清空、六轮限制、超大单轮、失败恢复、资格过期接管、旧结果无法覆盖新轮次，以及12个并发创建请求只能成功10个。损坏历史会明确失败并释放本次资格，不降级为空历史。HTTP测试覆盖三接口的登录、CSRF、UUID、消息长度、主体身份和404/409；协议替身测试覆盖完整工具配对、历史注入、新系统消息、当前来源及重新执行查询读取更新后的状态。

第一次真实DeepSeek执行2个测试场景、5次用户请求，两个场景均有失败点：

1. 订单追问成功重新读取CLOSED状态；但清空后模型查询列表并猜测“第一笔”是最早订单。
2. 规则追问直接使用历史检索片段，没有进行本轮检索。

保留首次结果tools/agent-conversation-live-initial-results.json和日志。随后补充提示：无法唯一确定指代时先澄清，每个规则问题都需本轮重新查询。修正后再次执行两场景、5次用户请求，全部通过：

| 用户请求 | 实际结果 |
|---|---|
| 待支付订单列表 | list_my_orders返回9305、9301 |
| 第一笔现在状态与期限 | 重新get_my_order，读取测试中已更新的9305 CLOSED，期限10:19 |
| 清空后问第一笔 | 不调用工具，先要求明确订单，没有带回旧编号 |
| 取消规则首问 | 本轮search_trade_rules，返回实际片段 |
| 重复操作追问 | 再次search_trade_rules，返回本轮片段并解释幂等 |

最终5次请求各耗时约1.0～2.7秒，累计14,557Token；首次12,879Token，两轮共27,436Token。只是当次观察，不是性能或总体成功率保证，不折算未经核对的费用。最终结果在tools/agent-conversation-live-results.json和日志，全部为Git忽略文件。固定夹具只在测试库创建并显式清理，不向开发库写入数据。多轮测试没有用跨模型网络调用的长事务回滚夹具。

真实模型测试默认不执行；显式运行会访问DeepSeek并产生费用：

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress "-Dtest=AgentConversationDeepSeekLiveTest" "-Dagent.conversation.live=true" test
```

本阶段最终关联回归87项通过，0失败、0错误、0跳过，离线打包成功。未执行全量回归。命令如下，不调用真实模型：

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress "-Dtest=AgentConfigurationTest,AgentToolsTest,TradeAgentProtocolTest,AgentApiTest,AgentOrderIntegrationTest,UserOrderPageApiTest,BgeEmbeddingModelTest,RuleDocumentsTest,RuleKnowledgeBaseTest,RuleRetrievalEvaluationTest,AgentHistoryTest,AgentConversationIntegrationTest,AgentConversationApiTest,SecurityWorkflowTest" package
```

## 学习顺序与简历边界

先讲完整Controller→会话Service→Mapper→Agent→保存的调用链；随后讲两张表和几个关键字段，再讲AgentHistory如何保存完整轮次，以及令牌/revision为什么能处理清空和并发。按用户最新要求，说明每段实现什么及主要变量，遇到疑问再展开。

可据实际实现描述：基于MySQL实现用户隔离的Agent多轮会话，按完整轮次限制上下文，使用短事务与版本令牌控制并发，支持历史清空与当前订单重新查询。不能写成长期语义记忆、自动摘要、SSE、生产规模或已完成开发环境部署。实现验证完成后停止，等待学习与提问，不自动提交推送。
