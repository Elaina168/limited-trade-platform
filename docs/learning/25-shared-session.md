# 第二十五阶段：多实例入口与Redis共享Session

## 本阶段解决什么问题

Nginx把请求轮流分给8081和8082。如果登录态只留在各自Java进程内存，登录后的请求换实例就可能变成未登录。本阶段使用Spring Session Redis保存会话，浏览器仍携带JSESSIONID，两个实例读取同一个Redis命名空间。没有使用粘性会话，也没有自行复制登录身份。

默认启动仍是8080和内存Session；只有启用`shared-session` profile才使用Redis会话。会话闲置有效期为1800秒，Cookie为HttpOnly、SameSite=Lax、路径`/`。同一套实例必须使用相同命名空间；不同环境应隔离命名空间。各实例使用相同版本的会话对象；当前使用框架默认JDK序列化。

Redis在该模式中属于登录和CSRF的必要依赖。不可用时请求会失败，不降级到各实例独立内存。命名空间默认`limited-trade:session:v1`，可用`SPRING_SESSION_REDIS_NAMESPACE`覆盖。MySQL继续处理库存与幂等，已有Redis限流继续共享预算。

## 新增与修改文件的作用

| 文件 | 作用 |
|---|---|
| `auth/SharedSessionConfiguration.java` | 按profile启用Redis HttpSession，并保持JSESSIONID Cookie约定 |
| `application-shared-session.yml` | 提供共享会话命名空间 |
| `application.yml`、`pom.xml` | 添加Boot管理的Spring Session Redis 3.5.7依赖；排除默认Session自动配置，由显式profile配置启用 |
| `deploy/nginx-cluster/nginx.conf` | 18081入口轮询8081/8082，独立PID、日志和运行目录 |
| `deploy/nginx/http-common.conf` | 单/双实例共用追踪编号、路由分类与JSON访问日志；upstream记录实际后端地址 |
| `deploy/nginx/server-common.conf` | 共用转发头、超时、健康入口和禁止代理自动重试的规则 |
| `deploy/nginx/nginx.conf` | 保留18080→8080，改为引用公共规则 |
| `scripts/start-nginx.ps1`、`stop-nginx.ps1` | 增加single/cluster模式，默认single，分别管理两套Nginx |
| `auth/SharedSessionIntegrationTest.java` | 真实Redis、两个独立Web上下文，验证跨实例认证与会话行为 |

## 如何运行真实双实例

先保持原MySQL、Redis、RabbitMQ运行，在项目根目录使用两个独立的PowerShell 7终端。这里给出步骤，本阶段没有自动启动这两套Java进程。

终端A：

```powershell
$env:APP_INSTANCE_ID = 'node-a'
$env:APP_LOG_FILE = 'logs/node-a.log'
java -jar .\target\limited-trade-platform-0.0.1-SNAPSHOT.jar --spring.profiles.active=shared-session --server.address=127.0.0.1 --server.port=8081
```

终端B：

```powershell
$env:APP_INSTANCE_ID = 'node-b'
$env:APP_LOG_FILE = 'logs/node-b.log'
java -jar .\target\limited-trade-platform-0.0.1-SNAPSHOT.jar --spring.profiles.active=shared-session --server.address=127.0.0.1 --server.port=8082
```

使用PowerShell 7启动入口：

```powershell
pwsh -NoProfile -File .\scripts\start-nginx.ps1 -Mode cluster
# 浏览器访问 http://127.0.0.1:18081/
pwsh -NoProfile -File .\scripts\stop-nginx.ps1 -Mode cluster
```

不传Mode时仍管理单实例入口18080。停止cluster Nginx不停止Java进程；Java在各自终端用Ctrl+C退出。UTF-8无BOM脚本按PowerShell 7执行；Windows PowerShell 5.1的默认编码会导致中文脚本解析失败。

共享会话不要求相同的业务请求落在相同实例。访问日志中的upstream用于核实实际分发，实例JSON日志中的instance用于区分服务端。会话Cookie和CSRF令牌不写入这些日志。

## 本次最小验证与证据边界

2026-10-20验证结果：

1. `SharedSessionIntegrationTest`两项通过：A获取CSRF、B登录、轮换Session ID、旧会话失效、两边身份和普通用户权限、旧CSRF拒绝、跨实例退出、Redis TTL以及共享限流预算。用户Mapper使用测试替身，Redis和Spring Security/Session链路真实运行；没有启动两个HTTP监听端口。
2. 原`SecurityWorkflowTest#databaseLoginPersistsIdentityWithoutExposingPassword`一项通过：真实测试MySQL和MockMvc确认默认内存Session登录行为保留。
3. 两套Nginx实际`-t`通过，两个PowerShell脚本解析通过。cluster启停通过；两个临时HTTP探针的6次请求各分到3次，唯一追踪响应头保留。停止一个探针后4次请求中1次502、其余3次到存活探针，测试后入口和探针已停止。这证明Nginx分发与被动排除行为，不代替真实双Java业务验收。
4. 跳过测试打包成功，可运行JAR包含新配置和Spring Session依赖。未做全量回归，没有Docker安装或部署，没有提交推送。

第一次重试时Windows短暂可连Redis，但Maven访问时已拒绝连接；WSL内Redis PING正常。保持一次有界WSL会话期间，两项测试通过。后续真实运行需保持WSL/Redis可用；本阶段没有修改Redis监听或系统网络。

`proxy_next_upstream off`不自动重放写请求。默认轮询加被动失败排除并不保证实例故障时零失败；本次探针实测首次失败即502。真实两Java进程经Nginx的登录/业务操作、实例停止后的完整表现，以及Redis故障演练，仍待专项验收。按最新澄清，只有Docker部署延后，原生运行可以继续推进。HTTPS未做；集中日志最新进度见[第二十六阶段](26-centralized-logging.md)。

后续更新：第二十七阶段已补齐真实双Java检查模式的共享会话、只读订单、故障恢复和实际日志关联；历史阶段的受控样本/探针证据不作替换，最新范围见[原生双实例记录](27-native-cluster.md)。写交易与MQ故障仍待专项。
