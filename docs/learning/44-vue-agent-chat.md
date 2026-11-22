# Vue前端第六阶段：Agent聊天与SSE

实施日期：2026-11-22。接入现有Java Agent，不新增依赖、不修改Java业务或数据库结构。没有提交或推送。

## 本阶段业务

登录用户进入/agent，输入问题后自动创建会话，通过POST请求读取SSE。回答逐段显示，最终确认后可展开实际规则来源。支持连续追问、停止生成、新会话和确认清空历史。助手仍只读查询本人订单和规则，不代为支付、取消或退款。

只有done代表服务端成功保存本轮历史。断流、停止、超时保留临时文字并标为未确认，不展示本轮来源，也不自动重新发送。reset会撤回中间草稿，done以完整answer替换草稿。

目前后端没有GET历史接口。sessionStorage仅按用户保存conversationId，不保存正文或来源；刷新后可以继续使用服务器上下文，但页面明确提示旧消息未重新加载。当前内存最多显示六轮，不能将它当成完整历史查询。切换账号会清空当前显示并停止旧请求，同账号路由返回可保留已完成消息。

## 运行代码改动量

| 文件 | 改动量 |
|---|---|
| frontend/src/types/agent.ts | 新增 13 行 |
| frontend/src/utils/agentStream.ts | 新增 89 行 |
| frontend/src/api/agent.ts | 新增 19 行 |
| frontend/src/stores/agent.ts | 新增 123 行 |
| frontend/src/views/AgentChatView.vue | 新增 53 行 |
| frontend/src/api/http.ts | +26 / -8 |
| frontend/src/router/index.ts | +1 / -0 |
| frontend/src/layouts/AppLayout.vue | +1 / -0 |
| frontend/src/styles/main.css | +27 / -0 |

## 短文件：类型、API和入口

types/agent.ts定义RuleSource、KnowledgeSource、AgentResponse，字段对应真实Java响应；CreatedConversation是创建结果，AgentEvent区分start/delta/reset/done，ChatMessage记录页面角色、文字、状态及最终结果。

api/agent.ts的createConversation调用postJson创建，先验证外部响应结构；clearConversation通过writeJson发送DELETE；streamMessage通过postEventStream发送问题，核对Content-Type后把body交给readAgentStream。expected记录身份版本，每个事件回调前再次检查，旧账号事件不能更新新页面。

router/index.ts新增需登录的/agent；AppLayout.vue为已登录用户增加交易助手导航。styles/main.css新增聊天滚动区域、消息、来源、输入区和窄屏样式。

## http.ts的修改

onSessionChange登记订阅并返回解除订阅函数；advanceSession增加身份版本后同步通知聊天状态，让退出开始时就停止旧流。readJson、writeJson和postJson增加可选signal，把取消/期限传入CSRF读取及业务请求。postEventStream读取当前CSRF，再发送带JSON和Accept: text/event-stream的POST，复用Cookie、HTTP错误与身份检查。

普通请求继续使用默认15秒期限；聊天发送用自身75秒期限，新建/清空用30秒期限。DeepSeek密钥由Java本机配置读取，不会传给前端。

## agentStream.ts：网络字节如何变成事件

readAgentStream先获取reader和UTF-8 decoder。buffer保存尚未形成完整行的文字；name保存event名称；lines保存data行；started记录是否收到start；completed保存最终结果。

reader.read读取网络块，decoder跨块解码中文。解析器按换行拆SSE，兼容跨块CRLF、注释和多行data；line处理空行结束的一条事件，解析JSON并按事件类型调用onEvent。网络块与事件不是一一对应，所以必须保留buffer。

response校验done的回答、工具统计和来源结构；非法JSON、坏UTF-8、未知事件或缺done结束会抛可见错误。AgentStreamError携带服务端code，供store解释404、409等。Promise.race使等待reader时也能取消；finally移除监听、取消reader并释放锁。

## stores/agent.ts：会话业务状态

conversationId定位服务器会话；messages驱动页面；busy和action控制按钮；notice和error展示过程。controller负责取消，generation区分本次操作和已作废的旧操作，nextId给消息分配页面编号。canSend/canStop是按钮可用条件。

remember按用户保存编号；restore恢复编号和提示。interrupt作废旧操作并停止请求；onSessionChange及身份watch同步清空旧数据。鉴权重新确认成功时可恢复当前用户编号；作用域结束解除订阅。

send先验证问题，增加用户行和助手草稿，需要时创建会话。随后调用streamMessage：start改为生成中，delta追加文字，reset清空草稿，done保存最终回答及来源并标为完成。assistant从响应式数组取得，因此修改文字会更新Vue。

catch对断流、HTTP错误、超时或主动停止给出状态，清除不可信来源；404移除失效编号。failure集中解释错误，不自动重发。finally仅允许仍有效的本次操作解除busy，避免旧请求干扰新操作。

stop取消发送；change供newConversation和clear共用，新建成功才换编号，DELETE成功才清空消息；失败保留当前显示。leave在离页时停止未完成请求，已完成消息可继续保留在内存。

