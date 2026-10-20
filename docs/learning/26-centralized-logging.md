# 第二十六阶段：集中日志收集与检索

## 本阶段完成的链路

```text
Java JSON活动日志 ─┐
                  ├→ Alloy → Loki → Grafana（仪表盘 / Explore）
Nginx JSON访问日志 ┘
```

2026-10-20按用户澄清：只有涉及Docker的部署延后；能够原生下载运行的组件直接实施。本阶段使用官方Windows amd64便携程序，不使用Docker、不安装系统服务。Java业务代码和数据库没有修改。

| 组件 | 版本 | 本机位置 | 入口 |
|---|---|---|---|
| Alloy | 1.21.0 | tools/alloy-1.21.0/alloy-windows-amd64.exe | http://127.0.0.1:12345/ |
| Loki | 3.7.8 | tools/loki-3.7.8/loki-windows-amd64.exe | http://127.0.0.1:3100/ready |
| Grafana OSS | 13.2.4 | tools/grafana-13.2.4/grafana-13.2.4/bin/grafana.exe | http://127.0.0.1:3000/ |

各包已比对官方SHA256并执行版本命令。确切下载地址、校验来源和SHA256见`deploy/observability/tools.json`，程序和压缩包均保存在忽略的tools目录。三包共约599 MiB，解压程序约1.83 GiB，含保留压缩包合计约2.42 GiB，日志数据另计；轻量验证时三主进程工作集合计约509 MiB，只是本机快照，不是固定资源上限。

## 文件作用

| 文件 | 作用 |
|---|---|
| deploy/observability/loki.yml | 本机单节点存储、TSDB v13索引、WAL、7天保留与compactor删除 |
| deploy/observability/config.alloy | 发现文件、解析JSON与原始时间、设置来源/实例标签、发送Loki |
| deploy/observability/grafana.ini | 本机监听、数据目录、登录限制与关闭非必要自动下载/遥测 |
| deploy/observability/provisioning/datasources/loki.yml | 自动配置uid为limited-trade-loki的数据源 |
| deploy/observability/provisioning/dashboards/trade.yml | 自动加载本地仪表盘文件 |
| deploy/observability/dashboards/trade-logs.json | 提供来源、实例和追踪编号筛选的日志面板 |
| deploy/observability/tools.json | 固定已验证的官方版本、下载地址、校验值和程序路径 |
| scripts/start-observability.ps1 | 校验配置、生成首次账号、隐藏启动进程并等待就绪 |
| scripts/stop-observability.ps1 | 按PID、可执行路径、启动时间核对身份，停止本项目组件 |
| scripts/verify-observability.py | 检查真实文件采集、Grafana数据源、查询、标签及仪表盘 |

`provisioning/plugins`和`provisioning/alerting`保留空目录，避免Grafana查找可选配置目录时产生错误；本阶段没有插件或告警规则配置。

## 日志如何流动

1. Java继续通过已有Logback配置写`logs/*.log`活动文件。实例启动时设置`APP_INSTANCE_ID`，值写入每条JSON的`instance`字段。
2. Nginx继续写`deploy/nginx/logs/access.log`及`deploy/nginx-cluster/logs/access.log`。Alloy按路径分别标记single和cluster。
3. Alloy每2秒重新发现匹配文件，按读取位置继续读。只采集上述明确目录和JSON访问日志，不采集config、本阶段组件自身日志或Nginx原生error.log。
4. Java使用`@timestamp`（RFC3339Nano），Nginx使用`timestamp`（RFC3339）作为日志时间；解析失败保留正文，使用读取时间，不伪造原始时间。已有历史活动日志首次启动从头读取；压缩归档不在采集范围。
5. Alloy设置app=limited-trade、source=java/nginx、instance标签，移除filename标签；Loki会自动增加固定service_name=limited-trade。查询结果可能还包含临时解析字段或detected_level结构化元数据，不等同于索引标签。
6. traceId、状态码和具体消息保留在JSON正文。traceId不作为索引标签：每个请求一个值会不断增加日志流，查询时用LogQL提取即可。
7. Loki写本机TSDB与文件存储，Grafana通过已配置数据源查询。Grafana为查询界面，Loki负责存储，Alloy负责采集与发送。

数据与读取位置位于`tools/observability-runtime`。Alloy的positions.yml记录文件偏移；Loki有自己的WAL和存储；Grafana使用该目录下独立SQLite数据库，不接入交易MySQL。

## 启动、登录、停止

使用PowerShell 7.4以上，在项目根目录执行：

```powershell
pwsh -NoProfile -File .\scripts\start-observability.ps1
```

浏览器打开 http://127.0.0.1:3000/ ，账号为admin。首次随机密码保存在`tools/observability-runtime/grafana-credentials.json`的password字段；secretKey是Grafana签名密钥，不是登录密码。不要把该文件提交或分享。

