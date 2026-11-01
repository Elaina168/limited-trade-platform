# Agent多轮会话实施计划

> 使用superpowers:executing-plans在当前会话逐项实施；用户已确认MySQL方案并要求开始，不重复请求实施许可，不启动子代理，不提交推送。

**Goal:** 实现本人多轮会话、有限完整历史与可安全清空的并发控制。
**Architecture:** 复用MyBatis与MySQL，短事务领取带版本的生成资格；模型调用位于事务外，提交前再次验证版本。保留现有单轮接口。
**Tech Stack:** Java21、Boot3.5.16、Spring AI1.1.8、MyBatis、MySQL，无新增依赖。
**Spec:** docs/superpowers/specs/2026-11-01-agent-conversation-design.md（用户已确认）。

## 全局约束

- 保留现有RAG未提交改动与本机密钥配置，默认Agent关闭。
- 最近6个完整成功轮次，总历史24,000字符；单轮过大则不进入后续上下文，并清除更早历史以避免上下文断裂。
- 每用户10个未过期会话，无活动24小时过期，创建或访问时清理本人过期记录。
- Session/CSRF与可信userId；本人以外会话统一404；同会话并发409。
- 不在模型请求期间持有数据库事务；资格有效期采用requestTimeout加30秒，并以每次新UUID令牌、revision及过期时间保护提交。

## Review Focus

1. 清空/过期/接管期间旧请求不得保存或成功返回；失败清理只能释放自己的令牌。
2. 不只保存最终答复；保留完整工具调用配对，淘汰按整轮执行。
3. 历史不成为当前事实或当前来源；每轮重新生成SystemMessage、用户身份和查询时间。
4. 并发创建第11个会话需要用户行锁保护；清理只能作用于当前用户。
5. DB失败真实传播，模型失败不保存半轮；写入失败不可返回成功。

## Task 1：模型消息历史接口

Files：TradeAgentService.java、AgentTurn.java、TradeAgentProtocolTest.java、AgentHistoryTest.java。

- [x] 编写并观察RED：第二轮包含用户/助手历史、工具配对，系统消息只保留当前一次，当前来源不含旧来源。
- [x] 增加chatTurn(long userId,String message,List<Message> history)返回AgentTurn(response,messages)，chat保持兼容委托。完整轮次只在成功后返回；不持久化SystemMessage。
- [x] 用Spring AI Message的Jackson显式DTO序列化成功轮次；验证工具调用、结果、角色及顺序往返不变。
- [x] 运行AgentHistoryTest、TradeAgentProtocolTest，通过后进入存储。

## Task 2：MySQL会话存储与短事务

Files：sql/014_agent_conversation.sql、agent/conversation/AgentConversationMapper.java、mapper/AgentConversationMapper.xml、AgentConversationService.java、AgentConversation.java、AgentHistory.java及真实测试。

- [x] 先写并观察RED：创建、归属、过期、10会话上限、6轮裁剪、24,000字符、清空、失败、并发和旧令牌提交失败。
- [x] 新增会话表和轮次表，带外键级联删除、revision、生成令牌及到期时间。只对测试库执行新表DDL；开发迁移由明确脚本提供，不自动更改开发库。
- [x] 服务通过TransactionTemplate执行短事务；begin读取并锁定本人会话，领取令牌，读完整历史后提交。网络调用在事务外。finish锁定并校验令牌、revision、过期再保存、裁剪并续期。
- [x] clear增加revision并释放资格、删除历史；失败finally仅按本次令牌释放资格。不同会话独立生成。
- [x] 定向真实MySQL测试通过，禁止依赖模型评分验证权限。

## Task 3：HTTP接口与安全

Files：AgentConversationController.java、SecurityConfig.java、AgentConversationApiTest.java。

- [x] RED：登录/CSRF/参数/路由，userId取主体，404/409映射，旧接口不变。
- [x] 实现创建、发送及清空三个设计接口；消息请求继续最大2000字符，UUID路径由Spring解析；Agent启用才创建Controller和Service。
- [x] WebMvc定向测试通过，响应复用AgentChatResponse，不接受客户端历史或身份。

## Task 4：联调、文档与交付

- [x] 新增可选真实DeepSeek多轮测试，覆盖订单指代重新查询、规则追问、清空后澄清；测试库固定夹具，结束清理新增会话。
- [x] 执行关联Agent/RAG回归和离线package；核对真实日志与来源，报告模型实际行为和失败。
- [x] 更新35阶段说明、README、roadmap，记录API、迁移、并发、预算和运行证据。
- [x] 自行审阅本阶段全部差异、UTF-8、密钥扫描、git diff --check，通过后停止，不自动进入下一阶段。

## 决策及执行记录

- 用户已批准具体MySQL设计；沿用当前会话自行实施。计划是已批准设计的细化，不再引入额外审批。
- Task 1：新增chatTurn与显式消息DTO。先观察缺方法/缺编解码器的RED，随后18项历史与协议检查GREEN。
- Task 2：先观察缺Service的RED，新增014两表且仅在测试库执行。初始4项真实MySQL检查GREEN，随后补充并发创建、超大轮次、资格接管、损坏历史等检查。
- Task 3：先观察缺Controller的RED，完成Session/CSRF路由与主体身份；组合27项检查GREEN。
- Task 4：真实DeepSeek首轮两个场景均有失败点，保留5次请求记录；根因是提示未明确限制无历史指代猜测、未明确本轮必须重新检索。增加这两条约束及协议断言后，两场景5次请求全部通过。最终87项定向回归及离线package通过，0失败、0错误、0跳过。
- Ruling: 为避免超大最新轮次被跳过却接上更早上下文，采用从最新轮次向前的连续保留，超大单轮清空后续历史；已在代码、单元/真实DB测试和阶段说明明确。
- Ruling: 只迁移测试库；开发库新增表由014脚本提供，使用前须显式执行。全新容器清单加入014，但未安装或运行Docker。
- 自行审阅：检查事务边界、用户行锁、本人会话锁、revision/令牌接管、清空失效、异常释放、历史工具配对与来源边界。未启动子代理；未发现须继续修改的实现问题。真实模型行为仍是小样本证据，不宣称确定性遵循。
