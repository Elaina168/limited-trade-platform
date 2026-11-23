# 补强 11 · Agent 工具授权与审计

日期：2026-11-23。[实施计划](../superpowers/plans/2026-11-23-strengthening-11-agent-tool-access.md)

## 调用链

`SecurityConfig` 对 Agent 的单轮、会话与流式入口统一要求登录角色为 USER 或 ADMIN。Controller 从已认证主体取得用户 ID，创建订单、支付、取消、发货与管理权限仍走原有接口。ADMIN 使用 Agent 时也只查询本人订单。

`TradeAgentService.runTurn()` 为本轮创建 `AgentToolAccess.Scope`，其中保存在服务端信任的用户 ID 与审计关联号。工具调用只暴露四种已注册的只读动作。规则工具仍按 RAG 开关二选一。批次执行前统一校验动作名称和剩余工具次数；每次真正调用前校验作用域、JSON 字段与类型、4,096 字节参数上限。拒绝重复键和附加 JSON 文档。模型参数不含身份输入。

`TradeAgentTools.listMyOrders()` 和 `getMyOrder()` 从作用域取得身份，然后调用 `OrderService` 的本人订单查询。直接调用缺少服务端作用域时返回 403。资源不存在与不属于本人统一为 404。模型只收到必要的订单摘要。工具返回超过 16,384 个 UTF-8 字节时返回 413，内容不会被添加到下一轮请求或引用。补强 10 的整轮上下文预算继续生效。

`AgentToolAccess.record()` 记录受控动作、状态、固定原因和随机 `agentRequestId`。单轮汇总带同一编号。审计日志不会携带模型提交的未知动作名、工具参数、工具输出、对话正文或凭据。直接调用缺少上下文用 `unbound` 记录拒绝。

## 防御回归清单 v1

| 范围 | 固定断言 | 证据入口 |
| --- | --- | --- |
| 登录身份 | HTTP 使用认证主体，非平台角色拒绝，直接工具调用需要服务端上下文 | AgentApiTest、AgentToolsTest |
| 工具与资源 | 仅注册读动作，额度检查在执行前完成，访问其他用户订单返回 404 | TradeAgentProtocolTest、AgentOrderIntegrationTest |
| 资料与工具数据 | 规则来源来自当前实际检索，工具数据仅进入模型数据消息 | TradeAgentProtocolTest |
| 历史上下文 | 按轮配对保留或移除工具调用与结果，订单状态重新查询 | TradeAgentProtocolTest |
| 信息披露 | 工具输出只含订单摘要，拒绝日志只含固定元数据 | AgentToolsTest、TradeAgentProtocolTest、AgentOrderIntegrationTest |
| 长度与额度 | HTTP 长输入拒绝，过长工具返回不出站，独立预算仍约束模型调用 | AgentApiTest、TradeAgentProtocolTest、AgentUsageLedgerIntegrationTest |

清单使用离线契约和本地协议替身。服务端授权可以由这些测试复现，真实模型是否会安全拒绝不可信指令需要独立评估。

## 验证

- 本地 `mvn -Pci test` 通过 71 项测试。真实数据库专项在隔离 MySQL 通过 8 项测试，包括订单归属、流式接口和预算台账。全部模型请求使用本地替身或模拟边界。
- 保留本机日志 `tools/strengthening-11-ci.log` 和 `tools/strengthening-11-integration.log`。隔离服务和临时目录已清理。本阶段没有数据库迁移。
