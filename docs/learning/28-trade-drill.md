# 第二十八阶段：完整交易与RabbitMQ故障联调

## 本阶段完成什么

使用两份真实Java进程、独立Nginx入口、真实MySQL/Redis/RabbitMQ及已有集中日志，完成三项小范围端到端HTTP验收。没有新增订单业务接口或修改交易规则，主要增加一组可重复运行的集成验收和独立入口配置。

```text
验收HTTP客户端 → Nginx:18082 → trade-drill-a:8083 / trade-drill-b:8084
                                    ↓
                   测试MySQL + 独立Redis键 + 测试MQ拓扑
                                    ↓
                         Alloy → Loki → Grafana
```

原8081/8082和18081只读检查环境保持运行，本轮环境结束后自动退出。当前仍未接入真实支付，pay接口验证的是项目已有的模拟支付状态转换。

## 新增和修改文件

| 文件 | 作用 |
|---|---|
| NativeTradeDrillIntegrationTest.java | 管理两个真实JVM和独立入口、准备隔离夹具、发起真实HTTP、验证交易/MQ/日志、恢复和清理本轮资源 |
| deploy/nginx-trade-drill/nginx.conf | 独立18082入口，转发8083/8084，复用已有转发与日志公共规则 |
| scripts/start-nginx.ps1、stop-nginx.ps1 | 新增trade-drill模式，single默认值与cluster模式保持 |
| deploy/observability/config.alloy | 新增独立入口访问日志目标，source=nginx、instance=trade-drill |
| .gitignore | 忽略独立Nginx运行临时目录；日志和tools本来就被忽略 |
| docs/superpowers/plans/2026-10-20-trade-drill.md | 实施范围、验收步骤和执行记录 |

Java测试文件位于src/test/java/com/elaina/limitedtrade/system。它是验收控制程序，不是应用的新Controller或业务Service，也不会被打进运行JAR。

## 数据与运行隔离

- 明确连接limited_trade_platform_test，启动前检查数据库名、全部测试业务表为空且无触发器。遇到已有测试数据立即拒绝启动，不清空原有数据。
- 只在测试库创建本轮随机用户名、商品和地址，正常通过后删除相关订单、Outbox和夹具；测试表行数必须回到启动前。
- MQ使用mq.test-virtual-host并确认不同于mq.virtual-host，队列/交换器前缀为本轮随机limited-trade.drill.*。
- Redis Session、商品缓存和限流使用本轮limited-trade:drill:*前缀；清理时仅扫描该次随机前缀，不执行FLUSHDB。
- JAR从既有target读取。JVM通过环境覆盖测试库、MQ测试vhost及随机前缀；账号密码仍从已忽略的本机配置加载，不写入命令行。
- 两个JVM开启Outbox定时发布和MQ消费；后台扫单关闭，以免扫描提前关闭订单而掩盖MQ消费结果。扫描补偿另由验收控制进程显式调用真实OrderTimeoutScheduler.closeExpiredOrders()。
- 三个新监听端口均127.0.0.1。两JVM由Process句柄管理，只停止本轮进程；本轮Nginx使用独立prefix/PID，并在启动前拒绝已有非空PID记录。

隔离vhost和随机前缀只能隔离业务资源。故障命令rabbitmqctl stop_app暂停的是本项目节点limited_trade@localhost上的整个RabbitMQ应用，会短暂影响该节点所有vhost；Erlang节点仍存在，随后用start_app恢复。运行前应确认没有其他需要保持在线的业务消费者。本轮原两份检查实例关闭MQ消费，故障期间仍保留其进程；不能将此描述为其他vhost完全不受影响。

## 三项真实场景

### 1. 下单、模拟支付、发货、收货

用户在一个JVM取得CSRF，在另一个JVM登录，通过Nginx下单。以相同requestId向两个JVM重复提交，返回同一订单，只扣一次库存、只登记一个Outbox事件。

随后用户支付，管理员登记发货，用户确认收货。验证状态依次PAID、SHIPPED、COMPLETED，发货记录只有一份并有收货时间；购买2件后库存从10到8，支付/履约不再次扣库存。

从支付响应读取X-Trace-Id，通过Grafana数据源代理查询到同一请求的Nginx访问记录和Java order_paid事件。

### 2. 真正的TTL消费和重复消息

