# 当前交易项目补强范围

日期：2026-11-23。依据本轮用户选定范围及“两个项目都完整实现”的确认整理。本文确定当前项目目标，不表示已经实施，也不授权自动安装、发布、提交或推送。

## 当前项目定位

沿用Java、Spring Boot、MyBatis、MySQL、Redis、RabbitMQ、Spring AI、DeepSeek和Vue。保留交易单体与Session/CSRF认证，不整体替换ORM、认证或微服务架构。此前Vue七阶段本机验收已完成，补强阶段另行编号。

## 已确认纳入

- 可移植自动验收、CI自动回归、Vue正式静态托管、Docker Compose真实部署。
- 并发正确性与吞吐/延迟基准、系统性性能实验、MySQL调优案例。
- 缓存：热点回源合并、TTL随机抖动、空值缓存、逻辑过期、后台刷新；Caffeine、布隆过滤器、Redis故障回源保护与旧值回填治理也在范围内，按具体数据和对比实验分阶段处理，不给所有接口叠加相同机制。
- 交易入口Redis共享令牌桶，与现有固定窗口比较。
- Agent：用户调用额度、Token统计与上下文预算、模型费用预算；会话列表、历史读取；细粒度工具权限、系统化攻击样本；持续评测、多配置比较。
- 当前RAG：基线评测、关键词与向量混合检索、RRF；Rerank和按需Query Rewrite做实验后决定默认启用；pgvector/Milvus在需求和资源论证后选择，不预设两种都引入。
- 运维：应用版本/配置/数据库迁移关联、发布回滚演练、告警通知和真实环境持续运行记录。没有正式环境时如实称演示/预发布环境。

## 第二个独立Agent项目

用户明确重叠能力两个项目都完整实现，不能把当前项目的额度、预算、历史、安全和评测删掉。第二个项目另用Python/FastAPI、LangChain/LangGraph等技术，另做MCP、Langfuse、RAGAS、多模型路由/熔断、多Agent、HITL、写操作审批、多模态、长期记忆/用户画像、摘要、Text2SQL、WebSocket、OSS、SSO。业务选题和具体架构另定，当前不创建工程。

## 共同约束

- 优化前固定基线；正确性、性能、模型质量和费用分别验收，不编造提升数字。
- 订单状态、库存和金额以业务服务/数据库为准；展示缓存和历史摘要不能替代交易判断。
- Agent仍只读，工具授权由服务端实施。没有审批动作时不建设写操作审批。
- Token预估与上游实际usage分开；费用未知不计零，不承诺无法核对的绝对精确成本。
- 独立数据、缓存和MQ拓扑；不覆盖开发库、不回退自增序列、不清空共享Redis。
- CI默认不调用付费模型；真实模型验收需要限额、显式触发和安全保存凭据。
- 一次实施一个阶段，完成必要验证后讲解；不自动提交推送。
- Docker 的真实部署与集成验收并入收尾。安装和镜像下载前重新盘点版本、已有安装、磁盘与资源；外部告警渠道、正式发布环境及真实费用上限在对应阶段确定，不在本阶段猜定。

## 当前事实入口

- `docs/learning/45-vue-integration-delivery.md`：最近一次本机联调及证据边界。
- `frontend/RUNNING.md`：现有开发/预览启动说明，尚非正式Vue托管。
- `src/main/java/com/elaina/limitedtrade/product/ProductService.java`：列表缓存与搜索查询是不同路径。
- `src/main/java/com/elaina/limitedtrade/product/ProductCache.java`：30秒列表缓存、提交后删除；空列表已经缓存，但不是按不存在商品ID做负缓存。
- `src/main/java/com/elaina/limitedtrade/ratelimit/RedisOrderRateLimiter.java`：现有固定窗口入口。
- `src/main/java/com/elaina/limitedtrade/agent/conversation/AgentHistory.java`：最近6轮及24,000字符裁剪，不是精确Token预算。
- `src/main/java/com/elaina/limitedtrade/agent/conversation/AgentConversationService.java`：24小时会话过期、每人最多10个未过期会话，历史读取前需重定数据生命周期。

阶段顺序和验收见对应补强计划。未明确的新接口、字段、预算金额和性能目标在阶段设计中确定，不把候选命名当成既有协议。
