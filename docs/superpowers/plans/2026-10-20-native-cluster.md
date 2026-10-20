# Native Cluster Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 将已有双实例共享Session与集中日志方案用真实Java进程验证，提供可重复的安全启停和故障演练。

**Architecture:** 复用JDK 21.0.10和现有可运行JAR，在8081/8082启动shared-session实例。Nginx 18081轮询，Redis使用两个实例共享的独立检查命名空间，Alloy采集node-a/node-b日志到已有Grafana链路。

**Tech Stack:** PowerShell 7.4以上、Python 3标准库、既有Java/Nginx/Redis/Alloy/Loki/Grafana。

**Spec:** 当前路线第7项的原生多实例验收子范围；依据docs/learning/25-shared-session.md和26-centralized-logging.md，以及用户“原生可直接做、只有Docker延后”的授权。

## Global Constraints

- 不安装新组件、不提交推送、不改Java业务、不新增订单或修改交易数据。
- 检查模式关闭app.scheduling和app.order-message，明确不验收MQ消费、交易写操作或完整生产部署。
- 启停只管理本项目创建并核对身份的Java实例；停止一个实例的演练须显式--failover，并在finally尝试恢复。
- Session、CSRF、密码不写入报告、源码或日志；本机账号从已忽略文件读取。
- Redis复用已有WSL服务，用户保持WSL运行，不启动无限保活程序或安装新Redis。

## Review Focus

- 两实例必须运行相同JAR、相同Redis命名空间，实例日志区分node-a/node-b。
- Nginx不自动重放请求，故障首次可能502，不能将成功的后续请求称为零失败切换。
- Session ID轮换、旧CSRF拒绝、跨实例退出通过真实HTTP测试。
- 停止操作核对PID、可执行路径和启动时间，不能误停已有Java或依赖服务。
- 集中日志检查使用真实Java与Nginx记录，不再用合成双实例样本替代。

## Task 1: 可重复的真实HTTP验证

- [x] 先写scripts/verify-native-cluster.py，未启动时明确失败。
- [x] 覆盖登录前后Session轮换、两个直连实例和入口身份、权限、只读订单查询、CSRF和退出。
- [x] 核对入口实际upstream和Grafana代理中的两个真实实例记录。
- [x] --failover停止node-a并观察502/后续存活实例，恢复后再次检查身份与入口分发。

## Task 2: Java实例生命周期

- [x] scripts/start-native-cluster.ps1检查程序/端口/Redis，使用独立运行目录和命名空间启动，等待实际HTTP就绪。
- [x] scripts/stop-native-cluster.ps1按保存的身份信息停止指定实例或全部实例，保留其他服务。
- [x] 启动失败清理本次已启动实例，记录和日志在忽略的tools/native-cluster及logs下。

## Task 3: 最小验收和文档

- [x] 用户打开WSL后，启动真实Java与cluster Nginx，执行本阶段验证和一次故障演练。
- [x] 只读复核和本次必要的脚本语法、进程身份、监听与日志检查，通过即停止扩大测试。
- [x] 更新docs/learning/27-native-cluster.md、README和roadmap，说明检查模式的限制与实际证据。

## Execution ledger

- 2026-10-20：核对MySQL与集中日志运行；WSL原为Stopped，Redis在WSL内PING成功但短会话结束后不可持续。已请用户打开WSL保持运行，未修改Redis配置。Java21.0.10、Nginx1.30.6与包含Spring Session的JAR均可复用。
- Ruling: 使用只读集成检查模式关闭定时任务和MQ消费 — 本阶段验证入口、共享Session及真实日志，不在启动时处理已有订单；成本是MQ与写交易的综合故障验收仍待后续专项。

- 完成：真实双JVM共享登录、会话轮换、旧Session/CSRF拒绝、普通权限、只读订单、跨实例退出、两上游分发和Grafana真实日志关联通过。故障6次请求为1次502、5次200，恢复node-a后身份保留。
- Ruling: Oracle javapath是转发器，第一轮管理到wrapper而非JVM — 根据java.home改为直接启动真实程序，并核对端口OwningProcess；仅凭本次日志/命令行/端口证据清理遗留JVM，随后同一演练通过。
- 独立只读复核脚本和文档未发现实质问题；只做最小必要验证，未修改Java、未重打JAR、未安装组件或Docker、未提交推送。两JVM、cluster Nginx和日志组件保留运行，用户保持WSL Redis可用。
