# 阶段 5.2 · Elasticsearch 万级商品检索、高亮聚合与自动熔断降级

日期：2026-11-27。对应执行方案[阶段 5.2](../plans/2026-11-27-ux-and-resume-upgrade-plan.md#阶段-5商城后端硬核补强redis+mq-异步秒杀与-elasticsearch-商品搜索)。

## 痛点与网关抽象设计

随着商城商品扩充至万级规模，原有 `ProductMapper.xml` 基于 `WHERE name LIKE '%keyword%'` 的前导通配符检索无法命中 B+Tree 索引，叠加多条件筛选（价格区间、仅看有货、价格排序）以及多分类数量统计时，每次搜索需在 MySQL 执行全表过滤与排序扫描。同时，项目要求离线 CI（`-Pci`）和无容器开发环境下零外部强依赖即可运行。

为此，本轮采用**双引擎网关 + 冷却期自动熔断降级**架构：

1. **统一检索契约（`ProductSearchGateway`）**：
   - `MysqlProductSearchGateway`（默认 `app.search.mode=mysql`）：保持离线单测与最小环境开箱即用，补齐多条件过滤、分类 `GROUP BY` 聚合、纯文本安全 `<mark>` 高亮与 `GROUP BY p.name` 前缀优先联想。
   - `ElasticsearchProductSearchGateway`（`app.search.mode=es`）：基于 Spring `RestClient` + Jackson 构建原生 ES 8 JSON DSL，零过重客户端依赖。
2. **中文分词自适应、安全高亮与分类聚合**：
   - 建索引时自动探测目标 ES 节点是否安装 `analysis-ik` 插件；有则启用 `ik_max_word` / `ik_smart`，无则自动回退内置 `zh_ngram_analyzer`（按字母数字切分 1~2 元语法）与 `prefix_analyzer`（1~20 `edge_ngram`），无需额外手工装插件即可支持中文词根检索与前缀实时联想。
   - 分类筛选使用 `post_filter`，配合顶层 `by_category` `terms` 聚合，保证买家点击某一分类胶囊时仍能看到其他分类下的匹配商品计数。
   - 高亮使用私有区占位符 `\uE000` / `\uE001` 接收 ES 片段，经 `HtmlUtils.htmlEscape()` 转义原始商品名称后再替换为 `<mark>` 与 `</mark>`；前端 `utils/search-highlight.ts` 将其解析为纯文本片段数组渲染，全程不使用 `v-html`，彻底杜绝存储型/反射型 XSS。
3. **事务提交后增量同步与故障自动降级（`ProductSearchCoordinator`）**：
   - 启动就绪（`ApplicationReadyEvent`）当索引新建或为空时按 1,000 条分批 `_bulk` 全量灌入在售商品；管理端新增 `POST /api/admin/products/reindex` 支持一键全量重建。
   - ES 模式下，`@TransactionalEventListener(phase = BEFORE_COMMIT)` 在商品变更的同一个 MySQL 事务记入 `product_search_pending`。同步任务只读取已提交的记录，每批最多 100 件，按 MySQL 中当前状态写入或删除 ES 文档。ES 确认后，仅删除仍匹配本批变更标识的记录，处理中产生的新变更保留到下一批。
   - 当 ES 超时或返回异常时，`ProductSearchCoordinator` 自动开启冷却熔断窗口（默认 `failure-cooldown-seconds: 15`），立即回退 `MysqlProductSearchGateway` 并标记 `engine = "mysql", degraded = true`，冷却期内后续请求零等待直走 MySQL，前端温和提示而不报错白屏。
   - 冷却结束后从持久表续传；启动、定时任务和读请求都可推进恢复，队列清空后搜索及联想才切回 ES。启用 ES 前对业务库执行 `sql/018_product_search_sync.sql`，同一业务库下负责商品或库存写入的实例使用相同的 ES 模式和目标索引。默认 MySQL 模式不读写此表。

## 实施过程中发现并修复的真实工程问题

1. **索引创建与首个主分片就绪竞态（`NoShardAvailableActionException`）**：并发探测请求在 `HEAD /{index}` 返回 `200` 瞬间，主分片 `[0]` 可能尚处于 `INITIALIZING` 阶段。通过将 `ensureIndex()` 改为 `synchronized` 并在首次校验通过前统一调用 `/_cluster/health/{index}?wait_for_status=yellow&timeout=10s`，彻底消除启动期 503 竞态。
2. **`application/x-ndjson` 默认字符集陷阱**：Spring `StringHttpMessageConverter` 在未显式声明 charset 的 `application/x-ndjson` 请求体上默认使用 `ISO-8859-1`，导致批量写入中文商品名变为 `?`。将 `_bulk` 与 `_search` 收发统一改为显式 `StandardCharsets.UTF_8` `byte[]`，既规避编码失真又减少字符串拷贝。
3. **MySQL 8.0 `ONLY_FULL_GROUP_BY` 联想 SQL 兼容**：`SELECT DISTINCT p.name ... ORDER BY ..., p.id ASC` 在 MySQL 8.0 严格模式下报 `Error 3065`，改为 `GROUP BY p.name` 配合 `MIN(p.id) ASC` 排序后与标准 SQL 完全兼容。

## 12,000 商品实测对比结果

本机隔离环境：Intel Core i9-14900HX、独立 MySQL 8.0 与独立便携版 Elasticsearch 8.17.2（单节点，JVM 512 MiB），灌入 **12,000 件商品（12 个分类，其中在售 11,520 件、下架 480 件）**，分别对 `mysql` 实例与 `es` 实例执行预热 + 采样闭环压测。原始证据保存在 `tools/es-benchmark/42f3cfe2f2/summary.json`（JAR SHA-256 与秒杀压测同为 `7568904eb312456a1de0f440df734b055c078cfd60715c6d2016462497538f10`）。

| 检索场景 | 并发度 | MySQL 吞吐（req/s） | MySQL P50 / P95（ms） | ES 吞吐（req/s） | ES P50 / P95（ms） | 吞吐提升倍数 |
|---|---:|---:|---:|---:|---:|---:|
| **关键词 + 高亮 + 12 分类聚合**（`无线机械键盘`） | 1 | 53.0 | 17.98 / 23.71 | **76.2** | **12.46 / 17.50** | 1.44× |
| **关键词 + 高亮 + 12 分类聚合**（`无线机械键盘`） | 8 | 386.1 | 20.63 / 23.26 | **672.4** | **11.51 / 15.36** | 1.74× |
| **关键词 + 高亮 + 12 分类聚合**（`无线机械键盘`） | 24 | 662.3 | 36.21 / 43.92 | **1423.8** | **16.38 / 23.66** | **2.15×** |
| **组合筛选 + 价格升序**（`蓝牙耳机` + 100~800 元 + 仅看有货） | 1 | 49.3 | 19.86 / 23.21 | **171.3** | **5.55 / 7.72** | 3.47× |
| **组合筛选 + 价格升序**（`蓝牙耳机` + 100~800 元 + 仅看有货） | 8 | 304.2 | 26.22 / 29.19 | **1124.7** | **6.96 / 9.69** | 3.70× |
| **组合筛选 + 价格升序**（`蓝牙耳机` + 100~800 元 + 仅看有货） | 24 | 524.0 | 45.35 / 56.81 | **2585.9** | **8.97 / 13.17** | **4.93×** |
| **搜索框前缀实时联想**（`prefix=客制化`） | 1 | 109.9 | 8.66 / 11.75 | **265.8** | **3.54 / 5.16** | 2.42× |
| **搜索框前缀实时联想**（`prefix=客制化`） | 8 | 701.8 | 11.33 / 14.12 | **1985.5** | **3.86 / 5.38** | 2.83× |
| **搜索框前缀实时联想**（`prefix=客制化`） | 24 | 1271.9 | 18.89 / 22.67 | **4372.9** | **5.25 / 7.56** | **3.44×** |

### 增量同步与故障降级实测证据

- **事务提交后增量同步**：管理员新建专属商品（初始 `OFF_SALE`，ES 搜索 `total = 0`）→ 上架 `ON_SALE`（ES 立即命中 `total = 1`，价格 `299.00`）→ 修改售价为 `259.00`（ES 立即更新为 `259.00`）→ 下架 `OFF_SALE`（ES 立即从索引删除，`total = 0`）。
- **ES 宕机自动熔断回退**：强制终止 Elasticsearch 进程后，首次搜索请求在 `48.45 ms` 内完成异常捕获、开启熔断并从 MySQL 返回完整的 `1,440` 条匹配结果与高亮片段（`engine = "mysql", degraded = true`）；冷却期内紧随其后的请求无需等待连接重试，仅耗时 `20.99 ms` 直接由 MySQL 响应。

## 复跑说明

确保 `tools/elasticsearch-8.17.2` 已解压就位且后端 JAR 已打包，在 `frontend` 目录执行：

```powershell
npm.cmd run benchmark:es
```

脚本会自动启动隔离的 Elasticsearch 8.17.2、MySQL 8.0 及双模式 Java 实例，完成万级数据灌入、9 组并发对照、增量同步与杀进程熔断验证，并将结果写入 `tools/es-benchmark/<runId>/summary.json`。
