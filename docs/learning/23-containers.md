# 第二十三阶段：Docker与Compose（部署文件已准备，运行验收待完成）

用户2026-10-20最新安排：先学习原理和文件实现，真实安装、镜像下载、启动及部署验收统一延后到项目收尾。下面保留方案和操作说明供届时使用，当前不因缺少Docker停止后续学习，也不执行安装或下载。

## 当前证据与安装边界

2026-10-20盘点：Windows没有docker/podman命令或Docker Desktop，现有Ubuntu-22.04 WSL也未安装Docker与Compose。WSL使用systemd，可通过wsl指定root执行管理命令；普通WSL用户的sudo需要密码，不必将密码发到聊天。

现有Ubuntu软件包索引中的候选版本为docker.io 29.1.3-0ubuntu3~22.04.2、docker-compose-v2 2.40.3+ds1-0ubuntu1~22.04.1。计划安装在已有WSL中，镜像、容器和数据卷留在WSL文件系统；不安装Windows Docker Desktop。安装、依赖下载、镜像拉取与容器启动均须用户确认后执行。

已查询Docker官方镜像仓库的清单元数据，确认Temurin 21-jre-jammy、MySQL 8.0、Redis 7.4-alpine、RabbitMQ 4.3.6-management标签存在，并把当时返回的清单digest固定在文件中；没有下载镜像层。Redis容器版与本机已有6.0.16不同，容器运行后的缓存/限流需要做相关最小检查。

## 文件与部署结构

| 新文件 | 作用 |
|---|---|
| Dockerfile | 使用固定Temurin JRE镜像，将已构建JAR复制进镜像，以非root用户运行 |
| .dockerignore | 白名单构建上下文，仅允许Dockerfile、目标JAR、应用健康检查脚本 |
| .gitattributes | Linux脚本保持LF，避免Windows检出产生CRLF执行问题 |
| .env.example | 容器环境变量键名说明，留空真实凭据 |
| compose.yaml | 编排应用、MySQL、Redis和RabbitMQ，声明网络访问、健康检查和持久卷 |
| docker/app-healthcheck.sh | 使用Bash TCP连接检查/api/ping，无需额外安装curl |
| docker/mysql/01-schema.sh | 全新数据卷按顺序执行既有结构和演示脚本，任何SQL失败立即退出 |
| scripts/prepare-containers.ps1 | 生成独立随机凭据及容器专用外部配置，拒绝覆盖已有配置 |

应用仍读取/app/config/db.properties和rabbitmq.properties，宿主机config/container中的两个文件只读挂载；不修改当前开发配置。Redis主机通过SPRING_DATA_REDIS_HOST=redis覆盖，数据库和MQ分别使用服务名mysql、rabbitmq。容器内127.0.0.1只指当前容器自身，不能用来连接另一个服务。

应用入口为http://localhost:18080/，RabbitMQ管理入口为http://localhost:15673/，均只发布到宿主机回环地址。MySQL、Redis及AMQP不发布宿主机端口。Compose项目名limited-trade-containers，四个独立命名卷保存MySQL、Redis AOF、RabbitMQ和应用日志，不导入或替换现有开发库。

初始化顺序为schema.sql、002—013、seed.sql、demo_catalog.sql，使用既有文件，不复制另一套表结构。仅在新MySQL数据卷首次初始化时执行；容器重建不会重新建表、重置库存或延长活动。后续迁移需显式执行，不能靠重新启动应用更新已有库。

首次没有默认账号：可在页面注册普通用户。需要管理员时，由操作者在容器数据库显式提升自己注册的账号，退出后重新登录；不内置固定管理员密码或更改角色的公开接口。

## 配置准备与启动步骤

以下是准备好的使用步骤。当前只执行了配置生成与静态检查，未安装或启动容器。

PowerShell项目目录中，首次生成配置：

```powershell
cd D:\codex\Java\limited-trade-platform
.\scripts\prepare-containers.ps1
```

本机已执行，会生成被Git忽略的.env.docker及config/container。三种密码独立随机生成，不输出密码；重复执行拒绝覆盖，避免与已有数据卷密码失配。应用只挂载数据库普通账号和MQ配置，不挂载含数据库root密码的.env.docker。

Dockerfile复用最新target/limited-trade-platform-0.0.1-SNAPSHOT.jar。源码变化后先按该功能要求验证，再跳过重复测试打包：

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress '-DskipTests' package
```

用户确认安装并完成Docker/Compose准备后，在已有WSL里执行：

```bash
cd /mnt/d/codex/Java/limited-trade-platform
docker compose --env-file .env.docker config --quiet
docker compose --env-file .env.docker up --build -d --wait --wait-timeout 180
docker compose --env-file .env.docker ps
docker compose --env-file .env.docker logs --tail 50 app
```

使用config --quiet验证，避免输出展开后的凭据。依赖健康后才启动应用；应用健康检查仅检查HTTP存活，完整数据库/Redis/MQ业务仍需单独验证，不能把/api/ping成功称为全部依赖健康。

需要管理员时进入容器MySQL，-p交互输入本机.env.docker中的root密码：

```bash
docker compose --env-file .env.docker exec mysql mysql -uroot -p limited_trade_platform
```

将实际注册的用户名代入后执行，不能照抄占位用户名：

```sql
UPDATE app_user SET role='ADMIN' WHERE username='替换为本人注册的用户名';
```

停止可使用docker compose --env-file .env.docker stop；移除容器与网络使用down，默认保留命名卷。平常不加-v，移除数据卷属于单独的数据删除操作。若初始化中途失败，先查MySQL日志及卷内状态，不自动删卷或重复导入。

日志既输出到容器日志驱动，也写入app_logs卷。驱动使用local并限制每容器10m×3文件，应用文件仍沿用第二十二阶段滚动策略；两者是不同存储，不等于集中日志收集。

## 已完成与待完成的最小验证

已完成：PowerShell脚本解析及真实生成、重复生成拒绝且凭据未变、凭据与配置匹配、root密码未放进应用挂载配置、忽略规则、15个SQL文件顺序和存在性、LF检查、使用已有SnakeYAML解析Compose并确认绑定源存在。Bash脚本语法检查另列工具记录。

待用户确认安装后：使用真正Compose校验配置、构建镜像、四服务启动健康、注册登录与一次最小下单取消、Redis缓存/限流和RabbitMQ消息专项检查；保留一条标记数据后重建服务，验证命名卷持久化。按实际失败补关联验证，不跑全量后端回归。

静态解析不是Docker构建或容器运行证明。当前阶段不标记容器部署已完成，不自动提交推送，后续源码教学只讲后端/配置，暂不讲前端。
