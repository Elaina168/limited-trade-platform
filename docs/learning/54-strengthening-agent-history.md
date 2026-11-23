# 补强 9 · 会话列表与历史读取

日期：2026-11-23。[实施计划](../superpowers/plans/2026-11-23-strengthening-09-agent-history.md)

## API 与保留策略

| 路径 | 行为 |
| --- | --- |
| GET /api/agent/conversations | 只列本人未过期会话，按最近活动倒序。默认第 1 页、每页 5 条，最多每页 10 条，用 `hasNext` 判断是否还有下一页 |
| GET /api/agent/conversations/{conversationId}/messages | 读取本人最近 50 轮已提交问答，按时间由早到晚，`generating` 表示当前生成资格是否有效 |
| DELETE /api/agent/conversations/{conversationId}/messages | 清空展示与模型上下文，保留会话和名额占用，旧回答不能写回 |
| DELETE /api/agent/conversations/{conversationId} | 删除会话与两类历史，释放名额，旧回答不能写回 |

每个账号最多 10 个未过期会话。创建、成功提交新问答或清空时重新计算 24 小时有效期，读取不续期。过期会话立即退出列表和读取范围，实体由既有按用户触发的过期清理回收。缺失、过期和他人会话返回 404。删除和清空仍要求 CSRF。生成中可读取此前已提交的快照，暂态片段不进入历史。其他标签页通过重新读取看到变更。

## Java 调用链

`AgentConversationController` 从认证主体取得用户 ID，`list()` 与 `readHistory()` 只读取本人会话。分页多取一条计算 `hasNext`。历史读取在会话行锁内一起取得有效期、生成状态与已提交的问答。删除通过同一所有权检查后删除主会话，子记录级联清理。

`AgentConversationService.finish()` 在校验 `revision` 和生成令牌后，将模型上下文和展示问答写入同一事务。模型上下文保持最多六轮和 24,000 字符的旧规则。展示记录取原问题、最终答案及公开来源，按最近 50 个完整轮次独立保留。工具参数、工具返回、草稿及运行指标不会通过历史接口返回。清空会递增版本并撤销生成资格，删除直接移除会话。

## Vue 调用链

`api/agent.ts` 增加了分页、历史读取和删除。页面挂载调用 `agent.open()`；Pinia 的 `browse()` 读取列表，优先恢复此前选中的会话，已过期或删除时选最新的有效会话。服务器提供正文，浏览器会话存储仅保存按账号区分的会话编号。点击会话后，`showHistory()` 把每个轮次转换成问题和回答两行。页码只影响会话列表。身份切换会中止旧请求并清空视图状态，请求代次 `generation` 阻止迟到结果覆盖新状态。

## 迁移与验收

已有 014 的数据库在停下 Agent 会话写入后，执行一次 015 再启动新版本。新容器空数据卷的初始化清单包含 015。迁移保留原模型表，只恢复旧表中仍能确认的首条用户问题与最终回答。旧表未记录公开引用，迁移行的来源数组为空。已裁剪、损坏或未完成的旧记录不会被补造。开始补强 10 前已在本机开发库应用并验证 015，当时旧会话与旧轮次均为 0。已有 Docker 数据卷尚未迁移。

- 隔离 MySQL 的 Java 专项 19 项通过，覆盖分页、所有权、生成中读取、清空与删除、50 轮展示和六轮上下文的独立保留。
- Vue 类型检查与构建成功，前端测试 43 项通过。Edge 通过 Nginx 和本地模型替身完成刷新、重登、隔离账号、分页选择、流式生成和停止、清空、删除。两轮模型调用均指向回环替身。
- 旧库迁移用隔离 MySQL 预先写入完整、损坏及未完历史，验证只恢复完整的公开问答，原模型上下文逐字节保留。保留的本机证据见 `tools/strengthening-09-tests.log`、`tools/strengthening-09-vue-tests.log`、`tools/strengthening-09-browser.log` 和 `tools/strengthening-09-migration.log`。
