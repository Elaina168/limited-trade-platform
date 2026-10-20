# 第二十七阶段：真实双Java实例联调与故障演练

## 本阶段实现

复用JDK 21.0.10、已有可运行JAR、Nginx 1.30.6、WSL Redis和第二十六阶段日志系统，真正启动两份Java进程，验证入口分发、共享Session、权限/CSRF、跨实例退出、实例停止与恢复、实际日志关联。不新增Java业务功能，不安装中间件，不使用Docker。

```text
浏览器 / 验证脚本 → Nginx:18081 → Java node-a:8081 / node-b:8082
                                      ↓             ↓
                                  共享Redis Session
                                      ↓             ↓
                            各自日志 → Alloy → Loki → Grafana
```

本阶段是只读集成检查模式：两实例均关闭app.scheduling.enabled和app.order-message.enabled，避免启动时扫描或消费已有订单。复用本机普通账号登录，仅查询身份、权限和订单，不创建订单，不调用支付/取消/履约写接口。这个模式不代表完整交易系统运行；自动关单、Outbox发布与MQ消费不在本轮验收范围。

## 新增文件作用

| 文件 | 作用 |
|---|---|
| scripts/start-native-cluster.ps1 | 启动指定Java实例或两个实例，检查Redis、端口及进程记录，等待HTTP就绪 |
| scripts/stop-native-cluster.ps1 | 按PID、实际程序路径和启动时间核对身份，停止指定实例或全部实例 |
| scripts/verify-native-cluster.py | 真实HTTP验证；显式--failover才会停止并恢复node-a |
| docs/superpowers/plans/2026-10-20-native-cluster.md | 实施范围、验收步骤与执行记录 |

运行记录和控制台输出位于忽略的tools/native-cluster，JSON应用日志位于logs/node-a.log和logs/node-b.log。配置文件不保存账号密码；验证脚本读取已忽略的config/local-accounts.txt及Grafana本机账号文件，不输出密码、Cookie或CSRF令牌。

## 实现流程

1. 启动脚本从当前java命令读取java.home，改用该目录下真正的bin/java.exe。PATH中的Oracle javapath可能是转发程序，不能直接把转发器PID当成JVM的PID。
2. 检查目标端口和进程记录，向Windows的127.0.0.1:6379发送Redis PING并等完整响应。Redis仍复用现有WSL服务；无需安装新Redis。
3. 两实例运行相同JAR并启用shared-session，使用共同的独立命名空间limited-trade:session:native-check:v1。该检查命名空间不与默认limited-trade:session:v1混用。
4. 分别传入APP_INSTANCE_ID=node-a/node-b和独立APP_LOG_FILE，端口8081/8082只绑定127.0.0.1。每份JVM使用-Xms64m、-Xmx256m，属于本机检查参数，不是生产容量结论。
5. 等/api/ping返回200后，再校验实际监听端口的OwningProcess等于本次启动PID，然后才标记就绪。保存PID、已解析的真实JVM路径、启动时间和端口。
6. Nginx仍使用已有cluster配置在18081轮询，不使用粘性会话。登录与退出状态通过Redis共享，业务查询仍由MySQL完成。
7. Alloy自动发现node-a/node-b日志，从JSON的instance字段区分实例；验证通过Grafana数据源代理查询真实http_request与Nginx记录。
8. 故障演练只停止管理脚本创建的node-a，node-b保持运行。finally中尝试恢复node-a，确认入口重新分发给它，且共享登录状态仍可使用，随后执行跨实例退出。

停止脚本会终止目标Java进程，这适合本阶段故障演练，不是优雅停机或零失败更新方案。脚本不会停止其他Java、Redis、RabbitMQ、Nginx或日志系统。

## 如何运行

PowerShell 7.4以上，在项目根目录执行。先保持原MySQL和Redis运行，并启动已有日志系统。Redis所在WSL可以通过新开一个PowerShell窗口进入，保持窗口开启：

```powershell
wsl -d Ubuntu-22.04
```

