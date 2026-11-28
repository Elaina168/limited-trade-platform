# 补强 3 · Docker Compose 部署

日期：2026-11-23。设计和验收顺序见[补强 3 实施计划](../superpowers/plans/2026-11-23-strengthening-03-compose.md)。本记录接续[原容器准备](23-containers.md)和[Vue 静态托管](47-strengthening-vue-static.md)。

## 本机盘点

现有 Ubuntu-22.04 为 WSL2，PID 1 是 systemd。盘点时有 7.6 GiB 内存、约 6.7 GiB 可用，Windows C 盘约余 68 GiB。Windows 和 WSL 都未发现 Docker。Ubuntu 的 APT 索引候选为 Docker Engine 29.1.3、Compose 2.40.3 和 Buildx 0.30.1。模拟安装会新增以上三项及其 `containerd`、`runc` 依赖，未计划升级或移除其他软件包。当前 WSL 到 Docker Hub 的 TCP 连接超时。

安装与镜像拉取需先确认。拟使用现有 WSL 和 Ubuntu 软件包仓库，确认后核对实际安装版本，并验证固定 digest 的镜像可拉取。

## 五服务入口

| 服务 | 用途 | 健康检查覆盖范围 |
|---|---|---|
| web | Nginx 托管 Vue dist，代理同源 API 和 SSE | `/nginx-health`，只检查 Nginx |
| app | 非 root 用户运行 Spring Boot JAR | `/api/ping`，检查 Java HTTP 响应 |
| mysql | 订单、账号、规格及 Agent 会话持久化 | 使用应用账户检查活动券表和两张 Agent 会话表可查询 |
| redis | 缓存和下单限流，AOF 数据保存在命名卷 | Redis `PING` |
| rabbitmq | 接收超时订单消息，保留有状态队列 | RabbitMQ 节点运行检查 |

应用在 MySQL、Redis、RabbitMQ 健康后启动。Nginx 在应用 HTTP 就绪后启动。应用拥有自己的日志卷，所有容器的 stdout/stderr 由限量 local 日志驱动管理。业务是否可用仍需单独走一笔真实订单。

Vue 容器复用 `deploy/nginx-vue/vue-http.conf`、`vue-server.conf` 与 `api-proxy.conf`。容器入口把 document root 指向镜像中的 dist，通过 Docker DNS 跟踪 `app:8080`。Windows 静态入口继续使用现有 18083 配置。

容器发布的默认端口为 `127.0.0.1:18084`（Vue + API）与 `127.0.0.1:15673`（RabbitMQ 管理 UI），可用 `CONTAINER_WEB_PORT` 和 `CONTAINER_RABBITMQ_PORT` 覆盖。Java、MySQL、Redis、AMQP 在项目内部网络通信。Compose 项目名用于区分网络、卷和本地构建的镜像标签。

