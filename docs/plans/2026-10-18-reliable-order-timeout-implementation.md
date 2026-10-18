# 可靠消息与订单超时 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task in the current session. Steps use checkbox (`- [x]`) syntax for tracking. 学习者已有分工：主助手编写和讲解，不主动派实现子代理；完成后可按已有流程做一次只读复核。

**Goal:** 订单与超时发送意图同事务落库，独立可靠投递RabbitMQ，消费复用原条件关单，消息异常时数据库扫描仍可兜底。

**Architecture:** 保留单Boot应用和MySQL库存权威。本地消息表仅服务订单超时场景，采用事件行锁、publisher confirm与mandatory/return判断发送结果；TTL/死信实现等待和失败归档，消费者调用既有OrderService.closeExpiredOrder。

**Tech Stack:** Java21、Boot3.5.16、MyBatis3.0.5、MySQL8.0.45；新增Starter AMQP3.5.16，Boot管理Spring AMQP3.2.12、Rabbit Java Client5.25.0、Spring Retry2.0.13。运行环境拟为RabbitMQ4.3.6＋Erlang/OTP28.5.0.8（Windows64位）。

**Spec:** [已确认业务设计](2026-10-18-reliable-order-timeout-design.md)。

状态：2026-10-18执行完成；离线package全量218项通过，0失败/错误/跳过，独立只读复核与数据/打包检查完成。未提交、推送或合并。

## Global Constraints

- 内层仓库D:/codex/Java/limited-trade-platform执行Git和Maven，保留全部现有修改；不自动提交、推送、合并或新建工作树。
- 新文本UTF-8，代码注释中文；不打印本机数据库/RabbitMQ凭据、Erlang cookie。
- 不修改普通/活动下单接口、15分钟支付期限、用户→活动→商品的锁顺序、幂等比较或一次归还约束。
- 只增加本阶段必要代码，不写通用事件总线、可插拔发送接口或多套库存实现。
- 发送等待队列TTL900000毫秒；投递任务默认3秒一轮、20条一批；确认等待2秒；生产失败退避5/10/20/40/60秒，之后60秒。
- 消费总共3次尝试，重试间隔1秒、2秒；仍失败进入专用失败队列；数据库扫描继续默认30秒一轮、100条一批。
- 不讲测试源码；向学习者展示新增业务代码、关键语法和调用链，旧业务只讲到新增点。
- 实施和下载必须在学习者确认本计划及下列清单后进行；现有“准备实现”不代替指定安装内容的确认。

## Review Focus

1. Broker已收、确认丢失或本地SENT提交失败：允许重复投递，禁止重复归还库存；Task2/3验证。
2. Broker ACK但消息不可路由：必须保留PENDING，不能只看ACK；Task2验证。
3. 原商品缓存afterCommit抛异常：数据库和Outbox可能都已提交；同键重试不得再建事件；Task1验证。
4. 消费早到、晚到、与支付或扫描竞争：数据库expires_at与条件状态转换仍是依据；Task3验证。
5. 消息功能关闭或测试自动调度关闭：订单发送意图仍保存，旧扫描入口仍可手动调用；Task1/3验证。

## 环境与下载清单（已授权并完成）

2026-10-18再次盘点：Windows命令/服务/本机MQ端口未发现可用RabbitMQ/Erlang/Docker；WSL Ubuntu22.04的本地包记录未安装相应组件。Ubuntu现有APT元数据候选为RabbitMQ3.9.27和Erlang24.2.1，本计划不用这一旧组合。

官方当前RabbitMQ4.3.6兼容表支持Erlang27.0至28.x，采用OTP28.5.0.8；不采用官方最新但不在该兼容范围内的OTP29.1.2。

| 内容 | 来源与缓存/安装位置 | 用途 |
|---|---|---|
| rabbitmq-server-windows-4.3.6.zip，70,605,921字节 | GitHub rabbitmq/rabbitmq-server官方v4.3.6发行；缓存tools/downloads/，解压tools/rabbitmq_server-4.3.6/ | Broker与随包管理插件 |
| otp_win64_28.5.0.8.exe，154,266,576字节 | GitHub erlang/otp官方OTP-28.5.0.8发行；缓存tools/downloads/，安装tools/Erlang OTP/ | RabbitMQ必需运行时；执行官方安装器的标准安装登记，并非仅解压 |
| Starter AMQP3.5.16及缺失传递依赖 | Maven Central，tools/maven-repository/ | Java发送、接收、重试与确认机制 |

两个服务端/运行时文件合计224,872,497字节，约225MB，不含Maven依赖和解压后空间。下载后核验GitHub资产SHA-256：

```text
rabbitmq-server-windows-4.3.6.zip
48161b084f1257edc112b24fccda378cc3e83c8e4b94984801cd6651817981f1

otp_win64_28.5.0.8.exe
145deb0386b98bed91ed2c2ba37197cc89041bd4b1402f4e011d5607c511375c
```

