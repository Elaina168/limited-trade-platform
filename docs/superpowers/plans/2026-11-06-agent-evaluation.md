# Agent轻量评估实施计划

> **For agentic workers:** 使用superpowers:executing-plans在当前会话实施；保留已有未提交改动，不自动提交推送。

**Goal:** 固定12个业务场景，生成完整且可追溯的真实模型评估报告。

**Architecture:** 问题集与判定器均放测试目录；JUnit入口复用AgentConversationService和测试库夹具，事务回滚。报告落到被忽略的tools目录，不新增生产代码或依赖。

**Tech Stack:** Java 21、JUnit 5、Jackson、Spring Boot 3.5.16。

**Spec:** docs/superpowers/specs/2026-11-06-agent-evaluation-design.md

## Global Constraints

- 只使用test profile；真实模型评估默认关闭，显式开启。
- 不安装Docker，不部署，不自动提交或推送；保留现有SSE和前端改动。
- 不输出凭据；自动检查和人工复核分开。

## Review Focus

- 已有固定夹具冲突：插入前失败，不删除未知数据。
- 前面场景失败：继续记录可预期错误；意外异常终止但finally保存部分报告。
- 错误响应不能通过正常回答检查；模型费用未知时不可统计成零。
- 来源必须取自实际返回的knowledgeSources，模型正文自报来源不足以通过。
- 多轮场景不同于请求数，清空和更新夹具不计为模型请求。

### Task 1：问题集与判定、汇总

Files: src/test/resources/agent-evaluation.json；src/test/java/com/elaina/limitedtrade/agent/AgentEvaluation.java；AgentEvaluationTest.java。

- [ ] 先写离线测试，验证错误状态、假来源、不完整运行、未知Token的汇总；运行并观察失败。
- [ ] 实现evaluate(JsonNode, AgentChatResponse, Integer, boolean)返回命名检查；report(...)输出逐项及汇总。
- [ ] 固定12场景，问题不随模型结果调整；离线检查通过。

### Task 2：真实评估入口

File: src/test/java/com/elaina/limitedtrade/agent/AgentEvaluationLiveTest.java。

- [ ] 显式agent.evaluation.live=true才执行，使用事务与测试库，夹具冲突先停止。
- [ ] 每场景新建会话，步骤间允许更新订单状态或清空，调用conversations.chat()，核对订单只读不变。
- [ ] 初始化所有场景为未执行；finally保存唯一文件名报告；预期ResponseStatusException记录，意外异常向外传播；最后对未通过约束使JUnit失败。
- [ ] 单次运行14个模型请求（12场景，其中两场景各两轮）；若意外失败保留已知结果不自动重试。

### Task 3：复核与交付

Files: docs/learning/38-agent-evaluation.md、docs/roadmap.md、本计划与设计状态。

- [ ] 复核全部实际回答，人工意见与自动约束分开保存；摘要注明样本和计时范围。
- [ ] 运行最小相关离线检查及打包，检查新增改动，无关失败单列。
- [ ] 更新阶段文档，保留完整原始报告在tools，结束本阶段。

## 执行记录

用户已批准设计并要求开始；按当前会话自主实施。自审覆盖本阶段新改动，不扩展检查到已有前端/SSE全量工作，也不自动创建新工作树。

2026-11-06执行结果：Task 1、Task 2、Task 3已完成。先观察缺少AgentEvaluation导致的编译失败，再实现并通过4项离线检查。单次真实模型评估完整执行12场景/14请求，自动约束11通过、1失败，原始报告未覆盖。身份伪造实际安全拒绝，保留预设404过窄的失败，不改题重跑；追问规则来源覆盖限制另记。逐项静态复核附件保存在tools。关联验证31通过、1真实模型入口默认跳过，离线package成功；只读SQL确认本次夹具残留为0。

Ruling: 生产代码不因本次结果变更——身份伪造样本没有暴露越权缺陷，规则来源覆盖限制据实报告；扩大修复需另定可验证行为，当前保持设计中的最小范围。自动结果与复核意见分开，不宣称全通过。
