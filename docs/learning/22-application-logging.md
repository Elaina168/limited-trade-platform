# 第二十二阶段：应用日志与请求追踪

## 本次交付与文件

复用Spring Boot 3.5.16自带SLF4J、Logback和Logstash JSON输出，无新依赖、迁移或日志平台安装。

| 文件 | 作用 |
|---|---|
| observability/RequestLoggingFilter.java | 在Security前生成API追踪编号，返回X-Trace-Id，记录请求结果、耗时与未处理异常，结束后恢复MDC |
| order/OrderService.java | 六种真实订单状态转换在事务提交后记录事件，幂等返回和回滚不产生成交事件 |
| src/main/resources/application.yml | JSON格式、实例名、文件位置、滚动压缩和保留策略 |
| static/assets/api.js | ApiError读取追踪响应头，并在页面报错时展示编号 |
| ApplicationLoggingIntegrationTest.java | 4项请求、安全拒绝、异常、实际日志文件与事务事件测试 |
| src/test/frontend/storefront.cjs | 新增trace场景，复用retry场景检查原有重试行为 |

生产Java文件位于src/main/java/com/elaina/limitedtrade，静态文件位于src/main/resources，测试Java文件位于src/test/java/com/elaina/limitedtrade/system。

链路为浏览器→日志Filter→Security→Controller/Service→事务提交及订单日志→接口完成日志→前端错误编号。401/403也经过日志Filter，静态资源不产生日常接口日志。

## 编号与事件

- traceId：服务器为每次API请求生成32位十六进制随机编号，忽略客户端传入的编号。通过MDC进入当前线程日志，通过X-Trace-Id响应头返回；结束时恢复旧值，不清除其他上下文。
- 下单requestId：继续用于一次购买意图的幂等重试；同一购买的多次请求可以有不同traceId。
- orderId：关联同一订单的创建、支付、取消、关闭、发货和完成事件。后台任务按orderId关联，不伪造原请求traceId；MDC不会自动跨线程或MQ传播。

接口event=http_request，字段包括method、route、status、durationMs。2xx/3xx为INFO，4xx为WARN，5xx或未处理异常为ERROR。route使用MVC模板，例如/api/orders/{id}；Security提前结束时，仅登录/退出保留固定路径，其余为UNMAPPED，不写原始URL和查询参数。

未处理异常保留根异常类型和代码栈位置，继续向外抛出，沿用框架错误处理。自定义事件不输出异常message或完整Throwable，避免SQL值、用户输入随异常消息入日志；不记录请求体、密码、令牌、Cookie或地址正文。依赖框架有各自日志行为，这些字段选择不代表完成全系统敏感信息审计；不要随意开启请求体、SQL参数或认证DEBUG日志。

订单事件为order_created、order_paid、order_canceled、order_closed、order_shipped、order_completed，记录事件和orderId。TransactionSynchronization.afterCommit在真实提交后执行，优先于缓存失效等提交后回调；回滚不记录成功，其他回调失败也能保留已成交记录。日志不代替数据库状态或持久审计账本，进程崩溃仍可能造成记录不完整。

## 文件与查找

默认文件logs/limited-trade-platform.log，控制台和文件均为每行一条Logstash JSON。APP_LOG_FILE可覆盖位置，APP_INSTANCE_ID可设置实例名，默认local。文件和归档被Git忽略。

按日期和大小滚动，单文件10MB阈值；归档以日期和序号命名并.gz压缩，保留7天，归档总量上限100MB，启动时清理历史。归档上限不含活动文件，实际由Logback策略执行。

启动最新JAR后，在项目目录另一窗口查找页面错误编号：

```powershell
Get-Content -Encoding UTF8 .\logs\limited-trade-platform.log |
    ForEach-Object { $_ | ConvertFrom-Json } |
    Where-Object { $_.traceId -eq '替换为页面显示的追踪编号' }
```

再按orderId关联同一订单的其他事件。无服务器响应的网络失败没有追踪编号，原网络提示与下单原请求重试行为不变。应用日志侧重接口和内部业务，后续Nginx访问日志侧重入口转发、上游状态和入口耗时。

## 最小验证与边界

仅执行以下定向后端测试，4项通过：

```powershell
.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress '-Dtest=ApplicationLoggingIntegrationTest' test
```

使用真实测试MySQL和MockMvc验证200/401/403/404追踪、路由模板、MDC恢复、未处理异常继续抛出且保留安全栈、文件实际JSON记录、滚动策略已装配、六种订单事件、幂等、真实提交/回滚，以及其他提交后回调失败仍保留成交记录。未实际等待7天观察归档清理。

浏览器仅跑trace和retry两个关联场景，使用已有Chromium和接口契约夹具，均通过。缺少前端编号、提交后回调导致日志丢失均先复现再修复。后端结果见tools/application-logging-green.log，文件记录位于target/test-logs/application-logging.log；夹具在测试后删除，没有全量回归。

没有启动新HTTP服务或完成真实HTTP浏览器部署验收。集中采集检索、Nginx访问日志、分布式追踪和消息上下文传播仍待做，不因写入JSON文件就标为完成。不自动提交或推送；源码仍分小步讲解并等待“继续”。
