# 补强 6 · 商品缓存基础

日期：2026-11-23。[实施计划](../superpowers/plans/2026-11-23-strengthening-06-product-cache.md)

## 改动落在哪些入口

| 入口 | 行为 |
|---|---|
| `GET /api/products` | 在售列表缓存。继续使用既有 Redis 键和 JSON 数组，空数组为有效缓存 |
| `GET /api/products/search` | 分页、分类及关键词查询直接读 MySQL |
| `GET /api/products/{id}` | 新增的匿名在售商品入口。正常商品直接查 MySQL，确实不存在的 ID 短暂记为缺失 |
| Vue 选购页 | 调用按 ID 的入口，获取一个商品及其价格和库存。不存在或下架时维持原提示 |
| 购物车、下单及支付 | 继续使用既有 MySQL 查询和交易事务。商品展示缓存不参与价格和库存的成交判断 |

## 商品列表如何刷新

1. `ProductCache.readList()` 用一次 Lua 调用读同一时点的值与剩余 TTL，沿用 `List<Product>` 的序列化。旧五字段 JSON 及带分类元数据的已有缓存都可读取。
2. 正常加载后硬 TTL 随机在 25–30 秒。剩余 TTL 超过 10 秒时直接返回。进入最后 10 秒后，请求可读当前值，同时触发一次后台刷新。
3. 冷未命中共享同一个 `inFlight` 任务。发起请求和跟随请求最多等待 2 秒，超时返回 503。一个请求超时后，其他请求仍可等待同一次加载，避免额外开启一批数据库查询。
4. 加载失败记录 `product_cache_load` 和失败原因，1 秒后允许重试。软过期失败沿用剩余硬 TTL；冷读没有可用值时返回 503。Redis 读取故障继续按现有异常边界处理。
5. 写事务提交后，`evictAfterCommit()` 删除列表并递增本机 `revision`。刷新只有在版本一致时才可写回。下架提交时正在加载的旧列表会被丢弃，失效后的冷读可以重新加载。事务回滚保留已有缓存。

每 JVM 只有一个列表工作线程。可按日志中的 `trigger`（`cold`、`refresh`）和 `outcome`（`stored`、`failed`、`invalidated`）计数，统计真实加载而不是请求数。已在失效前读到快照的并发请求可能返回该快照。所有实例共用 Redis 硬 TTL 和删除操作。本机的合并与版本检查只约束本机回源，跨 JVM 旧值回填和 Redis 故障治理留到补强 7。

## 不存在的商品如何缓存

商品 ID 映射到 256 个固定槽，槽内保存确切 ID，硬 TTL 为 5 秒。只有槽内 ID 完全相同时才返回缓存缺失。同槽的其他 ID 会重新查询数据库。下架商品由当前数据库状态判定，返回与不存在相同的 404。仅把数据库返回空行的情况写入负槽。

商品创建时默认下架，公开读取仍返回 404。上架事务提交后清理对应缺失槽，本机在失效之前启动的缺失查询无法重新写入旧标记。有限槽数换来碰撞时的额外查询，按 ID 读取现有商品每次仍查库。选购页减少了整个列表的读取与传输，暂未比较这两条路径的吞吐。

## 验证

- 受控慢回源下，同一组 12 并发请求的 mapper 调用从 12 次降至 1 次。验证了冷读等待 503、软过期先返回旧值再刷新、抖动窗口、刷新失败且 TTL 保留、失败后恢复、下架提交后的旧任务丢弃。
- 使用真实隔离 MySQL 和 Redis，`ProductCacheIntegrationTest`、`ProductSearchApiTest`、`OpenApiIntegrationTest` 合计 63 项通过。含旧缓存、空列表、库存变更、提交/回滚、幂等、真实价格及负缓存冲突和上架失效。
- 前端 42 项测试、类型检查和 Vite 构建通过。JAR 打包通过。真实 Chrome 验证了选购页打到单商品接口，缺失商品提示、购物车结算、支付发货收货和权限检查均通过。Java、MySQL 和 Redis 验收资源已回收。
- 本机日志为 `tools/strengthening-06-red.log`、`tools/strengthening-06-final-tests.log`、`tools/strengthening-06-frontend.log` 和 `tools/strengthening-06-browser.log`。

复测后端时，给测试 profile 准备空白的隔离 MySQL（应用 schema 及 002–014）和独立 Redis。用 `SPRING_DATASOURCE_URL`、`SPRING_DATA_REDIS_PORT` 指向隔离服务，并以独立的配置目录提供测试配置。执行 `mvn -Dtest=ProductCacheIntegrationTest,ProductSearchApiTest,OpenApiIntegrationTest test`。浏览器入口继续使用 `npm run acceptance`，按[补强 1](46-strengthening-acceptance-ci.md)准备构建产物和可执行文件。

## 学习顺序

先单独看 `ProductCache`。把 `Snapshot` 的剩余 TTL、`inFlight` 的等待、`revision` 校验及 `evictAfterCommit()` 连起来。短代码一起看 `ProductController`、`ProductService` 和 `ProductListItem`，对应读列表与读单个商品的两个调用链。Vue 侧对照 `PurchaseView.load()` → `catalog.productById()` → `GET /api/products/{id}`，以及页面如何处理 404。
