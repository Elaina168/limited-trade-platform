# 补强 3 · Docker Compose 部署

日期：2026-11-23。设计和验收顺序见[补强 3 实施计划](../superpowers/plans/2026-11-23-strengthening-03-compose.md)。本记录接续[原容器准备](23-containers.md)和[Vue 静态托管](47-strengthening-vue-static.md)。

## 本机盘点

现有 Ubuntu-22.04 为 WSL2，PID 1 是 systemd。盘点时有 7.6 GiB 内存、约 6.7 GiB 可用，Windows C 盘约余 68 GiB。Windows 和 WSL 都未发现 Docker。Ubuntu 的 APT 索引候选为 Docker Engine 29.1.3、Compose 2.40.3 和 Buildx 0.30.1。模拟安装会新增以上三项及其 `containerd`、`runc` 依赖，未计划升级或移除其他软件包。当前 WSL 到 Docker Hub 的 TCP 连接超时。

安装与镜像拉取需先确认。拟使用现有 WSL 和 Ubuntu 软件包仓库，确认后核对实际安装版本，并验证固定 digest 的镜像可拉取。

## 五服务入口

| 服务 | 用途 | 健康检查覆盖范围 |
|---|---|---|
| web | Nginx 托管 Vue dist，代理同源 API 和 SSE | `/nginx-health`，只检查 Nginx |
| app | 非 root 用户运行 Spring Boot JAR | `/internal/ready`，检查应用就绪及数据库迁移兼容性 |
| mysql | 订单、账号、规格及 Agent 会话持久化 | 使用应用账户检查活动券表和两张 Agent 会话表可查询 |
| redis | 缓存和下单限流，AOF 数据保存在命名卷 | Redis `PING` |
| rabbitmq | 接收超时订单消息，保留有状态队列 | RabbitMQ 节点运行检查 |

应用在 MySQL、Redis、RabbitMQ 健康后启动。Nginx 在应用 HTTP 就绪后启动。应用拥有自己的日志卷，所有容器的 stdout/stderr 由限量 local 日志驱动管理。业务是否可用仍需单独走一笔真实订单。

Vue 容器复用 `deploy/nginx-vue/vue-http.conf`、`vue-server.conf` 与 `api-proxy.conf`。容器入口把 document root 指向镜像中的 dist，通过 Docker DNS 跟踪 `app:8080`。Windows 静态入口继续使用现有 18083 配置。

容器发布的默认端口为 `127.0.0.1:18084`（Vue + API）与 `127.0.0.1:15673`（RabbitMQ 管理 UI），可用 `CONTAINER_WEB_PORT` 和 `CONTAINER_RABBITMQ_PORT` 覆盖。Java、MySQL、Redis、AMQP 在项目内部网络通信。Compose 项目名用于区分网络、卷和本地构建的镜像标签。

