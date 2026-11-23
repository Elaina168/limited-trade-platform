# Vue前端与Java后端本机运行

从干净检出运行确定性回归或隔离浏览器验收，见[补强 1 运行与学习记录](../docs/learning/46-strengthening-acceptance-ci.md)。

通过 Nginx 直接托管 Vue 构建产物和代理同源 API，见[补强 2 静态入口](../docs/learning/47-strengthening-vue-static.md)。下文保留开发和预览步骤。

从仓库根目录执行下面的PowerShell命令。此说明面向已准备好本机依赖的开发/演示环境；不是Docker或生产环境部署验收。

## 1. 已有环境与配置

本机核对版本：Node24.13.0、npm11.6.2、JDK21.0.10；Maven放在tools/apache-maven-3.9.16。Node由C:/nvm4w/nodejs链接提供。不要为了启动项目另装第二套Node。

```powershell
chcp 65001 > $null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Set-Location D:\codex\Java\limited-trade-platform
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:Path = $env:JAVA_HOME + '\bin;C:\nvm4w\nodejs;' + $env:Path
node.exe --version
npm.cmd --version
java.exe -version
```

config/db.properties、rabbitmq.properties、agent.properties存本机配置，均被Git忽略。不要把密钥写进前端、命令行参数或提交内容。新检出项目按同目录example创建配置；已有配置不要覆盖。

开发库limited_trade_platform已于2026-11-22执行014，两张Agent会话表已核对。新环境按根README初始化顺序执行schema和002—014；已迁移的库不要再次执行CREATE TABLE脚本。普通注册账号为USER；管理员角色需要由已有管理账号/数据库管理流程配置，页面不能自助提升权限。

## 2. 启动已有中间件

MySQL使用本机既有服务，确认状态即可，不另建实例：

```powershell
Get-Service MySQL
```

Redis使用既有WSL Ubuntu-22.04。另开一个终端进入并保持该会话：

```powershell
wsl -d Ubuntu-22.04
```

在Linux终端执行redis-cli ping，预期PONG。本机Redis随该发行版启动；若连接失败，先查看service redis-server status及端口，不要关闭Java的缓存/限流来掩盖中间件未启动。当前会话关闭后WSL可能退出，开发期间保留终端。

RabbitMQ使用tools中的既有安装。另开PowerShell，在仓库根目录前台运行：

```powershell
$env:ERLANG_HOME = (Resolve-Path 'tools/Erlang OTP').Path
$env:RABBITMQ_BASE = (Resolve-Path 'tools/rabbitmq-runtime').Path
$env:RABBITMQ_NODENAME = 'limited_trade@localhost'
$env:ERL_EPMD_ADDRESS = '127.0.0.1'
$env:RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS = '+S 2:2'
& .\tools\rabbitmq_server-4.3.6\sbin\rabbitmq-server.bat
```

预期出现Starting broker... completed。已有5672监听时先核对现有实例，不重复启动。此本机配置监听127.0.0.1:5672，管理页127.0.0.1:15672；账号与vhost由rabbitmq.properties配置，密码不写在这里。新机器须按根README单独准备Erlang/RabbitMQ、账号与vhost，tools内容不在Git中。

## 3. 构建与启动Java

```powershell
& .\tools\apache-maven-3.9.16\bin\mvn.cmd --batch-mode --no-transfer-progress -DskipTests package
& "$env:JAVA_HOME\bin\java.exe" -jar target/limited-trade-platform-0.0.1-SNAPSHOT.jar `
  --server.address=127.0.0.1 `
  --server.port=8080 `
  --app.agent.enabled=true `
  --app.agent.rag.enabled=true
```

-DskipTests只构建，不代表Java测试通过。此命令使用开发库及默认交易缓存、限流、Outbox与消费/扫描配置。Agent启用参数只影响本次进程，本机文件enabled=false仍保留；不传两个启用参数时，以本机配置文件的值为准。

RAG启动前需准备config/agent.properties所指向的本地模型和索引。本机已有BGE模型；新环境按docs/learning/34-rule-rag.md和scripts/prepare-rag-model.py准备。模型、索引、密钥均不随前端构建发布。资料或模型变更时按RAG说明重建索引；不盲目复用其他模型的旧索引。

