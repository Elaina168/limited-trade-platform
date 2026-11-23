# 补强 2 · Vue 静态托管与统一入口

日期：2026-11-23。阶段设计见[实施计划](../superpowers/plans/2026-11-23-strengthening-02-vue-static.md)。

## Windows 运行

1. 按[本机运行说明](../../frontend/RUNNING.md)启动 Java 及所需中间件，默认 Java 端口为 8080。
2. 从仓库根目录构建 Vue，并以 `vue` 模式启动 Nginx。沿用现有 Windows 便携版 Nginx。新检出按 [Nginx 学习记录](24-nginx.md)准备程序。
3. 打开 `http://127.0.0.1:18083/`。结束时执行停止命令，Java 由其启动终端独立管理。

```powershell
Push-Location frontend
npm.cmd ci
if ($LASTEXITCODE -eq 0) { npm.cmd run build }
$buildExit = $LASTEXITCODE
Pop-Location
if ($buildExit -ne 0) { throw 'Vue 构建失败' }
.\scripts\start-nginx.ps1 -Mode vue
```

```powershell
.\scripts\stop-nginx.ps1 -Mode vue
```

| 本机入口 | 作用 |
|---|---|
| `127.0.0.1:18083` | Vue 静态站点及同源 Java API |
| `127.0.0.1:8080` | Java 原生页面及 API |
| `127.0.0.1:5173` | Vite 开发入口 |
| `127.0.0.1:18083/nginx-health` | Nginx 自身就绪检查 |

Nginx 各模式有独立的 PID、日志和临时文件目录。Vue 模式的路径都在 `deploy/nginx-vue`，启动脚本以该目录为 prefix。配置中 `listen` 和 `upstream trade_backend` 分别决定站点监听地址和后端地址。当前绑定回环地址，HTTPS 和实际环境的开放端口留到部署时配置。

## 请求如何处理

`nginx.conf` 按路径把请求送到各自的入口：

- 页面路由由 `try_files` 查找。直接访问或刷新 `/orders/1`、`/admin/orders/1`、`/agent` 时，Nginx 回退到 `index.html`，Vue Router 再决定进入页面、登录或显示权限提示。入口 HTML 使用 `no-cache`，浏览器复用前需重新验证。
- `/assets/` 只读取磁盘文件，缺失返回 404。带构建哈希的成功资源响应使用一年缓存和 `immutable`。新构建产生新资源 URL。
- `/api` 和 `/api/…` 进入 `api-proxy.conf`。请求方法、路径、查询参数、Cookie 和 CSRF 头继续交给 Java。网关不重试写请求；403、404 等由 Java 决定。网关对 API 响应统一加 `no-store`，登录状态、CSRF 和订单数据都走同一路径。
- SSE 路由有单独的规则。Nginx 关闭响应缓冲，允许更长的上游读取间隔，并在浏览器中止后关闭上游连接。Vue 的 `useAgentStore.stop()` 通过 `AbortController` 取消 fetch，后端的 `AgentSseService` 处理连接写入失败与生成资格释放。

请求日志沿用既有固定路由分类与 Trace ID，Vue 模式的日志在 `deploy/nginx-vue/logs`。

## 隔离验收

先准备补强 1 的 Java JAR、前端 dist、MySQL Server/客户端与浏览器。保持在 `frontend`，设置 `ACCEPTANCE_NGINX` 后执行同一个验收命令。Nginx 可执行文件的相对路径以启动验收的工作目录为准，建议使用 `Resolve-Path`。

```powershell
$env:ACCEPTANCE_NGINX = (Resolve-Path '../tools/nginx-1.30.6/nginx.exe').Path
npm.cmd run acceptance
Remove-Item Env:ACCEPTANCE_NGINX
```

该入口复用独立 MySQL、随机账号和交易场景。测试将 Nginx 配置与 dist 复制到本轮目录，并用 `ACCEPTANCE_WEB_PORT`、`ACCEPTANCE_JAVA_PORT` 替换其中两个监听参数。真实 Java Agent 只连接本地模型替身。第一轮用浏览器收到首段作为释放尾帧的条件，确认只在完成后保存；第二轮由浏览器点击停止，核对模型连接关闭、生成资格释放以及成功轮次仍为一条。默认的 Vite preview 验收可在取消 `ACCEPTANCE_NGINX` 后运行。

## 本轮证据与边界

- Windows Nginx 1.30.6 配置检查通过。直接执行 `-Mode vue` 启停，18083 的健康接口与 `/agent` 深链接实际响应。
- 在隔离端口 24183 → 28083、Chrome 158 和 MySQL 8.0.45 上联调。通过静态资源 MIME、ETag 304、缺失资源 404、HTML 重验证、API no-store、Java 返回的 403/404 和原生首页检查。买家/管理交易闭环、深链接刷新、登录退出、CSRF 和权限检查通过。Vite preview 验收也通过。
- 首次断开测试中，Tomcat 记录了客户端连接关闭，但 Spring JDK 连接器缓存的响应流仍占用模型连接。修复后，`TradeAgentService.chatTurnStream()` 让每轮流式对话独占一个 HTTP 客户端。该轮多次模型请求可以复用连接，退出时主动关闭。复测确认未完成轮没有保存、生成资格释放、模型替身的连接关闭。
- Vue 类型检查与构建通过，Java 确定性用例 63 项通过并完成 JAR 打包。验收进程退出、临时 MySQL 目录清理。证据范围为 Windows 本地和本地模型替身。真实 DeepSeek、HTTPS 和容器托管留待后续验收。

## 学习顺序

先把 `nginx.conf` 和 `api-proxy.conf` 放在一起读，按 `/orders/1`、`/assets/…`、`/api/orders/1`、聊天流四条路径理解 `try_files`、`$vue_cache_control`、`proxy_pass` 和 `proxy_buffering`。流式停止可继续对照 `AgentChatView.vue` 的按钮 → `useAgentStore.stop()` → 浏览器中止 → Nginx 关闭上游 → `AgentSseService` 取消执行。
