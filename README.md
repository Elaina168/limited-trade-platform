# 限量商品营销交易平台

基于 Java 21、Spring Boot 和 Vue 3 的限量交易商城。买家可浏览商品、参与活动、领券、结算和查询订单。管理员可维护商品与活动并登记发货。交易助手可查询本人订单、解释公开交易规则并展示引用来源。

## 启动与验收

| 入口 | 用途 |
| --- | --- |
| [本机运行说明](frontend/RUNNING.md) | 准备 MySQL、Redis、RabbitMQ，启动 Java 和 Vue |
| [人工验收清单](docs/manual-acceptance.md) | 检查默认商城、买家与管理员流程、交易助手和自动恢复 |
| [数据库初始化](#数据库初始化首次准备) | 首次建库、执行迁移并导入默认商城数据 |
| [Nginx 静态入口](docs/learning/47-strengthening-vue-static.md) | 提供 Vue 和同源 Java API |
| [自动验收与 CI](docs/learning/46-strengthening-acceptance-ci.md) | 重现确定性构建、测试及浏览器验收 |
| [路线图](docs/roadmap.md) · [开发历史](docs/project-history.md) | 查阅剩余部署验收、各阶段实现及历史证据 |

[GitHub 仓库](https://github.com/Elaina168/limited-trade-platform) · [开发分支](https://github.com/Elaina168/limited-trade-platform/tree/codex/bootstrap-boot) · [GitHub Actions](https://github.com/Elaina168/limited-trade-platform/actions)

## 核心流程

- 下单在 MySQL 事务内扣商品库存、占用活动名额和优惠券，按请求标识去重。超时关单使用 Outbox、RabbitMQ 和扫描补偿。 [交易故障演练](docs/learning/28-trade-drill.md)
- 商品读取使用 Caffeine 与 Redis。本人订单深分页在指定大偏移路径先取当页主键，再读取完整订单。 [缓存一致性](docs/learning/52-strengthening-cache-consistency.md) · [分页测量](docs/learning/50-strengthening-mysql-pagination.md)
- 交易助手基于 Spring AI，使用服务端身份执行只读工具，支持 SSE、本人会话历史、费用预算和规则引用。 [预算记录](docs/learning/55-strengthening-agent-budget.md) · [商城规则知识库](docs/learning/61-storefront-rag-corpus.md)
- 响应异常时，页面自动核对下单、订单状态、助手回答等结果。 [人工验收清单](docs/manual-acceptance.md)

默认开发商城扩展脚本新增 720 件商品、120 个活动和 240 种优惠券。活动时间随首次导入时间生成。[规模验收](docs/learning/60-catalog-scale-and-regression.md)

## 当前交付状态

截至 2026-11-26，商城买家与管理端主线、订单回执恢复、商品详情、活动与券的北京时间判断均已通过本机隔离浏览器验收。最近一轮功能提交为 1ab70c5，其确定性回归包含 81 项 Java 测试、54 项 Vue 测试，以及 Java 打包和 Vue 类型检查与构建。浏览器验收使用隔离 MySQL 与本地模型替身。 [人工验收清单](docs/manual-acceptance.md) · [功能提交 CI](https://github.com/Elaina168/limited-trade-platform/actions/runs/45628825446)

| 待收尾 | 验收目标 |
| --- | --- |
| 补强 3 · Docker Compose | 五服务从干净环境启动，跑通交易并验证重建后数据保留。 [Compose 说明](docs/learning/48-strengthening-compose.md#验收进度) |
| 补强 15 · 发布与回滚 | 在确定的演示环境记录提交与迁移版本，演练发布、回滚、告警触发及恢复，留存持续运行证据。 [补强计划](docs/superpowers/plans/2026-11-23-project-strengthening.md#补强15版本关联发布回滚告警与持续运行) |

新版 13 篇规则已完成 DeepSeek Flash 真实上游复评，15 个固定场景自动断言通过，5 个预期文档引用全部命中。 [RAG 与真实模型复评](docs/learning/61-storefront-rag-corpus.md)

## 数据库初始化（首次准备）

将 `config/db.properties.example` 复制为同目录下的 `db.properties`，填写本机数据库用户名与密码。开发库为 `limited_trade_platform`，测试库为 `limited_trade_platform_test`；真实配置被 Git 忽略，并且不放入 JAR。

从项目根目录启动本机 MySQL 客户端，使用有建库权限的本机账号登录，在客户端执行：

```sql
CREATE DATABASE limited_trade_platform CHARACTER SET utf8mb4;
CREATE DATABASE limited_trade_platform_test CHARACTER SET utf8mb4;

USE limited_trade_platform;
SOURCE sql/schema.sql;
SOURCE sql/002_app_user.sql;
SOURCE sql/003_trade_order.sql;
SOURCE sql/004_order_payment_cancel.sql;
SOURCE sql/005_order_timeout.sql;
SOURCE sql/006_activity.sql;
SOURCE sql/007_order_timeout_outbox.sql;
SOURCE sql/008_product_category.sql;
SOURCE sql/009_cart_item.sql;
SOURCE sql/010_user_address.sql;
SOURCE sql/011_order_checkout.sql;
SOURCE sql/012_order_shipment.sql;
SOURCE sql/013_activity_coupon.sql;
SOURCE sql/014_agent_conversation.sql;
SOURCE sql/015_agent_conversation_visible_history.sql;
SOURCE sql/016_agent_usage_budget.sql;
SOURCE sql/seed.sql;
SOURCE sql/demo_catalog_scale.sql;

USE limited_trade_platform_test;
SOURCE sql/schema.sql;
SOURCE sql/002_app_user.sql;
SOURCE sql/003_trade_order.sql;
SOURCE sql/004_order_payment_cancel.sql;
SOURCE sql/005_order_timeout.sql;
SOURCE sql/006_activity.sql;
SOURCE sql/007_order_timeout_outbox.sql;
SOURCE sql/008_product_category.sql;
SOURCE sql/009_cart_item.sql;
SOURCE sql/010_user_address.sql;
SOURCE sql/011_order_checkout.sql;
SOURCE sql/012_order_shipment.sql;
SOURCE sql/013_activity_coupon.sql;
SOURCE sql/014_agent_conversation.sql;
SOURCE sql/015_agent_conversation_visible_history.sql;
SOURCE sql/016_agent_usage_budget.sql;
```

以上命令用于首次初始化。已有数据库只按顺序执行尚未应用的编号脚本。已应用的迁移不重复运行。`seed.sql` 只用于开发库，测试用例自行插入测试库数据，并通过事务回滚。

本机已生成 `learning_admin` 和 `learning_user` 两个学习账号，随机密码只保存在被 Git 忽略的 `config/local-accounts.txt`。该文件不进入 JAR 或 Git。新环境的账号初始化与登录操作见 [登录与权限学习记录](docs/learning/04-security.md)，不能假设其他机器存在这些本机账号。

商城开发库和新 Compose 数据卷默认包含扩展演示目录。新增 12 个分类、720 件在售商品、120 个限量活动和 240 种活动满减券。已有商城库在应用结构迁移后可选中目标库执行 `SOURCE sql/demo_catalog_scale.sql;`。脚本只追加缺失的扩展记录，重复导入保留库存、名额和活动时间。 [规模测试说明](docs/learning/60-catalog-scale-and-regression.md)
