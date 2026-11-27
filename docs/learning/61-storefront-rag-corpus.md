# 商城 RAG 知识库扩充

日期为 2026-11-24。

## 新增规则

原有 5 篇公开规则共 17 个片段。本轮增加 8 篇，按现有 320 字符配置切分后共有 13 篇、49 个片段。商城的商品、活动和优惠券种子是实时交易数据。RAG 只收录公开规则，库存、本人订单、券可否使用等实时事实仍以业务接口及订单工具为准。

| 新文档 | 实现依据 |
| --- | --- |
| 商品搜索与分类 | ProductService、ProductController、ProductMapper |
| 限时限量活动 | ActivityService、ActivityMapper、OrderService |
| 活动券领取 | CouponController、CouponService、CouponMapper |
| 满减、占用及核销 | OrderService、CouponMapper、UserCouponResponse |
| 购物车与结算 | CartService、CheckoutRequest、OrderService |
| 地址与快照 | AddressService、OrderService |
| 下单重试和限流 | OrderController、OrderService、RedisOrderRateLimiter |
| 助手会话和历史 | AgentConversationService、AgentConversationController、AgentHistory |

`RuleKnowledgeBase` 从仓库的 `knowledge/*.md` 读取材料，按标题、行号和内容生成可追踪片段。默认向量、Top 3、0.55 阈值沿用原配置。为关键词路线补充了新规则术语和完整的 `Retry-After` 匹配。旧向量评测继续使用原来五篇文档，新评测单独记录所有模式与本轮语料指纹。

## 实际检索结果

新增固定 32 题，包括 24 道有原文标注的正例、8 道无证据负例。正例同时核对文档 ID 和标注原文，结果只命中文档标题时不计成功。

| 模式 | 新正例原文命中 | 新负例拒绝 | 旧评估正例 | 旧评估负例拒绝 |
| --- | ---: | ---: | ---: | ---: |
| 向量 | 24/24 | 7/8 | 10/10 | 4/4 |
| 关键词 | 5/24 | 8/8 | 5/10 | 4/4 |
| 混合 | 23/24 | 7/8 | 10/10 | 4/4 |

信用卡积分返现叠加题在向量和混合模式均误召回了领券规则，但语料里没有返现叠加依据。混合模式还漏掉了地址变更不会影响历史订单这一证据。固定样本结果只用于说明本轮语料与检索覆盖。

## 重建索引

本机 `tools/rag-index/rules.json` 已重建为新版，并通过按默认参数重新加载检查。使用自定义索引路径的已有环境，在新代码启动时为其执行一次 `app.agent.rag.rebuild-index=true`，随后恢复为 false。

仅评测时运行：

```powershell
mvn '-Dtest=RuleCorpusExpansionTest' '-Dagent.evaluation.storefront-corpus=true' test
```

如要在同一次命令中重建项目约定的本地索引，追加 `-Dagent.evaluation.storefront.rebuild-default=true`。两种命令都使用本地 BGE，不调用付费模型。

## 验证证据

本地 CI 80 项、BGE 和索引专项 5 项均通过。完整 Agent 回归使用新语料、本地模型替身和隔离 MySQL，15 个场景、17 次提问均通过。JAR 内已确认包含全部 13 篇规则。

逐题原文、排名和索引指纹见 `tools/evaluation-baselines/storefront-corpus-v1-16052881953654084004.json`。Agent 报告为 `tools/evaluation-baselines/agent-v3-local-17620118755520587022.json`，本机日志为 `tools/rag-expanded-ci.log`、`tools/rag-expanded-final.log` 和 `tools/rag-expanded-agent.log`。

## 真实 DeepSeek 复评

2026-11-27 以新版 13 篇规则、BGE Small 中文 v1.5、向量 Top 3 和 0.55 阈值运行 DeepSeek Flash。服务通过 SSE 访问真实上游。业务夹具和台账使用本轮独立 MySQL，评测入口固定 2 元本地费用估算上限。

| 指标 | 本轮结果 |
| --- | ---: |
| 自动断言通过的场景 | 15/15，17 次提问 |
| 场景指定工具命中 | 13/13 |
| 允许的工具范围 | 15/15 |
| 正文引用预期文档 | 5/5 |
| 首段文本延迟 P50 / P95 | 1,015 / 1,641 ms |
| 模型账本 | 30 笔均已结算，40,040 Token |
| 已结算估算金额 / 待核对预留 | 91,234 / 0 微元 |

TTFT 从服务端准备会话轮次开始，到首次发出非空 delta。17 次提问中有 10 次录到该事件，P50 和 P95 在这 10 次上按最近秩计算。越权查单返回 404，越权身份指令与写操作请求通过拒绝断言，无规则依据的问题通过缺少依据与无引用断言。本表记录固定题集的自动评分，逐题人工语义复核尚未纳入。费用为项目费率估算，供应商账单由 DeepSeek 结算。

复现命令使用测试数据源指向空的隔离库 `limited_trade_acceptance`，执行 `schema.sql` 和编号迁移后再启用真实评测。密钥从本机配置或环境注入。

```powershell
mvn '-Dtest=AgentEvaluationV3Test' '-Dagent.evaluation.v3=live' test
```

逐题报告为 `tools/evaluation-baselines/agent-v3-live-10751295995297659975.json`，原语料指纹为 `909235a2ae81dff4d47a5844c07c42e929544e9c533060a82a1880987f02bbbd`。本机 Maven 日志为 `tools/upgrade-phase4-live.log`，隔离实例和临时数据目录已回收。
