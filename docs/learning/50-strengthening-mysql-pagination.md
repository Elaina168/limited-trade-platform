# 补强 5 · MySQL 本人订单深分页

日期：2026-11-23。[实施计划](../superpowers/plans/2026-11-23-strengthening-05-mysql-pagination.md)

## 问题与选择

补强 4 的 MySQL 语句摘要中，本人订单取页查询累计耗时靠前。当时每个买家的分页样本只有 80 笔。本轮将数据扩大到 15 万笔历史订单，其中主买家 12 万笔、第二位买家 3 万笔，主键交错。数据仅写入一次性 MySQL。

原查询按买家过滤后，以订单 ID 倒序取页。在偏移 100,000 的夹具中，MySQL 选择从 `uk_trade_order_user_request` 读取主买家 120,000 笔订单，再排序至 100,020 行。候选先使用覆盖索引 `idx_trade_order_user_id` 倒序找到当页 ID，之后按主键读取完整订单。执行计划显示内层仍经过 100,020 条索引记录，外层只读取 20 笔完整订单并对这 20 笔排序。偏移仍随页码增大，索引扫描成本仍存在。

浅页的原计划用主键倒序，候选多了一层物化、关联和小结果排序。原 SQL 的偏移 1,000 在两轮均更快。偏移 20,000 及之后的实测点支持采用候选。因此只在 `status == null && offset >= 20000` 时使用延迟关联，其余分页走原查询。阈值以主买家的实测查询为依据。第二位买家用于核对不同数据规模下的 API 行为。

## 结果

MySQL 8.0.45、REPEATABLE READ、128 MiB InnoDB 缓冲池。每组在同一连接交错执行旧式与候选查询，先各暖机 3 次，再各采 12 次。下表为优化后运行中在同一份数据上的语句耗时，P50/P95 为最近秩，单位毫秒。两次独立运行合计保存 384 条计时记录。

| 偏移行数 | 原 SQL P50 / P95 | 候选 P50 / P95 | 真实 API 使用 |
|---:|---:|---:|---|
| 0 | 0.157 / 0.191 | 0.952 / 1.122 | 原 SQL |
| 1,000 | 0.721 / 0.991 | 1.033 / 1.305 | 原 SQL |
| 20,000 | 131.604 / 276.633 | 3.082 / 4.202 | 候选 |
| 100,000 | 148.243 / 161.408 | 12.925 / 14.063 | 候选 |
| 119,980 | 149.956 / 156.118 | 14.887 / 16.788 | 候选 |

表中所述真实 API 选择适用于无状态筛选的请求。偏移 20,000 的原 SQL P95 跨两次运行波动较大，两轮分别为 140.690 和 276.633 ms，以 P50 与执行计划对照。测得的是取页语句耗时，API 还要计算精确总数并加载订单明细。Performance Schema 的 `ROWS_EXAMINED` 和树形计划的索引遍历口径各自留在原始结果中。

- 8 组偏移的完整 SQL 投影一致，覆盖最后一页与空页。变更后的真实 HTTP 13 组通过，覆盖阈值前后、超大页码、状态筛选、买家隔离、总数、倒序与商品快照；与变更前共有的 9 组结果逐项一致。
- 候选 SQL 在数据库的实际 API 语句摘要出现 6 次。剩余 7 次走原取页 SQL，其中 2 次带状态筛选，符合预期分支。
- 实现仅调整本人订单取页。写入语句和表结构相同，沿用既有索引。两轮 `SHOW INDEX` 的索引定义一致，订单表数据和索引大小分别为 16,302,080 与 19,480,576 字节。新增开销集中在读取时的临时派生结果与关联。

## 复跑与学习

先按[补强 1](46-strengthening-acceptance-ci.md)准备 JDK、Node、MySQL 可执行文件和 Java JAR。在 `frontend` 指定 `ACCEPTANCE_MYSQLD`、`ACCEPTANCE_MYSQL`，用空闲的 `ACCEPTANCE_MYSQL_PORT` 和 `ACCEPTANCE_JAVA_PORT`，运行 `npm run benchmark:pagination`。脚本独立启动 MySQL 和 Java，自动处理夹具与清理。日志中的 `tools/mysql-pagination/<运行编号>/results.json` 保存语句、执行计划、索引、真实 HTTP 摘要和样本。历史证据目录是 `c1c2745ea67a`（变更前 JAR）和 `55bf99ebdc1d`（变更后 JAR）。

调用链是 `OrderController.listOrders()` → `OrderService.listUserOrders()` → `OrderMapper.countUserOrders()`、`OrderMapper.findUserOrders()` → 批量补充明细 → `OrderPageResponse`。`offset` 仍由页码和页大小算出，MyBatis 用 `status` 和 `offset` 选择取页方式。筛选与总数仍在同一只读事务快照内。
