# OpenAPI与在线接口调试

日期：2026-10-21。

## 使用入口

按原方式启动或重启更新后的应用，再访问：

- 在线调试：[http://127.0.0.1:8080/api-docs.html](http://127.0.0.1:8080/api-docs.html)
- OpenAPI JSON：[http://127.0.0.1:8080/v3/api-docs](http://127.0.0.1:8080/v3/api-docs)

如果应用使用其他端口或通过代理访问，使用对应的同源地址。页面及Swagger UI资源由应用本地提供，不使用CDN或外部校验服务。

操作顺序：

1. 展开“认证”中的“登录”，点击`Try it out`。
2. 输入已有账号的用户名和密码，点击`Execute`；成功返回204，没有JSON正文。
3. 执行“查询当前登录身份”确认身份，再调试本人接口。管理接口要求ADMIN，普通用户访问返回403。
4. 写请求填入真实业务编号和正文后执行；请求示例中的占位值不能直接作为业务数据。
5. 使用“退出登录”结束会话，返回204；随后查询当前身份返回401。

浏览器自动携带Session Cookie，无需在`Authorize`中填写JWT或手工复制Cookie。该页面与商城共用浏览器会话，在这里登录、切换账号或退出也会影响商城。执行会调用真实业务；支付仍为原有模拟支付。

## 技术选择

现有Java 21、Spring Boot 3.5.16、Spring MVC、Spring Security继续使用。新增`springdoc-openapi-starter-webmvc-ui:2.8.17`，自动扫描Controller、请求DTO、返回DTO及校验注解；使用其自带Swagger UI资源。

兼容依据：springdoc官方[2.x文档](https://springdoc.org/v2/)的兼容表将Boot 3.5.x对应到springdoc 2.8.x；版本存在性和依赖结构核对了[Maven Central 2.8.17 POM](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/2.8.17/springdoc-openapi-starter-webmvc-ui-2.8.17.pom)。Knife4j原先是候选，本次采用springdoc配套Swagger UI，没有同时引入另一套界面。

## 文件与职责

| 文件 | 职责 |
|---|---|
| `pom.xml` | 固定springdoc版本并引入文档及本地UI资源 |
| `config/OpenApiConfiguration.java` | 文档元信息、Session/CSRF描述、权限与响应约定，显式补Security过滤器处理的登录和退出 |
| 15个现有Controller | 添加中文标签、接口用途及业务说明；未改变业务方法逻辑 |
| 注册、下单、结算、订单分页及商品状态DTO | 补密码、幂等标识、可选编号、分页含义及状态枚举说明 |
| `auth/SecurityConfig.java` | 仅放行GET文档及资源路径，保留现有业务权限和CSRF |
| `application.yml` | 文档只覆盖`/api/**`，默认响应介质为JSON，禁用Swagger默认示例地址 |
| `static/api-docs.html` | 本地在线调试页面 |
| `static/assets/api-docs.js` | 初始化Swagger UI，在每次写请求前获取当前CSRF Token |
| `OpenApiIntegrationTest.java` | 核对实际MVC路由覆盖、分页、模型、状态、认证与访问控制 |
| `OpenApiBrowserIntegrationTest.java`、`frontend/openapi.cjs` | 随机本机端口、隔离测试账号和商品，浏览器操作真实Swagger UI与后端 |

Java文件均位于`src/main/java/com/elaina/limitedtrade/`下；测试位于对应的`src/test/`目录。

## Session与CSRF如何配合

登录地址为`POST /api/auth/login`，正文是`application/x-www-form-urlencoded`中的`username`、`password`，不是JSON。退出地址为`POST /api/auth/logout`。这两个地址由Spring Security过滤器处理，因此在OpenAPI中显式声明，没有创建重复Controller。

所有写请求，包括匿名注册、登录及退出，都需要有效CSRF Token。`GET /api/auth/csrf`返回`headerName`、`parameterName`和`token`，客户端须保留同一会话的Cookie，并用返回的`headerName`传递Token。

在线入口的拦截器在每次POST/PUT/PATCH/DELETE前重新调用该接口，然后加上Token。登录和退出会轮换安全状态，因此不能一直使用登录前缓存的Token。Swagger加载文档时可能省略`method`，拦截器按Fetch默认GET处理。

没有关闭CSRF，没有增加JWT，没有为文档建立独立账号体系。OpenAPI中的安全声明只描述接口，实际认证和授权仍由Security执行。

## 响应约定

- 注册及普通新增资源201；单商品下单与结算首次201、相同请求幂等重试200。
- 修改购物车、删除地址、设置默认地址及登录退出等既有无正文操作204。
- 分页响应保留`page`、`size`、`total`、`items`，列表不再描述为数组。
- 400表示参数或正文校验失败，401表示未认证，403表示CSRF失败或权限不足；404、409按具体接口的既有业务约束描述。
- 下单429带`Retry-After`秒数，限流依赖不可用返回503。
- `/api/`响应含`X-Trace-Id`。Security错误可能无正文，MVC错误沿用Boot默认处理，不声明统一的`error`或`message`字段。

## 验证与边界

本次43项关联测试全部通过，0失败、0错误、0跳过：文档契约7项、真实浏览器1项、认证流程13项、本人订单分页22项。随后跳过重复测试重新打包，可运行JAR已更新；UTF-8与`git diff --check`检查通过。

先运行新增文档测试，确认旧实现缺少文档入口而失败，再接入实现。文档测试将实际MVC映射与生成的OpenAPI路径逐一比对，并补查过滤器登录、退出，防止遗漏。

真实浏览器验证使用`test`配置、随机127.0.0.1端口与独立测试账号、商品，完成匿名401、表单登录、Session身份、本人订单分页、普通用户管理接口403、自动CSRF购物车写入、缺CSRF仍403、退出后401、管理员重新登录和商品状态修改。最终删除本次创建的测试数据，未使用开发账号，也未连接开发库。断言没有页面脚本异常或外部资源请求；预期的401/403会产生浏览器HTTP错误提示。

设置`PLAYWRIGHT_MODULE`指向已有Playwright模块后，可使用现有Maven运行：

```text
mvn -B -ntp -Dtest=OpenApiIntegrationTest,OpenApiBrowserIntegrationTest,SecurityWorkflowTest,UserOrderPageApiTest test
```

未设置该环境变量时，真实浏览器测试会跳过，不自动安装浏览器依赖。此次没有执行全量后端回归、代理部署或公网验收，也没有执行真实订单写入。