页面中进入Dashboards → Limited Trade → 交易平台集中日志。可选择日志来源、实例，或输入追踪编号；也可在Explore中选择Local Loki手写查询。

```powershell
pwsh -NoProfile -File .\scripts\stop-observability.ps1
```

停止会终止本项目的采集、界面和存储进程，保留数据、账号及读取位置。这不是Linux信号驱动的优雅停机，不承诺进程终止瞬间尚未保存的记录零丢失或恰好一次。不要在采集追赶期间立即终止；本次重启验证先等偏移保存后才停止。

已有Grafana数据库时，环境变量密码仅用于首次初始化，修改账号文件不会修改数据库里的密码。若在Grafana界面改密，需同步本机文件供验证脚本使用。安装到其他机器时，用新的运行目录初始化自己的账号，不能携带这份本机凭据。

如果换电脑，按tools.json从官方地址下载，核对SHA256并解压到表中的目录。启动脚本不自动联网下载，也不覆盖已有安装。Loki没有额外HTTP认证，本阶段通过只监听本机隔离；三组件均不是公网发布方案。

## 常用查询

全部日志：

```logql
{app="limited-trade"}
```

某个Java实例（实际值由APP_INSTANCE_ID决定）：

```logql
{app="limited-trade", source="java", instance="node-a"}
```

把响应头X-Trace-Id中的实际编号代入下式，关联入口与后端：

```logql
{app="limited-trade"} | json | traceId="这里替换为实际编号"
```

Java错误请求：

```logql
{app="limited-trade", source="java"} | json | event="http_request" | status >= 500
```

入口502：

```logql
{app="limited-trade", source="nginx"} | json | status = 502
```

Nginx拿到后端X-Trace-Id时沿用它，所以可以关联同一次请求。连接不到后端时，入口自己生成编号，此时本就没有对应的Java请求记录，不能据此认定日志丢失。

## 本次最小验证

- 官方三包SHA256、程序版本，以及Alloy validate、Loki verify-config通过；启动遇到Grafana自定义SQLite父目录未创建，已在启动前显式创建并重试成功。
- 三组件实际运行并就绪，3000、3100、9096、12345全部只监听127.0.0.1；重复启动拒绝，已有进程记录不变；临时修改记录中的可执行路径后stop拒绝停止，三个进程保持运行，检查后恢复原记录。
- Grafana匿名读取数据源返回401，使用本机账号的数据源健康检查、仪表盘读取和数据源代理LogQL查询通过。
- 使用cluster Nginx入口真实发起只读请求，后端未启动时返回502并写入实际访问日志。两个Java实例记录为显式verification=true、event=observability_probe的受控JSON样本；它们借用该编号验证跨来源查询，不能当作真实双Java业务调用。
- 文件→Alloy→Loki→Grafana代理查询到两个受控实例和一条真实Nginx记录，状态过滤通过；series接口确认索引只有app/source/instance/service_name，不含traceId或文件路径。验证样本文件已清理，受控日志在Loki中等待保留策略清理。
- 重启专项先等positions.yml偏移等于文件字节数，停止三组件，停机期间追加一条记录，再启动。旧记录仍可查询，新记录被采集，新的偏移也保存；随后清理专项文件。未宣称网络故障或任意崩溃下恰好一次。
- 7天保留和compactor配置已校验，未等待7天观察删除。没有做多主机采集、公开发布、生产负载、真实双Java交易或完整消息故障排查验收。

本机证据为忽略的tools/observability-red.log、observability-green.log、observability-restart.log、observability-lifecycle.log及运行目录。按用户要求只做本阶段最小检查；没有全量回归，没有修改Java业务或重新打包JAR，没有提交推送。

复查链路时，需要已运行的单实例Nginx入口18080，或者传cluster选择18081；脚本只读访问管理接口，预期未登录401或后端未运行502，不创建订单：

```powershell
$env:PYTHONUTF8 = '1'
python .\scripts\verify-observability.py --nginx-mode cluster
```

盘点时发现旧单实例Nginx遗留PID文件（记录10744，但进程不存在且18080未监听），本阶段未改写该既有状态；使用独立cluster入口完成验证，验后停止本次启动的Nginx。

官方资料：

- https://github.com/grafana/alloy/releases/tag/v1.21.0
- https://github.com/grafana/loki/releases/tag/v3.7.8
- https://grafana.com/grafana/download/13.2.4?edition=oss&platform=windows
- https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.file/
- https://grafana.com/docs/alloy/latest/reference/components/loki/loki.process/
- https://grafana.com/docs/grafana/latest/administration/provisioning/

后续更新：第二十七阶段已补齐真实双Java检查模式的共享会话、只读订单、故障恢复和实际日志关联；历史阶段的受控样本/探针证据不作替换，最新范围见[原生双实例记录](27-native-cluster.md)。写交易与MQ故障仍待专项。
