# 补强 1 · 可移植自动验收与 CI 基础

日期：2026-11-23。阶段设计见[实施计划](../superpowers/plans/2026-11-23-strengthening-01-acceptance-ci.md)。

## 从干净检出运行

准备 JDK 21、Maven 3.9 和 Node 24.13.0。本机沿用 Maven 3.9.16、npm 11.6.2。命令从仓库根目录执行，PowerShell 中可用 `mvn.cmd` 和 `npm.cmd`。首次构建下载 Maven 和 npm 依赖，Java 测试使用项目自己的 Maven 缓存。

```sh
mvn --batch-mode --no-transfer-progress -Pci verify
cd frontend
npm ci
npm test
npm run build
```

- Java 的 `ci` profile 用 Surefire 明确选择确定性用例，包括 Agent 协议、SSE 控制器契约、权限与消息处理。MVC 切片跳过本机配置导入。通过后 Maven 才打包 Spring Boot JAR。
- 前端先用锁文件安装，再执行 Node 测试。成功后 `build` 先用 `vue-tsc` 做类型检查，再用 Vite 生成 `dist`。
- `.github/workflows/ci.yml` 在 push、PR 和手动触发时分别运行两项作业。GitHub 将检查关联到该次运行的 SHA。工作流没有模型凭据或真实模型步骤，也不依赖 MySQL、Redis、RabbitMQ 或浏览器。

## 真实浏览器与 MySQL

完成上面的构建后，保持在 `frontend` 目录。机器需有支持 `--initialize-insecure` 的 MySQL Server 和配套客户端，本次使用 MySQL 8.0.45。以普通用户运行。已有 Chrome 或 Edge 时直接选择对应通道，也可执行 `npx --no-install playwright-core install chromium` 安装配套浏览器。

```powershell
# 程序已在 PATH 时可省略前两行。将示例路径替换为自己的安装位置。
$env:ACCEPTANCE_MYSQLD = '<MySQL 安装目录>/bin/mysqld.exe'
$env:ACCEPTANCE_MYSQL = '<MySQL 安装目录>/bin/mysql.exe'
$env:ACCEPTANCE_BROWSER_CHANNEL = 'chrome'
npm.cmd run acceptance
```

| 环境变量 | 默认值与用途 |
|---|---|
| `ACCEPTANCE_MYSQLD`、`ACCEPTANCE_MYSQL` | 从 PATH 查找 `mysqld` 和 `mysql`，可指定可执行文件路径 |
| `ACCEPTANCE_JAVA` | 优先使用 `JAVA_HOME` 中的 Java，否则从 PATH 查找。脚本根据 `java.home` 启动实际 JVM |
| `ACCEPTANCE_BROWSER_CHANNEL`、`ACCEPTANCE_BROWSER_EXECUTABLE` | 任选其一指定 `chrome`、`msedge` 或 Chromium 系浏览器路径。两项均省略时用 Playwright 配套 Chromium |
| `ACCEPTANCE_MYSQL_PORT`、`ACCEPTANCE_JAVA_PORT`、`ACCEPTANCE_WEB_PORT` | 分别为 13306、18080、14173。三者均绑定 127.0.0.1，端口占用直接失败 |

入口为 `frontend/acceptance/run.mjs`。它在 `target/browser-acceptance-*` 生成一次性运行目录、MySQL 数据、随机凭据和 Spring 本机配置占位文件。启动独立 MySQL 后应用 schema 与编号迁移，再启动 Java 和 Vite preview。Java 直连和 Vite 转发均须查询到该轮随机商品，浏览器才开始操作。以 Java 子进程参数关闭 Agent、缓存、限流、消息和调度。运行中临时库名为 `limited_trade_acceptance`，使用该轮启动的专用端口和账号密码。

`frontend/acceptance/trade.mjs` 按三段验证流程组织。买家和管理员使用独立浏览器会话。注册时两者的角色都为 USER，订单归属检查后才提升临时库中的管理员夹具。

