# 补强 13 · 中文关键词、向量与 RRF

日期为 2026-11-24。[实施计划](../superpowers/plans/2026-11-24-strengthening-13-hybrid-retrieval.md)

## 生效方式

`app.agent.rag.retrieval-mode` 支持 `vector`、`keyword`、`hybrid`。默认 `vector`，继续使用既有 BGE、Top K 和余弦阈值。`keyword` 单独使用术语索引，启动不加载 BGE。`hybrid` 复用已生成的向量索引并建立关键词索引。当前五篇公开规则和分块内容相同，向量索引可复用。本阶段无需迁移。

## Java 调用链

`RagConfiguration` 按模式装配需要的模型。`RuleKnowledgeBase.search()` 校验检索输入并选择路由。向量路先经过现有相似度阈值，关键词路由 `RuleRanking.keyword()` 查阅启动时缓存的标题和原文术语。关键词提取先做 Unicode 归一化和大小写归一，英文标识按完整词匹配。候选需命中一个精确术语，或在同一片段命中至少两个相关术语。关键词分数按术语权重和在语料中的出现频率计算。

混合模式独立执行两路搜索，每路最多五个候选。`fuse()` 按片段 ID 合并，每一路按名次贡献 `1 / (60 + rank)`，同一路同一个 ID 只计一次。融合只累加排名贡献，最终返回配置的 Top K。原片段 ID、文档、行号和正文随结果返回。`score` 在三种模式下分别表示余弦分、关键词分、RRF 分。没有候选仍返回 `NO_EVIDENCE`。实时订单事实仍走本人订单工具。

## 固定留出结果

同一批 12 题测试三种模式，配置为 320 字符分块、Top 3、向量阈值 0.55。八道正例要求九条独立证据，四道负例为规则库中无答案。证据命中同时要求文档 ID 和预先标注的原文短句匹配，只有相关文档标题不计命中。时间为各路预热后本地单次测量的中位数。

| 模式 | 证据召回 | 正例完整命中 | 负例拒绝 | 证据 MRR | 中位耗时 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 向量 | 8/9 | 7/8 | 3/4 | 0.759 | 9.63 ms |
| 关键词 | 5/9 | 5/8 | 4/4 | 0.444 | 0.19 ms |
| 混合 | 8/9 | 7/8 | 3/4 | 0.759 | 10.49 ms |

`findOwnedOrder` 题上，关键词帮混合模式命中了订单归属文档的源码依据段，包含 404 业务语义的原文段仍未召回。向量和混合均误把“取消订单后的平台积分和会员等级”识别为有证据，返回的取消规则只说明库存、活动名额及优惠券。关键词路线能够拒绝此题，也漏掉多证据题和发货变更题。此批题上混合模式没有提高原文证据召回或拒绝能力，因此保留向量作为默认。测试记录的是检索结果，未用真实模型验证回答。

## 复现

```powershell
mvn '-Dtest=RuleHybridEvaluationTest,RuleKnowledgeBaseTest' '-Dagent.evaluation.hybrid=true' test
```

复用补强 12 的本地 BGE 文件，或用 `agent.evaluation.bge-directory` 指定目录。报告保存在 `tools/evaluation-baselines/hybrid-v1-2685150185648066138.json`，含题集哈希、分路原文、分数、排名及每题耗时。不同机器的时间不可直接视为生产延迟。

`mvn -Pci verify` 本地通过 75 项，BGE 检索与索引专项另通过 4 项。默认向量模式在隔离 MySQL 和本地模型替身下重跑 Agent v3 的完整 15 场景、17 次提问，全部通过。日志位于 `tools/strengthening-13-ci.log`、`tools/strengthening-13-retrieval.log`、`tools/strengthening-13-agent.log`。Agent 报告为 `tools/evaluation-baselines/agent-v3-local-5592527647543924577.json`。