下单3件后库存从10到7。仅将这个测试订单的expires_at推进到过去，测试队列TTL设为1000毫秒；生产代码中15分钟支付期限和正常队列配置保持不变。

Outbox自动发布，RabbitMQ等待队列TTL到期后转到消费队列，真实JVM消费者将订单变为CLOSED，库存回到10。此时后台扫单关闭，不能用扫描结果代替MQ结果。

然后通过真实RabbitTemplate向就绪路由再次发送同一订单的超时消息，等待Broker确认和消费ACK统计增加、ready/unacknowledged均为0，库存仍为10。集中日志中只有一次该订单order_closed成功事件。

### 3. Broker不可用、扫描补偿与恢复补发

1. 真实执行stop_app，再通过HTTP创建两个订单，合计扣3件，库存为7。请求仍成功，Outbox为PENDING，attempt_count增加，last_error保存发送失败。
2. 只让其中1件订单过期，显式运行已有真实扫描入口。它变为CLOSED并回补1件，库存为8；另一个2件订单仍待支付。
3. 再让2件订单过期，finally恢复Broker应用。两个JVM自动重试Outbox，事件最终SENT、last_error清空、attempt_count至少2。
4. MQ消费者关闭2件订单并回补，库存回到10；已经被扫描关闭的1件订单收到迟到消息，不再次回补。集中日志可查到恢复后的真实order_closed事件。

测试通过缩短队列TTL和调整测试夹具截止时间加速；没有等待真实15分钟，也没有验证后台扫单的定时间隔。消息可能重复，验证的是业务状态和库存幂等，不宣称恰好一次投递。

## 怎样重新运行

先保持MySQL、WSL Redis、Alloy/Loki/Grafana可用。使用现有scripts/start-rabbitmq.ps1启动本项目Broker，确认本机5672和15672就绪；无需安装或下载新组件。既有JAR应包含第二十五阶段共享Session配置。

这是有意中断Broker的专项，必须显式选择：

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress '-Dnative.trade.drill=true' '-Dtest=NativeTradeDrillIntegrationTest' test
```

普通mvn test或只指定该类但不传native.trade.drill=true时，三项均跳过，不会自动启动交易进程或暂停Broker。

无需手动启动18082及两份交易JVM，验收会管理它们。标准运行失败也会尝试恢复Broker、逐个停止本轮JVM，并汇总报告清理错误；如果仍有本轮JVM存活，会拒绝删除数据库/MQ/Redis资源，避免活动进程继续操作已清理夹具。CLI超时会终止本次控制命令进程树，恢复标记在停止Broker之前设置。

强行终止Maven或关闭整个宿主机不保证JUnit清理回调有机会执行。这种情况下先查看tools/trade-drill中的控制日志、进程命令行和端口，再按本轮身份处理，不批量停止所有Java或清空Redis。

## 实际验证与证据

2026-10-20，最终专项三项通过，0失败、0错误、0跳过；显式开关未启用时三项均跳过。新增入口PID保护后另对正常交易场景做一次最小关联检查。没有执行全量回归。

- 两份真实JAR进程与18082入口实际运行；正常履约、真实MQ TTL、重复投递、Broker暂停/恢复、Outbox失败落库与自动补发均通过。
- 测试结束前核对全部测试表行数回到原值，清理本轮MQ拓扑和Redis前缀；本轮JVM和18082入口退出，Broker应用恢复。
- Alloy新增配置原生validate及HTTP reload成功，独立Nginx配置-t通过；Grafana实际查询到HTTP请求/订单事件与MQ关闭事件。
- 独立复核补入显式开关、Broker恢复标记前置、逐个JVM清理、存活进程拒绝删夹具和收货时间断言，修正后仅重跑关联专项。

本机证据：tools/trade-drill-red.log、trade-drill-default-skip.log、trade-drill-green.log、trade-drill-entry-check.log，tools/trade-drill下的组件/控制日志，以及target/surefire-reports中的专项XML。第一次RED为缺少独立入口配置；加入显式开关后需使用上述命令实际执行。

本阶段没有改Java生产业务、没有重打运行JAR、没有Docker部署、没有提交推送。验证的是本机真实HTTP和实际依赖链路，仍不代表真实支付/物流服务、浏览器完整验收、生产负载、多主机高可用、数据库备份恢复或HTTPS/公网部署。
