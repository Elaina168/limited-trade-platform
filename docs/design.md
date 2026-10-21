# Spring Cloud独立实验设计

2026-10-21。按用户已确认专项及“开始Spring Cloud”授权实施。SQL Server已取消。

## 目标与边界

网关→订单预览→商品服务真实跨进程调用，注册发现、双实例分发、配置管理、超时熔断及恢复、网关分层限流均有运行证据。不创建订单、不扣库存、不连接交易库，不做前端/AI/Docker。全部仅监听127.0.0.1。

## 选型与工具

复用C:/Program Files/Java/jdk-21.0.10和../limited-trade-platform/tools/apache-maven-3.9.16；复用../limited-trade-platform/tools/maven-repository，Maven下载新增依赖，不另装工具。Spring Boot 3.5.16 + Spring Cloud 2025.0.3；Eureka、Config Server native、Gateway WebFlux、Spring Cloud LoadBalancer、RestClient、Resilience4j。使用独立目录，不创建worktree、不操作父目录Git，不提交推送、不启动子代理。

版本依据：https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions 及Maven Central 2025.0.3 BOM。2025.0.x对应Boot 3.5.x。

## 进程与接口

registry:18760，config-server:18761，product-a/b:18770/18771，preview:18780，gateway-a/b:18800/18801。预留端口现场未发现监听。商品服务只读GET /api/products/1，商品价格19.90；POST /api/previews接收productId、quantity(1..10)，返回单价、合计、商品实例及campaign配置。不接受外部价格。配置读取失败拒绝启动，修改campaign后重启预览服务生效，不声称热刷新。

网关总量及来源IP限流仅作用于POST /api/previews；Resilience4j RateLimiter采用固定周期、零等待，网关本机预算，两个网关额度独立，不声称集群限流。来源取TCP对端，忽略客户端X-Forwarded-For；共享IP可能共同受限。交易工程原有Redis用户额度不变。

商品调用600ms读取超时、300ms连接超时；不重试。Resilience4j计数窗4、最少4次、失败率50%、开启3秒、半开2次；失败明确返回错误，不伪造商品。400/404等输入问题不计入熔断失败。实验drill profile才提供故障切换接口，正常模式无注入入口。

网关生成X-Trace-Id并向下游传递，业务日志记录同一编号和实例；不是完整分布式追踪系统。Eureka快速心跳和关闭自我保护仅适用于本机演示，记录实际故障摘除窗口。

## 成功标准

金额计算正确，两个商品实例均实际收到请求；停止一个实例后记录失败与摘除时间，恢复重新收请求；配置修改后重启生效；慢调用有界失败、持续失败打开熔断、恢复重新成功；两类限流429且方法/路由不误伤，伪造来源头不能绕过；跨服务trace关联；脚本只管理本工程进程，验证后恢复配置并清理实验进程。保留JSON证据和日志，不扩展到交易项目回归。
