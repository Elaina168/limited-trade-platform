# 补强 7 · 多级缓存与故障治理

日期：2026-11-23。[实施计划](../superpowers/plans/2026-11-23-strengthening-07-cache-consistency.md)

## 读取与失效

- Caffeine 3.2.4 由 Spring Boot 管理版本。每个 JVM 最多缓存一份商品列表，写入后 1 秒到期。Redis 热数据只有剩余 TTL 超过 10 秒时才进入本地缓存，旧值的软过期窗口仍由 Redis 控制。
- 本机业务提交后立即清掉本地列表。其他 JVM 通过本地过期重新查询 Redis。同步机制依赖过期时间，不引入失效消息。远端提交前已经取得快照的在途请求仍可能把当时的快照保留在本机 1 秒。
- `ProductCacheStore` 把共享版本与缓存值一起读取。业务提交时用 Lua 更新共享随机版本并删除列表，相关的上架操作也会删除缺失槽。在途回源只在版本匹配时写回。旧任务超出 5 秒时丢弃。跨实例并发冷读各自可以发起一次数据库加载，单实例内仍合并。
- Redis 列表值继续保存 JSON 数组。共享版本另占一个键，负缓存标记含版本与确切 ID，同一个版本的标记才能命中。商品库存写入也会更新共享版本，因此会让旧负标记提前失效。Lua 多键操作基于当前单 Redis 实例。

## 布隆提示

默认关闭，可用 `app.product-cache.bloom-enabled=true` 开启。最多为 4,096 个在售 ID 建立 32,768 位布隆索引，随本地列表一起构建、替换和过期。命中并取得回源额度时，可以跳过 Redis 缺失槽，直接查询 MySQL。假阳性一样查库。冷启动、列表过大、旧索引遗漏新商品或额度耗尽时，使用正常查询路径。索引不承担 404 判定。

`app.product-cache.local-enabled=false` 可关闭 Caffeine 以比较同一批热读。测试在一个本地 TTL 窗口内连续读取 12 次，关闭时需要 12 次 Redis 读取，开启时只有第一次读取 Redis。补强 4 的吞吐和延迟数据保持为原生环境基线，补强 6 的 12 并发冷读合并继续通过专项。此次比较记录读取次数，未重跑完整吞吐基准。

## Redis 故障

还在本地有效期内的列表可以短暂展示。Redis 连接失败或命令超时后，列表与商品 ID 的降级查询共用每 JVM 1 个并发名额，启动间隔至少 250 毫秒。容量耗尽返回 503。降级查询从当前 MySQL 读取，列表只可短暂放入本地缓存，Redis 在缺少可验证版本时不接收这批数据。订单和支付继续走各自的事务及限流。

已提交的写入遇到缓存失效连接错误时，记录 `product_cache_invalidation_failed`。提交方标记存在未完成失效，并暂停使用旧的共享缓存，定时尝试旋转版本和删列表。重试标志在进程内。写入方重启、长期不可用或恢复竞态下，其他节点可能继续读旧 Redis 数据，直到后续成功失效或 Redis 原硬 TTL 到期。跨 JVM 语义是有条件的最终收敛。

## 验证

- 实际 Redis 和 MySQL 隔离测试重现了跨实例列表旧值回填，旧实现允许返回，新实现拒绝。还验证跨实例上架时正在查的缺失标记不能回填。
- 共 63 项相关 Java 测试通过，覆盖缓存基础、跨实例回填与本地过期、失效失败重试、并发降级额度、布隆假阳性和新商品、商品搜索。
- 双 JVM 的回环 HTTP 验收用专用 Redis 和临时 MySQL，测试代理仅切断这两台 JVM 到 Redis 的连接。首次断连暴露了 Lettuce 的 I/O 根因有时会包装成 `RedisSystemException`。已在 Store 归类该连接失败并保留原异常链。复测确认下架提交后另一实例在本地快照到期后读到新列表，断连期间写入正常提交且读取限额回源，恢复后提交方自动旋转共享版本并重建正确列表。
- 可通过 `mvn -Dtest=ProductCachePolicyTest test` 运行无中间件策略测试。准备隔离 MySQL 和 Redis 后，可按[补强 6](51-strengthening-product-cache.md)的方式运行 `ProductCacheIntegrationTest,ProductCachePolicyTest,ProductSearchApiTest`。本机证据位于 `tools/strengthening-07-red.log`、`tools/strengthening-07-tests-final.log` 与 `tools/strengthening-07-http.log`。

## 学习顺序

先读 `ProductCache.getOrLoad()`。理解 Caffeine 命中、Redis 热值、软过期及冷读如何选择。再把 `ProductCacheStore.readList()`、`writeList()` 和 `invalidate()` 串起来，看版本怎样阻止旧回填。最后看 `findProduct()` 与 `reserveDb()`，理解布隆只选择查询路径，以及进入数据库前如何争取降级额度。
