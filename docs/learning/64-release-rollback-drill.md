# 阶段 7.2 · 发布版本核验、故障回滚与消息积压告警恢复演练

日期：2026-11-28。对应执行方案[阶段 7.2](../plans/2026-11-27-ux-and-resume-upgrade-plan.md#阶段-7docker-compose-全栈交付与发布回滚演练收尾对应原补强-3--15)与[补强 15](../superpowers/plans/2026-11-23-project-strengthening.md#补强15版本关联发布回滚告警与持续运行)。

## 设计与安全边界

1. **构建产物与运行版本暴露（`GET /api/admin/operations/release`）**：
   - `pom.xml` 配置 `spring-boot-maven-plugin` 的 `build-info` 目标生成 `META-INF/build-info.properties`；`Dockerfile` 与 `compose.yaml` 支持通过构建参数和环境变量注入 `APP_GIT_SHA`、`APP_RELEASE_LABEL` 与 `APP_REQUIRED_MIGRATION`；本地直接运行 JAR 时若未显式指定 `APP_GIT_SHA`，`ReleaseInfoService` 自动从 `.git/HEAD` 解析提交编号。
   - `SchemaMigrationMapper` 通过只读查询当前库的 `information_schema.TABLES / COLUMNS / STATISTICS`，逐项核对 `schema.sql` 与 `002_app_user` 至 `018_product_search_sync` 共 18 个结构里程碑。
   - 接口仅允许 `ADMIN` 角色访问（匿名返回 `401`、普通用户返回 `403`），返回结果只暴露脱敏功能开关（`searchMode`、`seckillEnabled`、`productCacheEnabled`、`orderMessageEnabled` 等），**严禁包含数据库密码、MQ 密码或大模型 API Key**。
2. **数据库迁移与应用回滚解耦**：
   - 当新发布的实例声明依赖尚未执行的数据库迁移（或目标库缺少必需迁移）时，`GET /api/admin/operations/release` 立即返回 **`503 Service Unavailable`**（`compatible = false` 并精确列出 `missingMigrations`），同时输出结构化 `WARN` 日志 `event=release_schema_incompatible`。
   - 演练遵循“向后兼容扩展优先、应用二进制可秒级切回上一稳定标签、不盲目回滚已含业务数据的表结构”原则。

## 隔离环境全自动演练结果

演练脚本为 `scripts/release-rollback-drill.py`，在本机自动拉起隔离的 MySQL 8.0、WSL2 Redis 与便携版 RabbitMQ 4.3.6，全程使用真实订单、真实 Outbox 投递与真实队列死信流转。原始证据保存在 `tools/release-drill/f7befee1ca/summary.json`（Git SHA `2e5d3101cc2f`，JAR SHA-256 `1206e1b50c89969f275d5ffedb239bc63606f9133b7185c188ab651de2b7a459`）。

| 演练阶段 | 执行动作 | 实测核验结果 |
|---|---|---|
| **1. 首次发布 `r1` 基线** | 启动 `2026.11.28-r1`，检查发布快照并创建订单 #1 | `/api/admin/operations/release` 返回 `200`，`gitSha = 2e5d3101cc2f`，18 项迁移全部就绪（最新 `018_product_search_sync`），响应不含任何凭据；订单 #1 落库且 Outbox 状态转为 `SENT` |
| **2. 平滑升级 `r1 → r2`** | 切换应用实例至 `2026.11.28-r2`，校验旧单支付与新单创建 | 发布快照更新为 `2026.11.28-r2`（`compatible = true`）；历史订单 #1 正常完成模拟支付（`PAID`），新订单 #2 正常创建（`PENDING_PAYMENT`） |
| **3. 故障版本拦截与回滚 `r3-bad → r2`** | 部署要求未执行迁移 `019_unapplied_breaking_migration` 的故障版本 `2026.11.28-r3-bad`，捕获不兼容状态后立即切回 `2026.11.28-r2` | `r3-bad` 发布检查立即返回 **`503`**（`compatible = false`，`missingMigrations = ["019_unapplied_breaking_migration"]`）并记录结构化告警；切回 `r2` 后恢复 **`200`**，订单 #1（`PAID`）、订单 #2（`PENDING_PAYMENT`）与商品库存分毫不差 |
| **4. 消息积压告警、去重与恢复** | 创建 2 笔短 TTL 待支付订单投递至等待队列后暂停消费者，待消息死信转入 `.ready` 形成积压；采样核验后恢复消费者 | 暂停期间 `/api/admin/operations/order-messages` 准确触发 `["ORDER_CLOSE_DELAYED", "READY_BACKLOG"]`（`readyReady = 2`、`overdueOrders = 2`），连续多轮采样告警数组保持稳定不膨胀，日志落盘 `WARN` `order_message_health`；恢复消费者后队列积压自动排空（`readyReady = 0`），订单 #3、#4 转为 `CLOSED`，商品库存精确回补 2 件，告警列表归零（`[]`）并输出 `INFO` `alertCount = 0` |

## 复跑命令

完成 `mvn -Pci verify` 打包后，在仓库根目录执行：

```powershell
$env:PYTHONIOENCODING = 'utf-8'
python scripts/release-rollback-drill.py
```

脚本执行完毕后自动回收临时 MySQL、RabbitMQ、Redis 与 Java 进程，并将当次报告写入 `tools/release-drill/<runId>/summary.json`。
