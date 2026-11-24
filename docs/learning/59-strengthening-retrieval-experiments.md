# 补强 14 · 重排序、按需改写与向量存储

日期为 2026-11-24。[实施计划](../superpowers/plans/2026-11-24-strengthening-14-retrieval-experiments.md)

## 配置与调用链

- `app.agent.rag.rerank-enabled=true` 打开重排序。`RuleKnowledgeBase.search()` 为当前检索方式取最多五个合格候选，`RuleReranker.rank()` 按查询字符二元组在原文中的覆盖率重排，再取配置的 Top K。分数相同沿用原候选顺序，分数表示覆盖率。证据片段 ID、原文、行号及检索阈值沿用原路由。
- `app.agent.rag.query-rewrite-enabled=true` 打开按需改写。`TradeAgentService` 取得最近一条历史用户消息，授权和参数校验通过后，仅对 `search_trade_rules` 调用 `RuleQueryRewrite.resolve()`。当前用户问题和工具查询须符合泛化追问格式，历史里须有且只有一个可识别的规则主题。改写只前置历史里确实出现的公开术语。多主题、已明确的当前主题、订单指代和空历史走原查询，助手输出与工具结果不参与规则主题判断。
- 两个开关默认关闭。重排序用于比较已经召回的候选，按需改写只用于规则搜索。两种实验均在 JVM 本地计算，沿用一次 BGE 检索，未增加上游请求或模型费用。

## 对照口径

重排序重用补强 13 的 12 道固定留出题。两组取同一候选池，BGE 阈值 0.55、最多五个候选、输出前三条。用预先标注的文档和原文片段一起判定证据命中。重排序自身无法补召回或独立判断是否应拒绝。

改写使用另外八道固定追问。四道是单一公开规则主题的泛化追问，另四道覆盖多主题、显式新主题、具体订单和空历史。比较原查询与改写查询在同一 BGE 配置下的证据命中，并核对改写有没有加入意外术语。

## 本轮结果

| 实验 | 原方案 | 实验方案 | 本地额外计算中位数 |
| --- | --- | --- | ---: |
| 重排序 | 证据 8/9、无证据拒绝 3/4、MRR 0.759 | 证据 8/9、无证据拒绝 3/4、MRR 0.778 | 0.299 ms |
| 按需改写 | 泛化追问证据 0/4 | 泛化追问证据 4/4，其余四题保持原查询 | 0.129 ms |

重排序只在 h13-06 把超时证据从第 3 位提到第 2 位。h13-05 的 404 条款未被候选召回，重排后仍缺失。h13-10 关于会员和积分的无证据问题仍误召回取消规则。改写的四个正例均使用上一条用户消息里的术语。样本只覆盖预设的泛化问法和单主题规则，无法估计更自由的多轮语义质量。两项实验继续默认关闭。

逐题报告为 `tools/evaluation-baselines/retrieval-experiments-v1-14278984867134734308.json`。报告含逐题原文、排序、改写前后查询、源码和题集摘要，以及局部耗时。局部耗时不代表生产延迟。

## 向量库决策

当前公开规则来自五篇仓库文档。对照实验实测为 17 个片段、512 维，序列化索引 115,627 字节（约 113 KiB）。业务订单由 MySQL 和现有权限校验读取，规则库没有用户或租户级元数据过滤要求。索引带有规则、分块和模型指纹，随构建版本可追溯。保留 `SimpleVectorStore` 与可重建的本地索引。

| 方案 | 与当前需求的匹配 | 需要重新评估的条件 |
| --- | --- | --- |
| 本地索引 | 依赖最少，随实例加载同版本公开资料 | 内容改为高频独立更新，或并发、容量测试发现需要共享服务 |
| pgvector | 可在 PostgreSQL 中联合管理向量和结构化元数据 | 跨实例共享、更新及过滤形成真实需求后优先评估。当前项目用 MySQL，采用它需要额外维护 PostgreSQL 和备份恢复 |
| Milvus | 支持专用向量查询及元数据过滤 | 规模、吞吐和扩展需求经实测且超出现有方案时再比较；引入专用服务增加部署与运维范围 |

未来提出迁移时，另行核对 512 维模型和指纹、中文证据对照、过滤及权限、索引重建、备份恢复与完整部署流程。本次评估未安装或运行外部向量库。 [pgvector 官方仓库](https://github.com/pgvector/pgvector) · [Milvus 过滤搜索](https://milvus.io/docs/filtered-search.md)

## 复现入口

```powershell
mvn '-Dtest=RuleRetrievalExperimentsEvaluationTest,RuleKnowledgeBaseTest' '-Dagent.evaluation.retrieval-experiments=true' test
```

本地 BGE 文件复用上一阶段，可用 `agent.evaluation.bge-directory` 指定。默认 CI 跑开关、规则边界及实际工具协议单测。

本地 `mvn -Pci verify` 通过 79 项。真实 BGE 检索及索引专项另通过 4 项。默认配置下，隔离 MySQL 与本地模型替身通过 Agent v3 的 15 个场景、17 次提问。报告为 `tools/evaluation-baselines/agent-v3-local-1133588942848965979.json`。日志为 `tools/strengthening-14-ci.log`、`tools/strengthening-14-retrieval-final.log`、`tools/strengthening-14-agent.log`。