Temurin、MySQL、Redis、RabbitMQ 延用既有 digest；Nginx 1.30.7 Alpine 也使用固定镜像索引。[Nginx 官方镜像清单](https://raw.githubusercontent.com/docker-library/repo-info/master/repos/nginx/remote/1.30.7-alpine.md)

两个镜像的上下文各用白名单。Java 镜像接收 JAR 和健康脚本，Nginx 镜像接收 dist 和公开的网关配置。数据库和 RabbitMQ 密码由宿主机的外置配置提供，应用只读挂载自己的两个 properties 文件。

## 启动、查看和停止

有 Docker 运行环境后，在 Windows 先构建 JAR 和 dist。根目录执行 `mvn -Pci verify`，在 `frontend` 执行 `npm ci` 和 `npm run build`。首次部署可用 `scripts/prepare-containers.ps1` 生成默认配置，已有默认配置则复用。下列命令在 WSL 的仓库根目录执行，使用具备 Docker 权限的账号。

```bash
docker compose --env-file .env.docker config --quiet
docker compose --env-file .env.docker up --build -d --wait --wait-timeout 240
docker compose --env-file .env.docker ps
docker compose --env-file .env.docker logs --tail 50 web app
docker compose --env-file .env.docker stop
```

`stop` 保留容器，`down` 移除容器和网络并保留命名卷。用 `down` 后再次 `up` 可验证重建后的数据。查看 Compose 配置时使用 `config --quiet`，避免将插值后的认证信息写入日志。

全新 MySQL 卷自动依次执行 `schema.sql`、002–018、种子和演示脚本；已有卷由 MySQL 入口跳过初始化。若旧卷缺后续迁移，先核对缺表及备份，再用相应卷的现有凭据进入 MySQL，对该容器库显式应用缺失的 `/bootstrap/0xx_*.sql`。`up --build` 只负责重建服务，已有库的迁移独立管理。需要容器内 Elasticsearch 检索时，可叠加 `--profile es` 与 `SEARCH_MODE=es` 启动可选单节点 ES 8.17.2 服务。

## 独立验收与 CI

在 Windows 根目录生成一次性项目配置：

```powershell
.\scripts\prepare-containers.ps1 -Instance trade-check-local01
```

在 WSL 的同一仓库根目录，选择空闲端口并运行：

```bash
CONTAINER_WEB_PORT=28084 CONTAINER_RABBITMQ_PORT=25673 \
  python3 scripts/verify-compose.py trade-check-local01
```

`-Instance` 将独立配置写入 `config/container/<名称>`，与已有默认配置各自保存。验证器在启动前拒绝已有容器或数据卷的项目名，然后检查五服务启动、Vue 页与同源 API、注册下单、幂等、Redis 缓存和 RabbitMQ 投递状态。停止与启动后重新登录核对订单，再移除容器并重建，核对新 MySQL 容器仍能读取旧订单和库存。Redis 故障演练检查应用探针仍能响应、订单写入被拒绝、恢复后能确认原订单和取消。脚本收尾保留本轮卷供复查。

`.github/workflows/compose-integration.yml` 在 GitHub 托管 Linux runner 上构建 JAR 和 dist，生成隔离项目，调用同一个验收脚本。工作流显式触发，默认确定性 CI 的入口继续独立。容器默认关闭 Agent，验收没有真实模型步骤。

## 验收进度

2026-11-28 完成本机 WSL2（`Ubuntu-22.04`）Docker 真实安装与全新空卷全链路验收：

- **实际安装与镜像核验**：安装 `Docker Engine 29.1.3`（`29.1.3-0ubuntu3~22.04.2`）、`Docker Compose 2.40.3` 与 `Buildx 0.30.1`，拉取并核对 `eclipse-temurin:21-jre-jammy`、`nginx:1.30.7-alpine`、`mysql:8.0`、`redis:7.4-alpine`、`rabbitmq:4.3.6-management` 五个固定 digest 基础镜像。
- **全新空卷隔离验收（`trade-check-phase7`）**：使用 `pwsh -NoProfile -File scripts/prepare-containers.ps1 -Instance trade-check-phase7` 生成独立随机凭据，在 WSL2 执行 `CONTAINER_WEB_PORT=28084 CONTAINER_RABBITMQ_PORT=25673 python3 scripts/verify-compose.py trade-check-phase7`，退出码 `0`，留存证据于 `tools/compose-verification/trade-check-phase7/summary.json`：
  1. 五服务（`mysql`、`redis`、`rabbitmq`、`app`、`web`）全部按依赖顺序进入 `healthy`，`/api/admin/operations/release` 核验 `compatible = true` 且 18 项迁移（至 `018_product_search_sync`）完整生效；
  2. 通过统一网关 `127.0.0.1:28084` 跑通普通幂等下单 + RabbitMQ Outbox `SENT`、限量活动 Redis+MQ 异步秒杀（`202 QUEUED → CREATED`）、模拟支付、管理员发货与买家确认收货闭环；
  3. 跑通经 Nginx 无缓冲反代（`proxy_buffering off`）的交易助手 SSE 流式查单与可见历史入库；
  4. 执行 `stop/start` 及 `docker compose down` 后重新 `up -d --wait`（容器 ID 全部重建），核验买家账号、已完成/待支付订单、扣减后库存与交易助手会话历史 100% 保留；
  5. 执行 Redis 容器停止与恢复故障演练：停机期间 `/api/ping` 存活、新下单安全拒绝返回 `503` 且不扣库存，恢复后原请求幂等返回并成功取消回补库存。

## 学习顺序

先读 `compose.yaml` 的 `web → app → MySQL/Redis/RabbitMQ` 启动关系及命名卷，接着看两个 Dockerfile 的构建输入与运行时挂载。最后对照 `/api/orders` 的流程理解 Redis 限流、MySQL 落单及 Outbox、RabbitMQ 确认分别在哪一步生效。