## AgentChatView.vue：页面与交互

question是输入，confirming控制清空确认，log指向滚动区域，stateLabels将状态转为中文。submit取输入并调用agent.send；keydown处理Enter发送、Shift+Enter换行，避免输入法选字触发发送。create/clear转交store，身份watch清空输入和确认状态。

消息变化后的watch先判断是否接近底部，nextTick后才跟随滚动；用户上翻阅读时不强制拉回。onUnmounted调用leave。模板只插值输出纯文本，不执行模型返回HTML，也不把来源路径当可点击外部链接。HTML/CSS仅承担布局、状态、按钮和来源展开。

## 总调用流程

AgentChatView.submit → useAgentStore.send → createConversation（首次需要时）→ streamMessage → http.postEventStream → Java AgentConversationController.stream → AgentSseService.open（先调用AgentConversationService.prepare）→ 后台execute → AgentConversationService.stream → TradeAgentService.chatTurnStream → DeepSeek及业务工具。

返回方向：Java发送SSE → readAgentStream读取/拆事件 → streamMessage检查身份 → store的onEvent修改messages → Vue响应式更新聊天文字。收到done后以完整结果替换草稿并展示来源。Java的模型增量经AgentStream.emit触发open中登记的回调，再由AgentSseService.send通过SseEmitter发送；会话服务完成历史提交后，execute才发送done。

清空：AgentChatView.clear → store.clear/change(true) → clearConversation → HTTP DELETE → Java清空会话历史 →204 →移除页面消息。新会话通过createConversation取得新编号。刷新仅restore编号，不虚构已加载历史。

## 验证证据

- 42项前端测试全部通过，vue-tsc及Vite生产构建通过；Agent路由块12.75kB，入口81.38kB（未压缩）。新增12项Agent检查覆盖拆块中文、事件与来源格式、取消、CSRF、身份切换、断流、新建异常和清空失败保留。独立复核发现新建返回null会产生TypeError，已用失败再通过的回归修复。
- 受控浏览器验证真实增量/reset/done、来源、纯文本脚本载荷、停止和离页、403/404/409、刷新、账号切换、390px布局；同组检查在构建预览4173再次通过。受控服务不能证明真实模型质量。
- 真实Chrome → Vite → Java →测试MySQL → DeepSeek（deepseek-flash）及RAG：本人订单工具与规则工具各一次，首问收到404个delta，3条实际知识来源，2次模型调用，耗时2977ms；追问1次模型调用、无工具，耗时1337ms，正确引用上下文。上述为本次样本，不是性能基准或准确率统计。
- 测试库确认问答后分别保存1轮、2轮；清空后为0轮。另一账号发送或清空他人会话均为真实404，未触发模型。页面没有保存正文到sessionStorage，浏览器pageErrors为0。
- 联调共发起3次真实模型问答请求，其中首次因验证脚本读取已取消的响应体失败，后续改为克隆同一响应观测SSE，再完成两次问答；未增加额外模型请求来读取事件。

## 运行前提与边界

最初联调时开发库缺少014两表，所以真实模型联调使用测试库及临时启动参数。2026-11-22用户随后授权迁移，已在开发库limited_trade_platform执行sql/014_agent_conversation.sql：两张新表均为空，外键和轮次ON DELETE CASCADE已核对，原14张表的SHOW CREATE TABLE及行数前后相同。这里只验证了开发库结构，没有在开发库执行模型问答；config/agent.properties中的enabled=false、密钥及原有RAG索引没有修改。日常运行仍需核对并启用服务端Agent/RAG配置。

没有接入Markdown渲染、会话列表或历史读取、写操作工具；这些均不属于本阶段。下一阶段是整体联调与运行交付核对，具体范围届时确认。

## 教学定位

以下为本次交付版本的行号，后续改动可能移动。

- [frontend/src/types/agent.ts](/D:/codex/Java/limited-trade-platform/frontend/src/types/agent.ts:1)：1—13行。
- [frontend/src/api/agent.ts](/D:/codex/Java/limited-trade-platform/frontend/src/api/agent.ts:1)：1—19行。
- [frontend/src/utils/agentStream.ts](/D:/codex/Java/limited-trade-platform/frontend/src/utils/agentStream.ts:1)：1—89行。
- [frontend/src/stores/agent.ts](/D:/codex/Java/limited-trade-platform/frontend/src/stores/agent.ts:1)：1—123行。
- [frontend/src/views/AgentChatView.vue](/D:/codex/Java/limited-trade-platform/frontend/src/views/AgentChatView.vue:1)：1—53行。
- [frontend/src/api/http.ts](/D:/codex/Java/limited-trade-platform/frontend/src/api/http.ts:1)：1—135行。

新增的测试文件frontend/tests/agent.test.ts仅用于验证，不作为源码讲解内容。导航、路由和样式修改见上方改动表。

收尾已核对：2个测试账号、1个商品、2笔订单及会话样本均已清理；临时5173、4173、18737、18739端口均无监听。受控/真实浏览器脚本、截图及结果在ignored tools目录，未纳入源码提交范围。
