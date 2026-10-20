# 第二十四阶段：Windows Nginx统一入口与反向代理

## 本次交付与授权

用户2026-10-20明确允许直接下载安装并运行Nginx，涉及Docker再询问；因此本阶段实际安装、启动和HTTP验证，Docker仍延后收尾，没有安装或拉取任何Docker内容。

从https://nginx.org/en/download.html核对当时的Stable version为1.30.6，从https://nginx.org/download/nginx-1.30.6.zip下载官方Windows便携包，ZIP CRC检查通过，nginx -v确认nginx/1.30.6。便携目录为tools/nginx-1.30.6，未修改系统PATH或注册系统服务。下载包SHA256为c6ee35edb0a861063323f709192ae50d1eefaf2ea7d00678bd2b22d8542dc61e；该值是本机下载后计算的记录，不是独立签名认证。

## 新增文件

| 文件 | 作用 |
|---|---|
| deploy/nginx/nginx.conf | 独立主配置，代理本机Java、控制转发头/超时，输出访问日志和追踪编号 |
| scripts/start-nginx.ps1 | 创建运行目录、先执行nginx -t再隐藏窗口启动，拒绝重复启动 |
| scripts/stop-nginx.ps1 | 按本项目prefix和PID文件发送quit，优雅停止本项目Nginx |
| scripts/verify-nginx.py | 标准库HTTP最小验证，复用本机普通账号，仅登录/读取/退出，不新增订单或修改业务记录 |

修改.gitignore忽略Nginx临时目录；日志和PID位于已忽略的logs目录下，安装包与程序在已忽略的tools目录下。没有修改后端业务规则、数据库结构或前端代码。

## 请求链与启动方式

浏览器→127.0.0.1:18080的Nginx→127.0.0.1:8080的Java→原有MySQL/Redis/RabbitMQ。Nginx与Java在同一Windows主机直接运行；不需要Docker、容器网络或新一套数据库。

在项目根目录、按原方式准备好依赖后，先运行Java：

```powershell
java -jar .\target\limited-trade-platform-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1
```

另一个项目目录窗口启动Nginx：

```powershell
.\scripts\start-nginx.ps1
```

访问http://127.0.0.1:18080/。停止入口使用：

```powershell
.\scripts\stop-nginx.ps1
```

只停止Nginx不会停止Java、MySQL或消息队列。当前Java由用户窗口启动和管理；不要使用全局按进程名停止Java或Nginx。若非空PID文件残留，先确认所属进程再处理，启动脚本不自动覆盖。

修改配置后可先检查再重载：

```powershell
.\tools\nginx-1.30.6\nginx.exe -p "$($PWD.Path.Replace('\','/'))/deploy/nginx/" -c nginx.conf -t
# 只有检查成功后才执行；不要直接忽略-t失败。
.\tools\nginx-1.30.6\nginx.exe -p "$($PWD.Path.Replace('\','/'))/deploy/nginx/" -c nginx.conf -s reload
```

prefix决定nginx.conf、logs和temp等相对路径。Windows便携版在-p目录下需要temp父目录，且nginx -t可能留下空PID文件；启动脚本创建父目录并区分空PID与真实运行实例，这两点在实际启动中定位并修复。

## 配置实现

- upstream trade_backend只有127.0.0.1:8080一个后端，keepalive 16用于复用空闲上游连接。本阶段没有多实例负载均衡或共享Session。
- location /使用不附加URI的proxy_pass，页面、静态资源和API都保留请求路径与参数，继续由Spring Boot处理。/nginx-health仅检查Nginx自身，不能代表后端健康。
- Host保留入口主机和端口，设置X-Real-IP、X-Forwarded-Host/Port/Proto。X-Forwarded-For以实际连接地址覆盖，避免直接沿用客户端伪造的转发链。当前未为Spring开启任意转发头全局信任，正式HTTPS/多级代理需单独确定可信代理范围。
- Cookie、Set-Cookie和CSRF请求头按代理默认行为传递；不做接口缓存或放宽Security，不改变401/403/409等业务响应。proxy_intercept_errors off保留后端错误处理，proxy_next_upstream off禁止由代理自行重试请求。
- proxy_connect_timeout为3秒，send为15秒，read为30秒；send/read主要约束相邻读写操作的等待，不是整个业务的总时限。请求正文上限1m，未实现大文件上传或AI流式端点。

## 编号与日志

每个入口请求都有Nginx的$request_id，记为edgeTraceId。有Java响应且带X-Trace-Id时，入口日志traceId和最终响应头沿用该值，便于查询第二十二阶段应用日志；后端不可用等Nginx自身错误则使用入口编号。proxy_hide_header先隐藏原响应头，再由add_header统一输出一次，always确保错误状态也带编号。

访问日志deploy/nginx/logs/access.log为JSON，包含固定routeGroup分类、method、status、upstreamStatus、入口耗时和上游耗时等。没有记录原始路径、查询字符串、请求体、Cookie、Authorization或认证密码。traceId可以关联具体后端路由，无需让入口日志保留任意用户URL。

原生错误日志位于deploy/nginx/logs/error.log，保留诊断信息；Nginx错误日志可能包含原始请求行，因此不能将访问日志字段选择当作对所有日志的脱敏保证，也不应在URL里传密码或令牌。本阶段没有集中日志平台或Nginx日志自动切割，正式部署时再完成收集、轮转与保留策略。

## 本次最小实测

1. 官方便携包CRC、版本检查及实际nginx -t通过。
2. 实际启动、优雅停止、重启和重复启动拒绝通过。
3. Java未启动时，/nginx-health返回200，/api/ping返回502并携带入口追踪编号，访问日志中存在对应记录。
4. 用户启动后端并处理直接访问超时后，执行python scripts/verify-nginx.py通过：页面和JS文件转发、API响应、未登录401、缺CSRF的403、普通用户登录和Session、管理接口403、退出后401、唯一追踪头、入口与应用日志关联，以及访问日志不含测试查询标记和登录密码。

本阶段是真实Nginx加真实Java HTTP的最小联调，未使用浏览器UI自动化替代或夸大此证据。测试不新增用户、订单、地址或券，仅创建并退出一次认证会话；没有全量回归。

自动审批曾拒绝一次后台启动Java/RabbitMQ/WSL并联调的组合命令，返回blocked by policy，命令未执行且没有改写重试。用户随后自行启动并处理后端状态，再完成上述实测。工具线程检查曾收到操作系统拒绝访问，未据此推断具体阻塞根因或绕过权限。

Windows版用于本机学习和验证，不作为生产高可用或性能结论；公网、域名、HTTPS、多实例仍待后续阶段。当前18080与计划的Compose入口端口相同，收尾时需要选定最终部署拓扑，不能让两套服务同时占用该端口。不会自动提交推送，源码讲解继续分小步骤且暂不讲前端。
