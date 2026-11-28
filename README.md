# Spring Cloud Alibaba 独立商城实验

通过网关查询聚合商品详情或创建实验订单。商品库存、营销与订单服务各自负责自己的数据，库存服务运行两个实例。所有组件使用本地实验端口和独立的 `cloud_mall_lab` 库。

## 版本

JDK 21、Spring Boot 3.5.0、Spring Cloud 2025.0.0、Spring Cloud Alibaba 2025.0.0.0、Nacos 3.0.3、Sentinel 1.8.9。HTTP 服务间调用使用 OpenFeign。 [官方兼容矩阵](https://sca.aliyun.com/en/docs/2025.x/overview/version-explain/) · [Alibaba BOM](https://repo.maven.apache.org/maven2/com/alibaba/cloud/spring-cloud-alibaba-dependencies/2025.0.0.0/spring-cloud-alibaba-dependencies-2025.0.0.0.pom)

## 本地启动

系统使用 Windows、PowerShell 7 和 Python 3.11+。默认复用本机 MySQL 8.0、WSL Ubuntu-22.04 的 Redis，以及同级商城目录下已有的 Maven、Erlang 和 RabbitMQ。首次下载的 Nacos 便携包在 `runtime/` 下保存并核对官方 SHA-256。

```powershell
python scripts/prepare-nacos.py
pwsh -NoProfile -File scripts/build.ps1
pwsh -NoProfile -File scripts/lab.ps1 -Action Start
pwsh -NoProfile -File scripts/lab.ps1 -Action Status
```

读取详情或创建订单的入口为 `http://127.0.0.1:18800`。

- `GET /api/aggregate/products/1` 返回商品、活动和优惠券。加上 `?mode=serial` 可对照同样三次调用的串行耗时。
- `POST /api/orders` 接收 `requestId`、`productId`、`quantity` 和可选的 `couponId`。本地演示凭据为 `Authorization: Bearer lab-buyer-1`，网关将其解析为实验用户 1。请用唯一的 `requestId` 开始新购买。
- `GET /api/orders/{id}` 查询当前实验用户的订单，`POST /api/orders/{id}/cancel` 可取消待支付订单。实验的待支付默认期限为 4 秒。

脚本通过实验进程清单核对进程归属。使用 `-Node product-a` 可以只启停一个库存实例。常规停止保留该运行的实验数据，独立验收使用专用运行编号并清理临时数据。

```powershell
pwsh -NoProfile -File scripts/lab.ps1 -Action Stop
$env:PYTHONIOENCODING = 'utf-8'
python scripts/verify.py
```

自定义工具位置可设置 `LAB_JAVA_HOME`、`LAB_MYSQL_BIN`、`LAB_NACOS_HOME`、`LAB_WSL_DISTRIBUTION`、`LAB_RABBITMQ_HOME`、`LAB_ERLANG_HOME`。Maven 可通过 `LAB_MAVEN` 和 `LAB_MAVEN_REPO` 指定。

[架构与一致性设计](docs/design.md) · [实测结果](docs/results.md)
