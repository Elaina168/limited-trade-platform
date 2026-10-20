# 第二十六阶段：本机集中日志

## 目的与授权

把现有Java JSON日志和Nginx JSON访问日志集中采集，用Grafana通过时间、实例与traceId查询一次失败。用户已批准Alloy→Loki→Grafana方案，并明确：只有Docker部署延后，能够直接下载安装运行的组件现在直接做。按已有约定完整交付，不在内部步骤反复确认；不自动提交推送。

## 架构

- Windows amd64官方便携程序：Alloy 1.21.0、Loki 3.7.8、Grafana OSS 13.2.4。下载包保存tools/downloads，版本目录位于tools，不进Git；下载后比对官方SHA256。
- Alloy从项目日志目录读取Java活动日志和两套Nginx访问日志，保存读取位置到本机运行目录，发送Loki Push API。不采集Nginx原生error.log（可能包含原始请求行），不递归扫描config或其他用户文件。
- Loki单实例TSDB v13、filesystem存储，24小时索引周期、7天保留，启用compactor删除；不引入对象存储或集群。仅本机监听3100，gRPC本机9096。
- Grafana仅本机3000，禁用匿名访问与注册，管理员密码随机生成于忽略的本机文件，输出不显示密码。配置本地Loki数据源与一个带实例/来源筛选的日志仪表盘；Explore仍可手写LogQL。
- Alloy仅本机12345。固定标签app、source，Java从JSON提取instance，Nginx按目录标识single/cluster。traceId只作为JSON字段查询；移除自动filename标签，避免路径作为索引值。
- 运行数据、进程记录和服务输出保存在忽略的tools/observability-runtime。配置在deploy/observability。配置通过项目根目录或进程环境获得绝对路径，不固化个人盘符。

## 文件与职责

- deploy/observability：Loki、Alloy、Grafana配置，数据源和仪表盘自动装配，官方工具版本与校验记录。
- scripts/start-observability.ps1、stop-observability.ps1：检查配置、启动本项目进程、等待就绪、保存PID并精确停机；不安装系统服务，不接管其他进程。
- scripts/verify-observability.py：最小真实采集和查询验证，不调用交易写接口。覆盖Java两个实例、Nginx关联、可查询错误、Grafana数据源代理、采集进度重启。
- docs/learning/26-centralized-logging.md、README、roadmap：使用、源码作用、查询例子与实际证据边界；修正只有Docker延后的表述。

## 错误与生命周期

配置不合法或端口被占用即失败。启动中发生错误要清理本次已启动的进程并传播错误；停止时核对PID对应的可执行路径，不能误停其他程序。凭据不进命令行，不写入Git。Redis/MySQL/RabbitMQ不属于本阶段组件，不修改或启动它们。

## 验收

1. 官方文件校验、可执行版本、原生配置验证通过。
2. 三组件监听本机并就绪，Grafana身份验证与Loki数据源健康通过。
3. 受控JSON记录经文件→Alloy→Loki→Grafana数据源代理可查，两个Java实例和Nginx记录可以由同一traceId关联；错误条件返回预期日志。
4. Alloy重启后，新记录可查，读取位置保留；不要求网络失败下恰好一次。
5. 验证保留策略实际配置，不能把设置7天等同于等待7天观察删除；受控日志不能当作真实双Java交易部署验收。
6. 只做本阶段必要验证，不全量回归、不改Java业务、不使用Docker、不提交推送。
