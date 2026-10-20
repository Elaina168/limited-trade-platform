# Native Trade and RabbitMQ Drill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在隔离测试数据上验证两份真实JVM经Nginx的购买履约、MQ超时与回补、Broker中断后的Outbox补发及重复消息幂等，并关联集中日志。

**Architecture:** 新增一组可重复执行的JUnit集成验收，控制两个真实JAR进程8083/8084和独立Nginx入口18082。使用现有空测试库、现有MQ测试vhost与随机拓扑、随机Redis前缀，保留原8081/8082只读检查环境。

**Tech Stack:** 现有JDK21、Maven、Spring Boot测试、JDBC、Java HttpClient、RabbitTemplate、Windows RabbitMQ/Erlang、Nginx、Alloy/Loki/Grafana。不安装组件或Docker。

**Spec:** 用户已批准“完整交易链路联调与RabbitMQ故障演练”：正常交易、超时回补、短暂MQ中断/恢复、重复消息、实际日志定位；隔离测试数据，最小验证，不全量回归。

## Global Constraints

- 测试JVM明确连接limited_trade_platform_test，启动前确认测试表为空且无触发器；不得清空、改写开发库。
- MQ测试vhost必须与开发vhost不同；仅删除本次随机拓扑。Broker应用暂停前确认本项目节点且无开发业务实例消费，本轮finally恢复。
- 测试JVM开启Outbox调度和MQ消费；关闭后台扫单以免掩盖MQ结果，扫描补偿单独调用既有真实入口验证。
- 原订单期限仍为15分钟。加速通过测试队列TTL和仅修改本次订单expires_at实现，不改生产规则。
- 两真实JVM、Nginx均仅监听127.0.0.1；记录与进程句柄保留在本轮测试生命周期。
- 不把模拟支付称为接入真实支付；不做全量回归，不自动提交推送。

## Review Focus

- 跨进程相同requestId不能重复扣库存或制造多个Outbox事件。
- MQ不可用时HTTP下单仍提交，Outbox失败尝试落库，恢复后重试获确认。
- 扫描先关闭的订单收到迟到消息，或同一消息重复投递，都不能再次回补。
- 首次超时关闭需来自真实MQ消费者，不能让扫描悄悄替代。
- 失败时停止本轮进程并恢复Broker应用，再仅清理本次夹具、Redis键与MQ拓扑；保留其他运行环境。

## Task 1: 独立入口和真实交易验收

- [x] 先写NativeTradeDrillIntegrationTest，目标配置缺失时验证RED。
- [x] 新增deploy/nginx-trade-drill/nginx.conf，复用公共规则；Nginx脚本新增trade-drill模式，默认不变。
- [x] Alloy新增明确的trade-drill访问日志目标并原生验证、重载。
- [x] 以两个真实JVM、独立入口和真实用户会话跑通下单/支付/发货/收货以及幂等创建。

## Task 2: MQ与补偿验收

- [x] MQ TTL触发关闭，重复真实投递不重复回补。
- [x] 暂停本项目RabbitMQ应用，HTTP创建订单、Outbox记录PENDING与失败尝试；验证扫描补偿一单。
- [x] 恢复Broker应用，Outbox补发SENT，MQ关闭另一单，迟到消息不重复回补；从集中日志确认请求与关闭事件。

## Task 3: 收尾

- [x] 最小专项通过后核对清理、原检查进程和依赖状态；不扩大到全量测试。
- [x] 一次独立只读复核，更新第二十八阶段学习记录、README及路线。

## Execution ledger

- 2026-10-20：原MySQL、Redis、两个只读Java、cluster Nginx和集中日志运行；RabbitMQ未监听。复用已有RabbitMQ4.3.6/Erlang与原脚本启动，不下载或安装。
- Ruling: 原生交易验收在独立8083/8084/18082运行 — 不替换现有用户检查环境；代价是多两个短生命周期JVM。测试数据清理后进程全部退出。

- 完成：3项真实专项通过（修正后47.71秒），默认无开关3项全部跳过。最终入口PID保护新增后仅重跑正常交易1项通过（22.45秒）。未全量回归。
- 独立复核修正：故障演练必须显式opt-in，stop_app前设置恢复标记，逐个停止JVM，活动进程存在时拒绝删夹具；补齐收货时间文本断言和独立Nginx PID前置保护。修正后无剩余阻断问题。
- 资源：测试表行数恢复由AfterAll断言；管理API确认drill队列/交换器无残留，8083/8084/18082已退出。原8081/8082/18081及集中日志保持，Broker应用恢复。无Java业务修改，无重打JAR，无提交推送。