日志出现Tomcat started和Started LimitedTradeApplication后，再检查http://127.0.0.1:8080/api/products是否返回200。Agent还依赖有效DeepSeek密钥/余额/网络，基础商品接口成功不代表模型可用。

## 4. 启动Vue开发页面

另开PowerShell，在frontend目录执行：

```powershell
# 仅首次检出或锁文件发生变化且依赖尚未就绪时执行npm.cmd ci。
$env:BACKEND_URL = 'http://127.0.0.1:8080'
npm.cmd run dev
```

访问http://127.0.0.1:5173，浏览器的/api由Vite代理到Java。BACKEND_URL在启动Vite时读取，修改后需要重启Vite。不要直接打开dist/index.html，也不要混用localhost与127.0.0.1保存不同Cookie。

Vue入口由Vite提供，Java8080根路径仍是原来的原生页面；本阶段没有把Vue产物嵌进JAR。前端不直接请求DeepSeek，Session与CSRF通过同源代理往返。

## 5. 检查构建产物

在frontend目录执行：

```powershell
npm.cmd test
npm.cmd run build
$env:BACKEND_URL = 'http://127.0.0.1:8080'
npm.cmd run preview -- --port 4173 --strictPort
```

访问http://127.0.0.1:4173，直接打开/products、/orders、/admin/orders和/agent也应进入SPA并执行身份保护。preview用于本机检查dist，不作为生产服务部署方案。正式静态托管需要SPA回退、同源/api代理与SSE禁用缓冲；现有deploy/nginx配置代理的是Java原生页面，并未切换为Vue静态站点。

本轮实际验证：构建预览4173→Java18739→测试库，开发页面5173→Java18740→开发库。两端均连接真实Redis/RabbitMQ；临时测试实例使用独立缓存键和MQ前缀，批量订单验收实例关闭用户限流，开发库实例保留限流。没有将测试端口写入产品源码。

## 6. 推荐演示顺序

1. 游客浏览商品，分类/名称筛选，再注册并登录。
2. 买家维护地址、购物车；参加活动领券，下单后查看实际成交价及地址快照。
3. 买家模拟支付；管理员在管理订单中登记物流；买家确认收货。
4. 另下一笔订单演示取消。网络未知结果先刷新/使用原购买意图确认，不能改参数重复购买。
5. 交易助手询问本人订单及支付期限，展开真实规则来源，再追问、清空历史或新建会话。
6. 退出后访问订单或管理页，展示身份/角色保护。

模拟支付不接入真实资金，物流为人工登记，助手只读。Agent刷新只恢复会话编号，不重新加载旧正文；历史读取和会话列表尚未实现。回答以纯文本显示，Markdown符号不会渲染。

## 7. 常见运行反馈

| 现象 | 核对顺序 |
|---|---|
| 页面无法连接服务 | Java是否启动、BACKEND_URL是否正确、重启Vite后是否生效 |
| 商品或下单报Redis连接错误 | 保持WSL会话，核对redis-cli ping与6379；不要清空Redis |
| MQ连接失败/Outbox待发送 | 核对5672、账号、vhost及Java日志；不要删除业务队列或手工改订单状态 |
| Agent创建接口404 | 核对Java本次进程是否启用app.agent.enabled |
| 会话接口报缺表 | 确认Java连接的库及014执行状态，不能只检查另一个库 |
| Agent超时/上游错误 | 核对DeepSeek配置和网络；页面结果未确认时不要自动重发 |
| 浏览器控制台401 | 匿名/退出后/api/auth/me的401是身份判定，需结合具体路径区分真正异常 |
| 新账号不能进入管理中心 | 注册默认USER，前后端均限制ADMIN，不是登录失效 |

## 8. 结束本机运行

先停止Vue与Java终端，再按需停止本次启动的RabbitMQ与WSL保持终端。不要终止其他项目的进程，不删除业务库/数据卷，不执行FLUSHDB。本阶段临时验收样本已另行清理，未保留可登录的演示账号或修改原有账号。