RabbitMQ按普通进程运行，不注册Windows服务；启动脚本只为当前进程设置ERLANG_HOME/RabbitMQ路径，不永久改PATH。运行数据/日志/节点配置在tools/rabbitmq-runtime/。AMQP5672和管理15672只监听127.0.0.1；启用随包management插件，无需另外下载插件。

为项目创建独立开发虚拟主机limited-trade与测试虚拟主机limited-trade-test，独立随机凭据写被忽略的config/rabbitmq.properties，模板为config/rabbitmq.properties.example。实际文件必须加入.gitignore；不使用参考仓库凭据，不输出口令。Java外部配置沿用强制读取原则，新增RabbitMQ配置缺失应明确失败。

官方依据：
- https://github.com/rabbitmq/rabbitmq-server/releases/tag/v4.3.6
- https://github.com/erlang/otp/releases/tag/OTP-28.5.0.8
- https://www.rabbitmq.com/docs/which-erlang
- https://www.rabbitmq.com/docs/install-windows-manual

## 文件与接口约定

以下为拟新建标识，实施前再次核对是否冲突。主代码包com.elaina.limitedtrade.order.message，不为一次性动作额外抽象接口。

| 文件 | 责任与主要接口 |
|---|---|
| sql/007_order_timeout_outbox.sql | 专用表、order_id唯一索引、待发送候选索引、订单外键；不为测试添加级联删除 |
| order/message/OrderTimeoutOutbox.java | 可变JavaBean，映射设计规定字段，支持自增id回填 |
| order/message/OrderTimeoutOutboxMapper.java及mapper/OrderTimeoutOutboxMapper.xml | insertEvent(event)、findPendingIds(now,limit)、lockPending(id,now)、markSent(id,attemptedAt)、markFailed(id,nextAttemptAt,error) |
| order/message/OrderTimeoutMessage.java | record(Long eventId, Long orderId, LocalDateTime expiresAt)，消息边界非空及正编号校验 |
| order/message/OrderTimeoutMessageSender.java | send(OrderTimeoutMessage message)：等待确认、检查退回；失败抛明确的AMQP异常，线程中断恢复标记并向外传播 |
| order/message/OrderTimeoutOutboxService.java | @Transactional boolean publishOne(long id)，锁事件后调用sender并更新数据库 |
| order/message/OrderTimeoutOutboxScheduler.java | publishPending()：查候选，跨Bean逐条调用publishOne |
| order/message/OrderTimeoutRabbitConfiguration.java | 3个Direct交换机、3个队列及绑定、受限JSON转换、消费重试配置 |
| order/message/OrderTimeoutConsumer.java | onTimeout(OrderTimeoutMessage message)：校验MQ边界后调用既有关闭方法 |
| config/TaskSchedulingConfiguration.java | @EnableScheduling，app.scheduling.enabled控制调度设施，替代原OrderSchedulingConfiguration |
| scripts/start-rabbitmq.ps1、scripts/stop-rabbitmq.ps1 | 按本项目路径启动/停止自己的节点，不按端口强杀其他进程 |

实体、返回类型和Mapper参数细化：insertEvent返回int；findPendingIds返回List<Long>；lockPending返回OrderTimeoutOutbox或null（未获执行权）；两个状态更新返回受影响行数。SQL显式映射全部字段。attempt_count用BIGINT/Long保存已落库尝试数，last_error最多500字符；数据库异常不吞掉。

配置：新增app.order-message.enabled控制投递与监听；订单始终登记事件。app.order-message.name-prefix默认为limited-trade，构成设计中队列/交换机名；测试使用独立随机前缀。app.order-message.wait-ttl-ms默认900000，可在隔离验证中缩短。

## Task 1：准备运行环境并完成订单/Outbox原子保存

**新增文件：** 运行脚本、外部配置模板、007迁移、Outbox实体和Mapper/XML、OrderTimeoutOutboxIntegrationTest.java。

**修改文件：** pom.xml、.gitignore、application.yml、application-test.yml、OrderService.java；已提交夹具清理涉及OrderConcurrencyTest.java、ActivityConcurrencyTest.java、ActivityOrderApiTest.java、ProductCacheIntegrationTest.java。

**Consumes:** TradeOrder.getId/getExpiresAt、原OrderService.createOrder及外部数据库配置。

**Produces:** Outbox表与Mapper接口；首次订单保存后产生唯一PENDING事件，原幂等分支不登记。

