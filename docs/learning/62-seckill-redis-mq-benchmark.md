# 阶段 5.1 · 限量活动 Redis + Lua + RabbitMQ 异步秒杀与压测对账

日期：2026-11-27。对应执行方案[阶段 5.1](../plans/2026-11-27-ux-and-resume-upgrade-plan.md#阶段-5商城后端硬核补强redis+mq-异步秒杀与-elasticsearch-商品搜索)。

## 架构演进与核心痛点

原限量活动抢购直接走同步事务 `POST /api/orders`，在高并发争抢同一热门活动时，所有请求同时竞争 MySQL `activity` 与 `product` 同一行的排他锁（`SELECT ... FOR UPDATE`），导致大量数据库连接阻塞在行锁等待队列中，尾延迟飙升。

本轮在保留原同步接口与离线测试兼容性的前提下，新增完整的高并发异步秒杀链路：

1. **数据契约与幂等预约表（`sql/017_activity_seckill.sql`）**：
   - `activity` 表新增 `per_user_limit INT NULL`（默认管理端与秒杀活动设为 `1`，即一人一单；`NULL` 兼容历史多件测试夹具）。
   - 新增 `activity_purchase_reservation` 表，以 `(user_id, request_id)` 唯一键承接异步排队状态机（`QUEUED` → `CREATED` / `FAILED` / `CANCELED`），作为 MQ 异步消费与定时对账的持久化事实源。
2. **四段式 Redis Lua 原子状态机（`src/main/resources/redis/`）**：
   - `activity-seckill-init.lua`：惰性或主动预热活动时间窗、单人限购上限、剩余名额与已购用户哈希。
   - `activity-seckill.lua`：单次原子执行 `requestId` 幂等重放检查、活动时间窗校验、单用户累计购买件数校验（`users[uid] + qty <= perUserLimit`）、剩余名额扣减（`quota - qty`）及在途水位登记（`inflight[requestId]`）。
   - `activity-seckill-complete.lua`：消费者落库提交后将请求状态转为 `CREATED:{orderId}` 并清理 `inflight`。
   - `activity-seckill-release.lua`：建单失败、买家取消订单或支付超时关单时，凭独立防重守卫键幂等回补 Redis 名额并扣减用户已占件数，恢复重购资格。
3. **RabbitMQ 异步削峰、降级兜底与三恒等式对账**：
   - `POST /api/activities/{id}/seckill` 在 Lua 预扣通过后投递持久化消息至 `seckill.order.exchange` 并立即返回 `202 Accepted`（`status = QUEUED`）；若 Broker 瞬时故障不可达，服务先同步写入数据库 `QUEUED` 预约行，由后台定时任务补投，若数据库亦失败则立即回补 Redis 预扣，杜绝悬挂扣减。
   - `ActivitySeckillConsumer` 按固定锁顺序（用户 → 地址 → 优惠券 → 活动 → 商品）物化真实订单；业务校验不通过时自动标记 `FAILED` 并释放 Redis 名额，未知异常经重试后进入 `seckill.order.dlq` 死信队列告警。
   - `ActivitySeckillReconciler` 周期校验三项守恒关系：`total_quantity == remaining_quantity + 有效已占件数`、`redis_quota == max(0, remaining_quantity - inflight_sum)`、`redis_user_purchased == db_user_purchased + user_inflight`，自动修复超时滞留的 `inflight` 或缓存漂移。
4. **前端无感排队与恢复**：
   - `QuickPurchaseDrawer.vue`、`ActivityDetailView.vue` 与 `stores/checkout.ts` 支持 `202 QUEUED` 渐进式短轮询 `GET /api/activities/seckill/result/{requestId}`，并写入 `sessionStorage` 防止刷新丢失排队进度，出单后自动跳转 `/orders/:id?justCreated=1`。

## 实测对比结果

本机隔离环境：Intel Core i9-14900HX（32 逻辑核）、Windows 11、JDK 21.0.10、Node v24.13.0、独立 MySQL 8.0（InnoDB Buffer Pool 256 MiB，开启 Performance Schema）、WSL2 Redis、独立便携版 RabbitMQ 4.3.6。测试构建产物 SHA-256 为 `7568904eb312456a1de0f440df734b055c078cfd60715c6d2016462497538f10`，预先建立 2,000 个独立登录会话与收货地址。原始证据保存在 `tools/seckill-benchmark/19e86a35ac/summary.json`。

| 压测档位（争抢 50 件名额） | 链路模式 | HTTP 吞吐（req/s） | P50（ms） | P95（ms） | P99（ms） | HTTP 峰值期间 InnoDB 行锁等待次数 / 累计等待耗时 | 异步队列全量落库总耗时 |
|---|---|---:|---:|---:|---:|---:|---:|
| **500 并发（10× 竞争）** | 原 MySQL `FOR UPDATE` 同步 | 283.1 | 1075.73 | 1687.61 | 1739.16 | 499 次 / 36,162 ms | —（同步 1766.22 ms） |
| **500 并发（10× 竞争）** | **Redis + Lua + RabbitMQ 异步** | **1665.3（5.88×）** | **177.90** | **286.12（快 5.90×）** | **290.79** | **0 次 / 0 ms** | **1336.13 ms** |
| **2,000 并发（40× 竞争）** | 原 MySQL `FOR UPDATE` 同步 | 426.1 | 2556.60 | 4482.54 | 4604.50 | 1,999 次 / 96,920 ms | —（同步 4693.77 ms） |
| **2,000 并发（40× 竞争）** | **Redis + Lua + RabbitMQ 异步** | **2164.1（5.08×）** | **757.63** | **860.39（快 5.21×）** | **871.58** | **0 次 / 0 ms** | **1027.89 ms** |

### 一致性对账与防刷补偿核验

- **零超卖与零少卖**：500 并发与 2,000 并发两轮实验下，异步秒杀均精确返回 `50` 个 `202 Accepted` 与 `450` / `1,950` 个 `409 Conflict`（被 Lua 直接在内存层拦截，未穿透至数据库）；异步消费排空后，MySQL `order_item` 成交数为 `50`、`activity.remaining_quantity = 0`、去重买家数 `distinctBuyers = 50`、Redis `quota = 0`、`inflight = 0`。
- **同账号并发防刷（一人一单）**：单个买家使用 30 个不同 `requestId` 同时并发抢购 `per_user_limit = 1` 的活动，Lua 原子放行 `1` 笔 `202`，其余 `29` 笔全部返回 `409`（超出每人限购上限）。
- **取消订单自动回补与重购**：将该买家刚落库的秒杀订单调用 `POST /api/orders/{id}/cancel` 取消后，数据库 `remaining_quantity` 立即恢复为 `10`，Redis 名额与该用户限购计数同步释放；同一买家再次发起抢购返回 `202` 并顺利异步落库为新订单。

## 复跑说明

先打包最新后端 JAR（`mvn -Pci verify`），随后在 `frontend` 目录执行：

```powershell
npm.cmd run benchmark:seckill
```

脚本会在 `target/seckill-bench-*` 下自动拉起隔离的 WSL Redis、RabbitMQ、MySQL 与 Spring Boot 实例，完成 2,000 会话建连、两档并发对照、同账号防刷及取消重购核验，输出 `tools/seckill-benchmark/<runId>/summary.json` 并在退出时自动回收全部子进程与临时目录。
