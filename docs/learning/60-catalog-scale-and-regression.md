# 商城数据扩展与规模化回归

日期为 2026-11-24。

## 默认商城目录

本机默认开发库已导入扩展数据。此前四类、11 件商品、3 个活动和 3 种券继续保留。扩展部分包含 12 类、720 件在售商品、120 个活动和 240 种券。导入时活动分为 96 个进行中、12 个未开始、12 个已结束。券分单件和双件满减，以活动商品价格计算门槛，领取和使用时间遵循所属活动。

`sql/demo_catalog_scale.sql` 使用名称区分扩展演示数据。同一数据库的种子执行通过应用级命名锁串行，事务内只追加缺失项。重复导入不补回库存和名额，也不修改活动窗口。README 的新开发库初始化和 Compose 的新卷初始化均包含该种子。已使用的其他数据库可在选中对应库后执行一次，已有记录按名称保留。

## 可重复规模验收

`npm run acceptance:catalog-scale` 在临时 MySQL 上执行两次和默认库相同的种子。通过真实 HTTP 遍历商品分页，逐一核对活动券及归属，并验证库存、名额和券状态。读取并发档位为 1、8、24、48。

| CATALOG_SCALE_PROFILE | 缓存 | 下单限流 |
| --- | --- | --- |
| off | 关闭 | 关闭 |
| redis-fixed | Redis | 固定窗口 |
| local-bloom-token | Redis、Caffeine 和布隆提示 | 令牌桶 |

后两组使用独立回环 Redis，端口通过 `BENCHMARK_REDIS_PORT` 指定。运行结果保存在 `tools/catalog-scale/`。缓存和限流一起搭配测试，延迟反映组合，不能单独归因。生产阈值沿用经过现有验收的配置。

## 本轮结果

- 默认开发库导入成功。Edge 从连接该库的商城打开商品页时可直接看到扩展商品，活动 003 的详情显示单件和双件满减券。导入与浏览器日志为 `tools/scale-dev-import.log` 和 `tools/scale-dev-browser.log`。
- 三组规模验收均通过。各组走完 720 件商品、120 个活动和 240 种券，1/8/24/48 并发合计 81 次完整目录请求，另有 48 次并发详情请求。每组 24 人并发领取 16 张券，随后 16 人争 8 份库存及活动名额，8 单全部取消后库存、名额和领券状态对账一致。同用户并发提交 8 次同一请求，关闭限流准入 8 次，固定窗口和令牌桶各准入 5 次，三组均只创建一单、扣一件。报告为 `tools/catalog-scale/f4e0f6dee4df.json`、`tools/catalog-scale/ff573d155dd5.json`、`tools/catalog-scale/5988df4e4642.json`。
- Java 全量发现 714 项，698 项通过、0 失败、16 跳过，包含 17 项真实 RabbitMQ 验证。独立补跑的 Agent v3 本地模型替身 15 个场景、17 次提问全部通过。真实付费模型、绑定固定节点的原生故障演练和单独的 Swagger 浏览器开关未执行。结算事务专项固定直读 Redis 的测试关闭本地缓存，日志专项读取当前 Logback 文件输出，修复了全量运行时的夹具假设。日志为 `tools/scale-java-final.log`、`tools/scale-agent.log`。
- 前端 43 项测试及构建通过。Nginx 和 Edge 浏览器回归覆盖注册登录、购物车结算、发货收货和 Agent 流式交互。记录为 `tools/scale-vue-test.log`、`tools/scale-vue-build.log`、`tools/scale-browser.log`。
- 150,000 行订单深分页通过真实 API、归属、状态和 8 组偏移对照，记录 192 条 MySQL 采样。原生链路另以 1,003 件在售商品完成读负载及真实消息对账，共 111,054 条请求原始样本。报告在 `tools/mysql-pagination/cb21d973a03c/` 和 `tools/trade-benchmark/22fd57ab504f/`。本批样本用于验证规模和正确性，未当作独占机器性能基准。