Temurin、MySQL、Redis、RabbitMQ 延用既有 digest；Nginx 1.30.7 Alpine 也使用固定镜像索引。[Nginx 官方镜像清单](https://raw.githubusercontent.com/docker-library/repo-info/master/repos/nginx/remote/1.30.7-alpine.md)

两个镜像分别在构建阶段用 Maven 打包后端、用锁文件安装依赖并打包 Vue。白名单排除宿主机上的产物、依赖缓存和配置。运行镜像只接收构建产物和入口配置。

## 启动、查看和停止

在 Git 检出的根目录运行。首次构建需要 Docker 能访问镜像、Maven 和 npm 仓库。下列启动方式使用本机演示凭据，入口为 `http://127.0.0.1:18084`。

```bash
docker compose up -d --build
docker compose ps
docker compose logs --tail 50 web app
docker compose stop
```

已有卷继续用 `docker compose --env-file .env.docker up -d --build` 并沿用原密码。共享环境需要自有数据库和 RabbitMQ 凭据，可用 `scripts/prepare-containers.ps1` 生成新部署的环境文件。需要真实助手时，通过未跟踪的 `.env` 或运行环境提供 `DEEPSEEK_API_KEY`。提供有效密钥后自动启用助手，显式 `AGENT_ENABLED=false` 可关闭。

`stop` 保留容器，`down` 移除容器和网络并保留命名卷。用 `down` 后再次 `up` 可验证重建后的数据。查看 Compose 配置时使用 `config --quiet`，避免将插值后的认证信息写入日志。

全新 MySQL 卷依次执行 `schema.sql`、002–018、种子和演示脚本。扩展数据的临时列继承商品和分类名称的排序规则。首次导入若中断，初始化标记会阻止该卷随后被判为健康。已初始化卷独立管理迁移，缺后续迁移时需先核对和备份，再应用缺失脚本。按需使用 `--profile es` 与 `SEARCH_MODE=es` 启动 Elasticsearch。

## 独立验收与 CI

在 WSL 的仓库根目录，选择新的项目名和空闲端口：

```bash
CONTAINER_WEB_PORT=28084 CONTAINER_RABBITMQ_PORT=25673 \
  python3 scripts/verify-compose.py trade-check-local01
```

验证器使用默认演示配置，拒绝已有容器或数据卷的项目名。它核对五服务启动、扩展商品和券导入、交易履约、助手 SSE 及重建后数据。Redis 演练核对下单拒绝和恢复后的订单状态。脚本移除本轮容器与网络，保留命名卷供复查。

`.github/workflows/compose-integration.yml` 手动触发并调用同一个验收脚本。脚本注入本地模拟模型和测试密钥，默认确定性 CI 独立运行。

## 验收进度

2026-11-28 复验 `trade-check-fix4634247a` 退出码为 0。两套镜像从源码构建，五个服务均健康。全新 MySQL 卷导入 720 件扩展商品、120 个扩展活动和 240 种扩展优惠券。幂等下单、异步秒杀、支付发货收货、助手流式查单、启停及重建持久化、Redis 故障拒绝下单均通过。报告位于 `tools/compose-verification/trade-check-fix4634247a/summary.json`。

同日早期 WSL2（`Ubuntu-22.04`）Docker 安装及验收记录：

- **实际安装与镜像核验**：安装 `Docker Engine 29.1.3`（`29.1.3-0ubuntu3~22.04.2`）、`Docker Compose 2.40.3` 与 `Buildx 0.30.1`，拉取并核对 `eclipse-temurin:21-jre-jammy`、`nginx:1.30.7-alpine`、`mysql:8.0`、`redis:7.4-alpine`、`rabbitmq:4.3.6-management` 五个固定 digest 基础镜像。
- **全新空卷隔离验收（`trade-check-phase7`）**：使用 `pwsh -NoProfile -File scripts/prepare-containers.ps1 -Instance trade-check-phase7` 生成独立随机凭据，在 WSL2 执行 `CONTAINER_WEB_PORT=28084 CONTAINER_RABBITMQ_PORT=25673 python3 scripts/verify-compose.py trade-check-phase7`，退出码 `0`，留存证据于 `tools/compose-verification/trade-check-phase7/summary.json`：
  1. 五服务（`mysql`、`redis`、`rabbitmq`、`app`、`web`）全部按依赖顺序进入 `healthy`，`/api/admin/operations/release` 核验 `compatible = true` 且 18 项迁移（至 `018_product_search_sync`）完整生效；
  2. 通过统一网关 `127.0.0.1:28084` 跑通普通幂等下单 + RabbitMQ Outbox `SENT`、限量活动 Redis+MQ 异步秒杀（`202 QUEUED → CREATED`）、模拟支付、管理员发货与买家确认收货闭环；
  3. 跑通经 Nginx 无缓冲反代（`proxy_buffering off`）的交易助手 SSE 流式查单与可见历史入库；
  4. 执行 `stop/start` 及 `docker compose down` 后重新 `up -d --wait`（容器 ID 全部重建），核验买家账号、已完成/待支付订单、扣减后库存与交易助手会话历史 100% 保留；
  5. 执行 Redis 容器停止与恢复故障演练：停机期间 `/api/ping` 存活、新下单安全拒绝返回 `503` 且不扣库存，恢复后原请求幂等返回并成功取消回补库存。

## 学习顺序

先读 `compose.yaml` 的 `web → app → MySQL/Redis/RabbitMQ` 启动关系及命名卷，接着看两个 Dockerfile 的构建输入与运行时挂载。最后对照 `/api/orders` 的流程理解 Redis 限流、MySQL 落单及 Outbox、RabbitMQ 确认分别在哪一步生效。