- [x] 学习者确认后，下载并校验上述文件，核对安装器支持的安装参数，安装Erlang到指定目录、解压RabbitMQ；不接受校验不一致的文件。
- [x] 配置回环监听、项目节点和虚拟主机，随机生成本机凭据；通过CLI检查Broker与Java连接，保持现有WSL Redis在验证期间运行。
- [x] 保存开发库公开字段基线、测试库行数和当前Git状态；先核对数据库实际表结构，只对两个项目数据库各执行一次007。
- [x] 添加Starter AMQP并下载缺失依赖；之后的红绿测试及打包使用--offline。
- [x] 写原子登记红测试：普通/活动首次下单各有一事件，同requestId并发重试仍一条；断言例如：

```java
assertEquals(1, jdbc.queryForObject(
        "SELECT COUNT(*) FROM order_timeout_outbox WHERE order_id=?", Integer.class, orderId));
```

- [x] 运行新测试，观察旧下单路径没有登记事件导致的0/1差异；不要把缺依赖或缺表错误当成业务红测试。
- [x] 在OrderService首次insertOrder之后构造事件并insertEvent，使用同一事务、同一expiresAt；原幂等提前返回保持。将所有相关自有夹具Outbox删除放在订单删除之前。
- [x] 用测试库自有触发器故意拒绝事件插入，确认订单/商品/活动全部回滚；检查触发器所有权并在finally清理。另用明确回滚事务验证不残留事件。
- [x] 验证提交后缓存删除异常时订单和事件已落库，原requestId重试不重复建事件；仅在测试中控制该外部边界，不改生产代码增加测试入口。
- [x] 新消息开关关闭时仍保存事件，且既有普通交易不依赖Broker在线。运行专项及既有订单/活动/缓存用例，确认测试库自有夹具清理。

Run：项目Maven --offline --batch-mode --no-transfer-progress "-Dtest=OrderTimeoutOutboxIntegrationTest,OrderApiTest,OrderConcurrencyTest,ActivityOrderApiTest,ActivityConcurrencyTest,ProductCacheIntegrationTest" test。

Expected：0失败、0错误，事件与订单原子性、幂等以及旧业务均通过。只更新计划复选项，不提交。

## Task 2：可靠投递、退避重试和事件行竞争

**新增文件：** 消息record、MessageSender、OutboxService、OutboxScheduler、Rabbit配置，以及OrderTimeoutSenderTest.java、OrderTimeoutPublisherIntegrationTest.java。

**修改文件：** 应用MQ参数；配置共享调度设施，删除旧的OrderSchedulingConfiguration并将其能力迁入TaskSchedulingConfiguration；保留旧扫描Bean供手动验证，按各开关控制定时方法的执行，不因关闭扫描使其他调度一并失效。

**Consumes:** Task1 Mapper和事件，既有Boot ObjectMapper。

**Produces:** send(message)、publishOne(id)、publishPending()；发送成功SENT，失败可恢复PENDING。

- [x] 写sender红测试，断言ACK无退回成功、ACK有退回失败、NACK失败、确认超时失败；边界替身仅模拟Broker回执，不替代数据库事务验证。
- [x] 实现发送：每次调用使用new CorrelationData()产生独立关联id，稳定eventId放在消息正文；RabbitTemplate.convertAndSend带correlationData，getFuture().get(2,SECONDS)，再检查Confirm.isAck()与getReturned()。已读3.2.12官方源码明确退回在future完成前填充，实施时再对照实际JAR签名。
- [x] 配置publisher-confirm-type=correlated、publisher-returns=true、template.mandatory=true；JSON转换器使用现有ObjectMapper并限制到消息包，消息持久化。读取参数失败/发送失败不得标成功。
- [x] 写publishOne红测试：真实MySQL候选锁、5/10/20/40/60秒退避、两worker竞争；ACK后本地状态保存失败时仍可重投。用真实SQL失败验证本地事务回滚，不只mock事务管理器。
- [x] 实现独立事务：lockPending使用FOR UPDATE SKIP LOCKED；null正常返回false；网络阶段仅捕获可识别的AMQP发送失败，登记PENDING错误和下次时间；数据库异常、线程中断不得吞掉。成功markSent更新状态；事件行锁已保证目标存在，依据项目规则不为不可能的行消失添加检查。
- [x] 实现3秒fixedDelay、每批20条的扫描器，逐条跨Bean调用；保持原30秒数据库扫描，测试profile app.scheduling.enabled=false，手动调用入口。
- [x] 在专用测试虚拟主机以随机前缀验证真实可路由ACK、不可路由退回、独立客户端连接拒绝后的PENDING与恢复投递。测试发布目标不得是开发队列；NACK/确认丢失用边界注入单独标注，不冒充真实Broker故障演练。
- [x] 验证单条SENT更新提交失败后的重复消息不影响本地状态恢复；检查消息JSON字段及eventId稳定，物理发送关联id不同。

Run：项目Maven --offline --batch-mode --no-transfer-progress "-Dtest=OrderTimeoutSenderTest,OrderTimeoutPublisherIntegrationTest,OrderTimeoutOutboxIntegrationTest" test。