1. 游客看商品、购物车跳转登录，UI 注册登录，刷新后身份仍有效。
2. 填地址、选购并加购物车、提交结算。首次请求在真实后端创建订单，脚本只丢弃浏览器收到的结果。刷新后以同一参数重试，核对 201 → 200、同一订单、成交金额 39.80 元、收货地址快照、单条订单和库存从 10 到 8。
3. 检查缺 CSRF 的支付请求返回 403、普通用户访问管理接口返回 403、其他用户读取订单返回 404，再完成模拟支付、管理员登记物流、买家确认收货和退出重定向。

脚本失败时输出具体断言和服务日志尾部，回收浏览器、Vite、JVM 和 MySQL，再移除该轮目录。成功返回 0，断言或清理失败返回非零。如果机器断电或进程被强制终止，先按输出的目录及其中的服务日志和 `mysql.pid` 核对归属，停止该次进程后只移除该目录。强制中断后的 PID 可能已被系统重新使用，需核对启动信息。

## 真实 Redis、RabbitMQ 与模型入口

浏览器默认套件使用真实 MySQL。下列专项使用各自的隔离环境，可按需要分别运行。

### Redis 和 RabbitMQ

另外准备一份专用测试检出及空白 MySQL 测试库，执行 `sql/schema.sql` 和 002–014 的编号迁移。测试 profile 默认指向 `limited_trade_platform_test`，可在专用终端通过 `SPRING_DATASOURCE_URL` 覆盖到独立库；不要沿用开发库。按 `config/*.properties.example` 在专用检出配置 MySQL 和 RabbitMQ 测试账号。测试 profile 使用 `mq.test-virtual-host`，指向本次创建的专用 vhost。用 `SPRING_DATA_REDIS_HOST`、`SPRING_DATA_REDIS_PORT` 指向独立 Redis 实例。

```powershell
mvn.cmd --batch-mode --no-transfer-progress '-Dtest=ProductCacheIntegrationTest,OrderRateLimiterIntegrationTest,OrderTimeoutMessagingIntegrationTest' test
```

缓存和限流用例使用随机键，消息用例使用随机拓扑前缀。运行完成核对该轮资源，仅回收本次创建的隔离库、Redis 实例及 vhost。服务化编排留到补强 3。

### 真实 DeepSeek 与 RAG

沿用上述隔离 MySQL 数据边界，按[Agent 评估记录](38-agent-evaluation.md)和[规则 RAG 配置](34-rule-rag.md)准备本机模型、索引及密钥。仅在明确批准调用并有额度时手动执行。下面示例选用现有问题集中的一个场景，实际模型请求次数取决于工具轮次。

```powershell
mvn.cmd --batch-mode --no-transfer-progress '-Dtest=AgentEvaluationLiveTest' '-Dagent.evaluation.live=true' '-Dagent.evaluation.scenarios=fresh-follow-up' test
```

## 本轮证据与边界

- 以 HEAD `2cc9f1c74661d604b9c068f9178beff107fa22b7` 的文件加本轮改动生成独立源树，未复制原检出的 `config`、`target` 或 `node_modules`。Maven 63 项通过并打包，锁文件安装成功，前端 42 项、类型检查和构建通过。
- 在独立源树注入 Maven 与 Node 测试断言失败，两条测试命令均返回 1，Maven 在打包前停止。临时断言随后撤回。
- Chrome 158、MySQL 8.0.45 分别用默认和自定义端口完成全链路。另把副本中的库存预期改为 9，实际库存为 8，浏览器命令返回 1。自定义端口两轮均核对临时目录消失且三组端口无监听。故意失败仅在副本执行。
- 本阶段核对了工作流 YAML 与执行入口，实际运行了对应本地命令、真实浏览器和临时 MySQL。GitHub 托管运行及真实 Redis、RabbitMQ、DeepSeek 专项本轮未执行。

## 学习顺序

一起看 `.github/workflows/ci.yml` 和 `pom.xml`。前者选择工具版本与执行目录，后者通过 `ci` profile 控制 Surefire 测试集合。再单独看 `run.mjs` 的 `fixture`、`childEnv` 和 `ready`，理解隔离数据、子进程配置与就绪检查如何串起来。业务调用可顺着 Vue 购物车结算 → `POST /api/orders/checkout` → `OrderController` 的 checkout() → OrderService.checkout() → 订单详情页面确认，同一购买意图贯穿首次提交和重试。SQL 与测试断言源码留作查阅。
