# Spring Cloud独立实践

本机学习工程，商品查询→订单预览，不创建订单、不扣库存、不连接交易数据库。SQL Server专项已取消；本工程对应2026-10-21开始的Spring Cloud专项。

## 组件与版本

- JDK 21.0.10、Maven 3.9.16：复用现有Windows工具。
- Spring Boot 3.5.16、Spring Cloud 2025.0.3：官方兼容表中2025.0.x对应Boot3.5.x。
- Eureka注册发现、Config Server native文件配置、Gateway WebFlux、Spring Cloud LoadBalancer、RestClient、Resilience4j。
- BOM解析版本：Netflix4.3.3、Config4.3.4、Gateway4.3.5、Cloud CircuitBreaker3.3.3；Resilience4j2.2.0。

版本依据：[官方兼容表](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)、[2025.0.3 BOM](https://repo.maven.apache.org/maven2/org/springframework/cloud/spring-cloud-dependencies/2025.0.3/spring-cloud-dependencies-2025.0.3.pom)。不沿用参考项目Boot2.7时代的版本和配置前缀。

## 结构与请求流程

| 模块 | 端口 | 职责 |
|---|---|---|
| registry | 18760 | Eureka，发现服务实例 |
| config-server | 18761 | 从config-repo读取非敏感配置 |
| product-service | 18770、18771 | 两个独立JVM，返回商品和实际实例名 |
| preview-service | 18780 | 以服务名调用商品，计算预览金额及执行熔断 |
| gateway | 18800、18801 | 两个独立入口，路由、请求编号与本机限流 |

所有进程仅监听127.0.0.1。配置中心与注册中心均为单节点，不属于高可用部署。

POST /api/previews → Gateway路由 → PreviewController → PreviewService → 带LoadBalancer的RestClient → ProductController。返回quantity、unitPrice、totalAmount、productInstance、campaign、traceId等信息；价格来自商品服务，不能由请求指定。

网关生成X-Trace-Id，预览服务传入商品服务，三个进程日志可按同一编号关联。这是请求关联演示，没有接入分布式追踪后端。GET /api/products/1可经网关查询商品，未知商品404。

## 构建与启动

在本工程根目录，用PowerShell7执行：

```powershell
pwsh -NoProfile -File scripts/build.ps1
pwsh -NoProfile -File scripts/lab.ps1 -Action Start
pwsh -NoProfile -File scripts/lab.ps1 -Action Status
```

构建复用`../limited-trade-platform/tools/apache-maven-3.9.16`及已有Maven缓存，首次解析会下载所需依赖。本工程不另装JDK、Maven、Nacos，不使用Docker。脚本使用现场确认的`C:/Program Files/Java/jdk-21.0.10/bin/java.exe`，迁移机器后应先重新盘点工具并调整路径。

注册有传播窗口：进程健康不等于其他服务已经发现该实例，刚启动时应等待注册和客户端同步。演示调用：

```powershell
$body=@{productId=1;quantity=3} | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:18800/api/previews -Method Post -ContentType application/json -Body $body
pwsh -NoProfile -File scripts/lab.ps1 -Action Stop
```

一次停止某节点可用`-Node product-a`，同名启动前必须先停止。启停脚本使用本工程manifest，核对JVM路径、完整JAR路径、创建时间及监听PID，不根据端口批量结束其他Java进程。日志和manifest位于被忽略的runtime/。启动失败会保留诊断与已启动节点，可确认后执行Stop清理。

## 配置与故障实验

配置文件为`config-repo/preview-service.properties`与`product-service.properties`。campaign从Config Server导入，修改后**重启preview生效**，没有热刷新或Bus。启动强依赖配置中心，不用optional导入掩盖配置读取失败。properties中的中文业务值使用Unicode转义，文件本身仍为UTF-8。

商品读取连接超时300ms、读取超时600ms，不自动重试。熔断计数窗4、最少4次、失败率50%、开启等待3秒、半开允许2次；网络异常与5xx计为失败，4xx不计入故障。已有4次成功时新增2次失败即达到50%，并非必须连续失败4次。错误明确返回502/503/504，没有虚构价格的fallback。

只有显式`-Drill`启动商品和预览进程时，才启用PUT /lab/mode和GET /lab/circuit。商品模式normal/slow/fail分别表示正常、延迟2秒、返回503；网关没有这些实验路由。默认Start不启用注入入口。

Eureka心跳1秒、租约5秒并关闭自我保护，仅为本机缩短观察时间，不能作为生产配置；停止进程后仍可能有注册缓存、失败请求和熔断等待，需要记录真实摘除窗口，不承诺零失败切换。

## 网关限流

仅限制预览路由的POST请求，默认每网关每秒总量8、每来源IP4，采用Resilience4j固定周期与零等待许可。过滤器依据Gateway已匹配的路由编号判断，矩阵参数等路径写法不能绕开同一业务额度。拒绝返回429、Retry-After及X-RateLimit-Scope。两类规则都按请求尝试消耗许可，不是成功订单计数；当前实验没有登录用户业务。

来源取TCP对端，不信任客户端X-Forwarded-For。共享公网IP会共享IP预算；以后接可信代理时须重新设计来源解析。两个网关预算各自独立，不是集群总额度。交易工程原有Redis用户级共享限流保留不动。

## 最小验证

先停止本工程进程，再执行：

```powershell
$env:PYTHONIOENCODING='utf-8'
python scripts/verify.py
```

验收自启七个JVM，验证正常链路、金额/中文名称、双实例分发、参数与404、跨服务日志关联、慢调用和熔断恢复、5xx传播、实例中断/恢复、配置重启生效、总量/IP限流及伪造头、方法/路由范围。限流阶段采用明确的实验阈值：gateway-a总量2/IP100、gateway-b总量100/IP2，周期60秒，以隔离两种拒绝来源并观察各网关独立预算；不是性能压测。

验收结束恢复campaign配置，停止本次进程；证据写入runtime/verification.json和相关日志。测试源码只覆盖本专项，不运行交易项目全量回归。

实现约定见[设计](docs/design.md)及[实施计划](docs/plans/2026-10-21-spring-cloud.md)。本次构建、6项单测与13组真实HTTP/清理检查通过，详见[运行记录](docs/results.md)。验收进程已停止，源码讲解另按用户节奏分段进行。
