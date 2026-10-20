# Centralized Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在Windows本机直接运行日志采集、存储和检索，按traceId关联两个实例与Nginx。

**Architecture:** Alloy读取已有JSON文件并发送Loki，Grafana自动装配数据源和仪表盘。数据、密码、程序及进程状态都与版本化配置隔离。

**Tech Stack:** Alloy 1.21.0、Loki 3.7.8、Grafana OSS 13.2.4、PowerShell 7、Python 3标准库。

**Spec:** docs/superpowers/specs/2026-10-20-centralized-logging-design.md

## Global Constraints

- 只有Docker部署延后；本阶段原生下载运行已授权。
- 仅监听127.0.0.1；不安装系统服务，不改业务和数据库。
- UTF-8文本、中文注释；保留所有已有未提交修改，不提交推送。
- 最小必要验证，通过后停止。

## Review Focus

- 本机现有进程占用端口时不得接管或误停。
- 日志滚动和采集重启保留读取位置；不宣称恰好一次。
- 任意traceId不得进入索引标签。
- Grafana管理员凭据不得进入版本控制或进程命令行。
- 官方组件的本机配置验证与真实数据源查询都必须通过。

## Task 1: 原生工具与配置

- [x] 下载官方Windows包，校验SHA256和版本，记录确切工具路径。
- [x] 创建Loki、Alloy、Grafana配置与自动装配数据源、仪表盘。
- [x] 使用原生配置验证；发现不兼容先定位再做最小修正。

## Task 2: 启停与最小链路验证

- [x] 编写start/stop脚本，后台启动使用Hidden，核对PID和程序路径。
- [x] 编写最小真实采集验证；先运行观察服务未就绪失败，再启动并验证成功。
- [x] 验证两个Java实例和Nginx来源、traceId关联、错误查询、Grafana数据源和采集重启。
- [x] 核实仅本机监听、重复启动拒绝、停止不误伤其他进程。

## Task 3: 文档与收尾

- [x] 记录新增文件作用、运行入口、凭据位置和查询示例。
- [x] 同步路线的Docker边界，区分受控日志验证与真实业务验收。
- [x] 检查本次改动、忽略规则和真实验证结果；保留源码与必要服务，不提交推送。

## Execution ledger

- 2026-10-20：设计自查确认符合用户最新授权；用户的“直接做就可以”覆盖内部重复确认，按当前会话直接执行。复用现有工作目录和未提交阶段成果，不复制到新worktree，也不依技能默认提交。

- 2026-10-20完成：三包官方SHA256和版本验证通过；Alloy/Loki原生配置验证通过。初次Grafana启动因自定义SQLite父目录缺失失败，补齐目录后通过。
- 最小链路：真实cluster Nginx502、两个verification受控Java样本、Grafana身份验证/自动装配/数据源代理查询通过；LogQL解析字段与实际索引标签分别验证。
- 重启专项：偏移等于字节数后停止三组件，停机追加，重启后旧新日志及新偏移检查通过。重复启动和身份不匹配拒停通过，账号/密钥未出现在源码配置或组件输出日志中。
- 独立只读复核未发现实质问题；补齐Grafana可选plugins/alerting空目录。旧single Nginx遗留PID单独记录，未改写；本次启动的cluster Nginx已停止，三个日志组件保持运行。
- 未运行全量测试、未改Java或重打JAR、未部署Docker、未提交推送；本次仅本机集中日志链路，不作真实双Java交易或七天删除验收。
