# Spring Cloud独立实验实施计划

> 执行方式：使用superpowers:executing-plans，在当前会话自行实施；用户要求不启动子代理、不自行提交推送。

**Goal:** 实现并实测独立Spring Cloud服务调用及故障处理实验。
**Architecture:** Eureka注册中心、native Config Server、双商品服务、预览服务和双Gateway；使用RestClient/LoadBalancer及Resilience4j。
**Tech Stack:** Java21、Maven3.9.16、Boot3.5.16、Cloud2025.0.3。
**Spec:** ../design.md

## 全局约束

沿用设计中的端口、接口、故障和限流参数；仅本机，不读交易凭据或数据；新增文本UTF-8、中文注释。复用工具和缓存，不Docker、不提交推送。各步骤只做必要验证。

## 检查重点

服务发现的缓存与故障摘除窗口；配置重启而非热刷新；网络失败不能产生成功金额；限流方法/路径及伪造IP头；PID必须属于本工程才能停止。

## 任务

- [x] 1. 建立独立Maven模块及预览金额/下游错误测试，先确认RED，再实现最小服务逻辑。
  文件：pom.xml、registry/、config-server/、product-service/、preview-service/、gateway/。
  契约：GET /api/products/1与POST /api/previews遵循设计，配置文件位于config-repo/。
- [x] 2. 接入注册发现、配置导入、调用超时/熔断和网关限流，完成单项测试与打包。
  验证：mvn test package，仅独立工程测试，不运行交易工程回归。
- [x] 3. 编写scripts/lab.ps1启停和scripts/verify.py真实进程验收，记录正常、故障、限流、配置、trace证据。
  验证：python scripts/verify.py；输出runtime/verification.json，实验后仅停止自身进程并恢复配置。
- [x] 4. 自行复核差异、完善README与源码流程、同步交易路线状态，检查脚本语法及Git差异。

## 执行记录

- 2026-10-21：已核对仓库、工具、版本兼容和空闲端口；开始实施。
- 取舍：独立新目录本身隔离交易工程，无需worktree；按用户约定自行实现和复核，不创建子代理，不执行技能默认提交步骤。
- 选型取舍：RestClient配合LoadBalancer完成服务名调用，使用Resilience4j明确错误和状态；没有同时增加OpenFeign或Sentinel。配置采用重启生效，没有动态刷新。
- 实施发现：修正PowerShell就绪超时捕获、进程创建时间比较及Windows继承管道句柄导致验收等待的问题；停止操作等待真实进程退出，不吞超时。
- 验收预期修正：已有成功样本的4项滚动窗新增2次失败即可触发50%阈值，不把“最少4次采样”误当作“至少连续4次失败”。
- 差异复核：新增矩阵参数绕过同一业务限流的RED用例，改为根据Gateway已匹配路由编号限流；随后构建及6项单测通过，关联真实HTTP验证通过。

- 最终结果：构建成功，6项单测和13组真实HTTP/清理检查通过；campaign恢复，实验端口释放。详见../results.md。未提交推送。