Expected：错误回执不能标SENT，失败可重试，真实行锁下同一轮只有一个worker获得执行权；不承诺网络恰好一次。

## Task 3：到期消费、失败归档与原扫描共同工作

**新增/修改文件：** OrderTimeoutConsumer.java、Rabbit配置、OrderTimeoutMessagingIntegrationTest.java，以及调度开关验证；复用OrderService.closeExpiredOrder，不复制库存逻辑。

**Consumes:** Task2消息/拓扑、原closeExpiredOrder(long,LocalDateTime)。

**Produces:** 正常返回AUTO确认；业务异常重试3次后拒绝回队并死信到failed。

- [x] 先写真实Broker红测试：等待到期后关闭普通/活动订单；重复消息只归还一次；已支付/取消/关闭正常退出；提前到达仍保持待支付。
- [x] 监听器接收OrderTimeoutMessage，使用Jakarta Validator校验消息边界的非空及正编号，然后调用原关闭方法；异常向容器传播。配置AUTO、预取1、业务重试1/2秒及拒绝回队归档。
- [x] 验证畸形JSON、缺字段、非法类型头不会进入交易操作，并确实进入失败队列；转换异常与业务异常的重试差异以真实结果记录，不仅检查配置文字。
- [x] 验证数据库归还INT溢出引起事务回滚、消息多次消费失败归档；修复隔离夹具后重投原消息，确认只归还一次。
- [x] 验证消费端数据库提交但确认前重复投递：状态已CLOSED，数量不再归还。另覆盖Redis afterCommit失败后的重复消费与原TTL边界。
- [x] 关闭监听器、手动扫描关闭订单后恢复监听；迟到消息不得改变终态或重复还库存。确认过期首次支付仍拒绝，支付/取消/消费/扫描竞争仍合法。
- [x] 测试TTL缩短只用于独立前缀；SQL截止时间在隔离夹具中显式设置，不把提前消息强制当过期；停止自有监听器后再删除自有队列/交换机，防止重声明竞争。

Run：项目Maven --offline --batch-mode --no-transfer-progress "-Dtest=OrderTimeoutMessagingIntegrationTest,OrderTimeoutConcurrencyTest,OrderActionConcurrencyTest,ActivityConcurrencyTest" test。

Expected：消费/扫描共享原条件更新，库存和名额一次归还，失败消息可见且恢复重投有效。

## Task 4：一次全量验收与新增代码教学

**新增：** docs/learning/11-reliable-order-timeout.md。

**修改：** README.md；必要时根据实际实施补全环境和启动步骤。不要改旧学习稿历史结果。

- [x] 做一次只读复核，重点检查生产状态与回执、网络失败窗口、消息转换边界、事务代理及测试证据；仅修复本次必要问题。
- [x] 运行项目根目录 .\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress package，将结果写tools下日志。确认真实输出0失败/错误/跳过和BUILD SUCCESS，不预先承诺测试数量。
- [x] 核对开发库公开字段基线、测试库五表清理和专用测试队列清理；新迁移只新增表，不能修改原开发数据。检查JAR不含本机凭据、测试配置和工具。
- [x] 在教学稿展示新增SQL/实体/Mapper、事务中新增登记点、发布及消费完整调用链；已有下单/关单只交代到新增点，不讲测试代码。
- [x] 记录真实验证与未验证范围，更新计划状态；完成后停止，等待提问或“继续”，不自动提交、推送或合并。

## 审阅与执行方式

沿用主助手在本会话逐任务实施的方式，不创建实现子代理。实施前请学习者确认计划覆盖其意图，并授权环境清单；若安装器需要额外系统操作或发生版本变动，说明新事实后再决定，不擅自扩展安装范围。

## 完成记录与实施裁定

- 用户已授权精确下载清单；文件哈希核验一致。Windows安装器需用户确认，由用户完成；实际Erlang安装目录为tools/Erlang OTP，已运行确认28.5.0.8。
- 发送确认、原子保存与投递事务先红后绿；真实Broker验证发现类型头被默认参数推断覆盖，TYPE_ID修复后14项通过。
- RabbitAdmin测试使用Spring管理的实例重新声明拓扑；手工new实例无ApplicationContext，不用其initialize冒充拓扑恢复。
- 连接拒绝使用独立客户端及真实关闭端口，不修改共享连接工厂的地址解析器，不称为Broker服务端宕机演练。
- 完整package218项、原数据基线、五表清理、触发器/队列清理及JAR排除检查均通过。仅文档收尾不重复全量测试。
- 全量日志tools/order-message-package.log；细节和裁定位于tools/order-timeout-progress.md；教学docs/learning/11-reliable-order-timeout.md不含测试源码。
