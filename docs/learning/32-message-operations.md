# 订单消息监控、告警与故障处理

日期：2026-10-21。

## 这次增加什么

在已有Outbox、RabbitMQ延迟关单、失败归档及扫描兜底上增加只读监控，不改变交易规则或自动重投消息。复用现有Spring AMQP、MyBatis、结构化日志、Alloy 1.21.0、Loki 3.7.8、Grafana 13.2.4，没有增加Prometheus或其他中间件。

- `GET /api/admin/operations/order-messages`：管理员只读快照，已纳入OpenAPI。
- 每30秒输出`event=order_message_health`结构化样本，有异常时为WARN，恢复为INFO。
- Grafana仪表盘UID为`limited-trade-messages`，标题“订单消息监控”，共12个面板。
- Grafana增加“订单消息链路异常”和“订单消息监控采样缺失”两条规则。

访问应用接口仍需要Session和ADMIN角色。未登录401，普通用户403。接口查询不消费、清空、重投消息，也不返回消息正文、用户资料或数据库中的`last_error`。

## 指标口径

| 字段 | 定义 |
|---|---|
| `outboxPending` | Outbox中PENDING记录总数，包括尚未到下一次重试时间的记录 |
| `outboxRetrying` | PENDING且attempt_count大于0的记录数，是仍在等待重试的事件数 |
| `storedFailedAttempts` | 当前保留记录中已提交保存的失败尝试：PENDING取attempt_count，SENT扣除最后一次成功尝试 |
| `oldestPendingSeconds` | 最老PENDING记录从created_at起经过的秒数；没有PENDING时0 |
| `overdueOrders` | 已到expires_at且仍为PENDING_PAYMENT的订单数 |
| `oldestOverdueSeconds` | 上述订单中最大的当前到期延迟；无此类订单时0 |
| `closedLastHour` | CLOSED且ended_at位于最近一小时内的订单数 |
| `closedLastHourMaxDelaySeconds` | 上述订单的最大ended_at减expires_at，表示记录中的关单延迟，不包括事务提交耗时 |
| `waitReady` | 等待队列的待投递消息数；正常TTL等待不视为故障 |
| `readyReady` | 关单队列的待投递消息数 |
| `failedReady` | 失败归档队列的待投递消息数 |
| `readyConsumers` | 关单队列的消费者数，快照中提供 |
| `queues.available` | 三个被动查询都成功且队列存在；这不代表发布权限、所有绑定及端到端链路都健康 |

`storedFailedAttempts`不是永久累计Counter：记录被清理后会减少，未提交的失败也不计入。SENT只代表既有发送器获得Broker确认，不代表订单已经关闭。Outbox统计不因订单已支付或已被扫描关闭而隐藏尚未发送的事件。

队列计数来自`AmqpAdmin.getQueueInfo`，**只包含ready，不包含unacked**。消息可能正在重试消费，因此failedReady为0不能单独证明没有故障。应结合消费者、到期订单、日志与RabbitMQ管理台的unacked观察。

数据库的两个表聚合在同一条SQL中读取；数据库与RabbitMQ之间没有分布式快照，三个队列也按顺序查询。`collectedAt`是本次采样开始时间，不是所有数据同时发生的证明。

多Java实例读取的是同一数据库与队列。看板先按实例取两分钟内最新样本，再跨实例取最大值，**不把共享计数相加**；停止实例的旧样本可能在两分钟窗口内暂时保留。没有新数据或采集失败时显示空值，不把它当作0。

## 配置与告警

默认配置位于`application.yml`：

```yaml
app:
  order-message:
    monitor:
      enabled: true
      interval-ms: 30000
      pending-age-seconds: 60
      ready-messages: 100
      overdue-seconds: 60
```

| 告警标识 | 默认触发条件 |
|---|---|
| `OUTBOX_DELAYED` | 有PENDING事件且最老年龄至少60秒 |
| `ORDER_CLOSE_DELAYED` | 有到期未关闭订单且最大延迟至少60秒 |
| `FAILED_MESSAGES` | failedReady大于0 |
| `READY_BACKLOG` | readyReady至少100 |
| `MESSAGE_QUEUES_UNAVAILABLE` | RabbitMQ查询失败或所需队列缺失 |

阈值用于本机学习项目，不宣称是生产容量标准。历史失败次数或历史关单延迟不会单独持续触发当前故障告警。

RabbitMQ不可用时快照仍返回200，但`queues.available=false`、所有队列计数为null并带异常告警。数据库采集失败会记录`order_message_monitor_error`并返回503，不产生伪造的健康样本。定时采样同样保留明确错误。

Grafana每10秒评估一次：

- “订单消息链路异常”：两分钟内各实例最新`alertCount`的最大值大于0，持续20秒进入Firing。
- “订单消息监控采样缺失”：两分钟完全没有健康采样事件，再持续20秒进入Firing；用于发现应用、数据库或采集链路中断。

第一条无样本时交给第二条判断；查询执行错误本身按Alerting处理。当前只提供Grafana内告警状态及本地WARN日志，**未配置邮件、短信或外部Webhook通知**。

`monitor.enabled=false`只停自动采样，不撤销管理员快照接口。全局`app.scheduling.enabled=false`也会停采样；如果仍启用Grafana规则，之后出现采样缺失告警是预期行为。

更新看板和规则后使用现有`start-observability.ps1`加载配置；已有进程需先按原脚本正常停止。规则文件是JSON格式内容，保存为`.yaml`，属于有效YAML。

## 故障定位与修复

### 1. 先确认监控是否可靠

