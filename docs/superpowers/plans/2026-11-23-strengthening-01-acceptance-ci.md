# 补强 1 · 可移植自动验收与 CI 基础

> 使用 superpowers:executing-plans 在现有检出实施本阶段。保留现有计划改动，提交和推送由用户另行授权。

**目标**：从干净检出运行可复现的前后端回归，能用隔离夹具自动跑通核心浏览器路径，并通过退出码定位失败。

**设计**：GitHub Actions 分别运行 Maven `ci` profile 和前端锁文件安装、测试、类型检查与构建。Maven profile 只选取已复核的无外部服务用例。浏览器入口复用构建产物，启动绑定回环地址的临时 MySQL、Java 与 Vite preview，使用唯一工作目录。Redis、RabbitMQ 和真实模型沿用既有专项入口，分别执行。

**依据**：[当前补强范围](../../plans/2026-11-23-strengthening-scope.md)及[总体计划](2026-11-23-project-strengthening.md)。

## 实施步骤

1. 在 `pom.xml` 新增确定性回归 profile，确认 `mvn -Pci verify` 执行的测试清单和退出码。在 `.github/workflows/ci.yml` 配置后端、前端独立作业，使用 JDK 21、Node 24 和 `npm ci`。
2. 在 `frontend` 锁定 Playwright Core。浏览器流程从现有代码与 Vue 七阶段记录提取，覆盖游客访问、注册与登录、选购及购物车提交、未知结果用原购买意图确认、管理员发货、买家收货及权限边界。
3. 在浏览器入口内准备独占 MySQL 目录和配置，应用 schema 及 002–014。集中记录创建的进程和目录，在正常及断言失败时回收。默认关闭 Agent 和 Redis/MQ 相关开关。程序路径和端口可配置。
4. 文档给出干净检出运行命令、资源边界、Redis/MQ 专项和真实模型入口。使用独立临时源树执行确定性检查与浏览器验收，分别注入回归失败和浏览器断言失败，核对退出码及清理结果。GitHub 托管证据留待获授权推送后验证。