Linux中用redis-cli PING确认返回PONG。若Redis尚未启动，执行sudo service redis-server start。仅WSL内PING成功还不够，启动脚本会进一步检查Windows连接。

启动两份Java及已有Nginx入口：

```powershell
pwsh -NoProfile -File .\scripts\start-native-cluster.ps1
pwsh -NoProfile -File .\scripts\start-nginx.ps1 -Mode cluster
```

检查入口为http://127.0.0.1:18081/。启动脚本不会自动启动或接管依赖组件；若日志系统没有运行，另执行scripts/start-observability.ps1。

默认只验证，不停止实例：

```powershell
$env:PYTHONUTF8 = '1'
python .\scripts\verify-native-cluster.py
```

执行包含中断的故障演练（会让一部分入口请求返回502）：

```powershell
python .\scripts\verify-native-cluster.py --failover
```

可单独管理某个实例，或者停止整个检查环境：

```powershell
pwsh -NoProfile -File .\scripts\stop-native-cluster.ps1 -Instance node-a
pwsh -NoProfile -File .\scripts\start-native-cluster.ps1 -Instance node-a
pwsh -NoProfile -File .\scripts\stop-native-cluster.ps1
pwsh -NoProfile -File .\scripts\stop-nginx.ps1 -Mode cluster
```

Java停止后不清空共享会话；正常退出接口会删除用户会话，其他会话按闲置30分钟过期。不要把全局Redis FLUSHDB作为清理方式。

## 本次最小真实验证结果

2026-10-20：

- 真实Java进程分别监听8081、8082，启动PID与实际监听PID一致；Nginx监听18081，均仅127.0.0.1。
- 在node-a获取CSRF，在node-b登录成功，Session编号轮换；旧编号访问身份接口返回401。
- 两个实例都识别同一普通用户，管理接口均403；登录前旧CSRF和缺CSRF退出均403。
- 两个实例只读查询订单列表一致，6次入口身份查询实际到达两个上游；未创建或修改订单。
- 从每个上游选一条真实请求，通过Grafana代理查询到对应Java http_request和Nginx访问记录。不同于第二十六阶段的verification样本，本轮记录来自真实JVM请求。
- 停止node-a后，6次请求实际为[502, 200, 200, 200, 200, 200]；5次成功请求全部由node-b处理并保持身份。502在集中日志中仅有Nginx记录，符合请求未到达后端的事实。
- 恢复node-a后，它重新接收入口请求，原共享登录状态仍有效。在node-b取得新CSRF、node-a退出后，两实例身份接口均401。
- 应用与Nginx访问日志未发现本轮密码、前后Session编号或CSRF令牌；报告只保存验证结果和HTTP状态，不保存账号、订单正文或会话数据。

第一轮故障演练揭示Oracle javapath转发器的问题：停止转发器后实际JVM仍监听，恢复被占用端口检查拒绝。已根据真实命令行、进程关系和端口归属仅清理本轮遗留进程，修正为直接启动java.home下的JVM，并加入监听PID校验；随后相同故障演练通过。没有通过更改PATH或批量停止Java绕过问题。

本机证据：tools/native-cluster-red.log、native-cluster-launcher-red.log、native-cluster-green.log，以及tools/native-cluster/verification.json和实例记录。均不进Git。

## 验收边界

本阶段只完成真实多进程入口、共享会话、权限/CSRF、只读订单、故障恢复和集中日志链路。没有全量回归、没有改Java或重新打包JAR、没有Docker部署、没有提交推送。

MQ故障/重投、真实写交易的综合验收、数据库备份恢复、HTTPS、公网部署、多主机高可用和真实浏览器完整操作仍待对应专项。一次实例故障出现502是当前proxy_next_upstream off和被动失败排除的实际表现，不宣称零失败切换。

后续更新：第二十八阶段已在隔离环境完成真实HTTP写交易及Broker暂停/恢复、Outbox补发与消息幂等；本阶段保留的8081/8082仍是只读检查模式。最新范围见[交易与MQ故障记录](28-trade-drill.md)。