先查快照中的`queues.available`及最近采样时间，再看`order_message_monitor_error`。数据库503、队列null或日志停采不等于积压为0。检查当前应用的数据库、RabbitMQ vhost和`app.order-message.name-prefix`，避免排查另一套实验拓扑。

默认拓扑为：

```text
limited-trade.order-timeout.exchange
    -- order-timeout.wait --> limited-trade.order-timeout.wait
    -- TTL / DLX --> limited-trade.order-timeout.ready
    -- 消费重试耗尽 --> limited-trade.order-timeout.failed
```

实际前缀以目标进程配置为准。进入ready的交换器为`${prefix}.order-timeout.dlx`，路由键为`order-timeout.ready`。

### 2. Outbox延迟或发送失败

按订单编号检查`order_timeout_outbox`中的`status`、`attempt_count`、`next_attempt_at`、`expires_at`和`last_error`。错误摘要只在受控运维环境查看，不复制到公开日志或仓库。

依次排查Broker连接、vhost权限、交换器、绑定、mandatory返回及publisher confirm。修复后让现有发布器按`next_attempt_at`继续处理；成功后应转为SENT，等待重试数下降。不要直接把记录手工改为SENT，也不要为补发延长订单expires_at。

即使等待队列里的TTL从重新发送时计算，已有扫描兜底仍按原订单expires_at关闭，不能借此延长付款时间。

### 3. 消费失败或关单延迟

同时检查ready的ready/unacked、消费者是否运行，以及failed队列。按消息中的`eventId`、`orderId`查询原Outbox和订单，不把HTTP的traceId与业务事件编号混为一谈。

检查原订单是否真的到期，库存及活动额度恢复是否失败，数据库约束是否拒绝更新。若尚在处理或按既有有限重试等待，先观察，不重复灌入同一消息。若扫描已经关闭订单，也需要确认资源只归还一次，而不是再次修改库存补数。

### 4. 修复后的重投顺序

本次没有增加自动重投接口。对已确认可重投的合法消息，运维客户端应采用以下顺序：

1. 从失败队列**手动确认模式**取得一条消息，暂不ACK，保留原正文和必要类型头。
2. 校验消息结构与原订单、Outbox关系，确认故障原因已经修复。不合法JSON、缺字段、不受信任类型等毒消息应继续隔离，不能批量原样重投。
3. 将合法原消息以持久化方式发送到`${prefix}.order-timeout.dlx`，路由键`order-timeout.ready`，保持原业务标识和截止时间。
4. 同时启用mandatory返回检查和publisher confirm。只有确认未被返回且Broker确认接收，才ACK原失败消息；发送失败或结果不确定时保留或requeue原消息。
5. 若进程在确认后、ACK前中断，可能出现重复消息，依赖现有订单锁及状态判断避免重复归还资源；不宣称恰好一次。

管理台查看消息时选requeue，不能用“先自动ACK取出，再发布”替代上述移交过程。当前没有专用生产重投工具，不用测试里的自动确认取消息写法冒充安全的运维命令，也不执行整队列purge。

### 5. 结果核对

对于原本待支付且已到期的订单，核对状态CLOSED、ended_at，以及商品库存、活动名额和优惠券状态。若订单已支付、取消、关闭或履约，不强制覆盖现有终态。重复投递后资源应保持不变。

核对失败队列和ready/unacked逐步清理、等待重试数下降、没有新的同类错误。新采样应清除相应alert标识，Grafana随后恢复Normal；历史失败尝试仍保留是正常现象。禁止通过清空队列、篡改状态或停监控来消除告警。

## 文件与验证证据

- `observability/OrderMessageMetricsMapper.java`及对应XML：只读统计。
- `OrderMessageDatabaseStats`、`OrderMessageMonitorProperties`、`OrderMessageMonitor`：指标口径、配置、采集和结构化采样。
- `AdminOrderMessageController`：管理员接口；OpenAPI补充503说明。
- `deploy/observability/dashboards/trade-messages.json`及`provisioning/alerting/trade-messages.yaml`：12个面板和2条规则。
- `scripts/verify-message-alerts.py`：以明确标注verification的受控日志验证实际采集、看板查询和告警状态，不写业务数据库或消费业务队列。

Java关联测试覆盖真实MySQL聚合、阈值等号边界、队列不可用/缺失、数据库失败503和角色权限。既有真实RabbitMQ故障修复重投测试增加监控断言：库存恢复失败后归档计数为1且出现FAILED_MESSAGES，修复并重投、重复消息排空后恢复正常，库存与活动额度只归还一次。

本次19项关联测试通过，0失败、0错误：统计SQL1项、快照接口3项、监控判断7项、真实MQ修复重投1项、OpenAPI7项。没有重新运行整个历史测试集。

Grafana验证是另一层证据：受控日志文件实际经过Alloy和Loki，验证规则Firing及恢复；不能把受控样本说成真实交易故障。受控日志文件验后删除，已经进入Loki的verification样本仍按原保留策略清理。

实际读取12个面板并逐一执行LogQL成功，两条已provision规则均观察到Firing及恢复。证据位于忽略的`tools/message-monitor-tests.log`、`tools/message-alerts-verification.log`及`tools/message-alerts-result.json`。

本轮启动发现旧日志组件进程记录已经失效，核对其PID均不存在后按既有停止脚本清理。新启动的Alloy又复现了`Process.Path`在刚启动时暂时不可读，导致停止脚本安全拒绝；将启动记录改为已知的规范化绝对启动路径，停止时仍核验真实路径与创建时间，没有放宽进程身份检查。

本次未全量回归、未改变SQL表结构、未新增监控依赖、未执行生产重投，未接入外部通知或Docker，也不构成生产容量或多主机高可用验收。
