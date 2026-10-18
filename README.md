# 限量商品营销交易平台

从空 Spring Boot 工程开始的 Java 后端学习项目，用 Git 记录需求分析、表设计、编码、验证和阶段复盘。

## 当前状态

2026-10-18：已实现商品维护、一级商品分类与分页筛选、用户购物车、用户收货地址、Session 登录与角色权限、创建订单与本人订单查询、模拟支付、主动取消、定时超时关闭、管理员订单分页筛选、最小限时限量活动、Redis 商品列表缓存，以及 Outbox + RabbitMQ 可靠超时消息。购物车只保存购买意向；创建订单才扣商品库存，活动订单还扣活动名额；取消或关闭只归还一次。尚未实现注册功能。

本次学习停在用户收货地址及代码讲解。地址簿支持本人新增、列表、完整修改、删除和设置默认；所有写入先锁用户行，保持每用户最多一个默认项。完整新增代码与调用链见 [第十四阶段学习记录](docs/learning/14-address.md)。本阶段验收后等待“继续”再推进。

## 学习方式

目标 → 必要原理 → 文件作用与代码展示 → 学习者阅读、提问和实践 → 检查验证 → 集中复述。

助手先提供当前阶段的草稿和原始代码，解释关键部分；学习者阅读，不懂就问，并逐步练习修改和独立实现。不一次性生成完整项目，每阶段验收后等待“继续”再推进。

每阶段同时讲解业务和实际源码，明确新增、修改的文件，并按请求对象、Controller、Service、Mapper、返回对象的调用顺序解析关键语句；不能只列功能或文件职责。

## 参考项目

- [macrozheng/mall](https://github.com/macrozheng/mall) 与 [mall-learning](https://github.com/macrozheng/mall-learning)：按具体问题查阅商品、订单与交易设计。
- [hy0713/ecommerce-order-system](https://github.com/hy0713/ecommerce-order-system)：新增参考项目，可按需比较订单状态、库存扣减与回补、商品缓存、超时处理及工程说明的组织方式。

这些仓库仅作为阅读和方案比较资料，不是必须照做的需求清单，也不作为本项目的工程底座。继续沿用从空工程搭建、按阶段理解和验证的路线；不因参考项目包含微服务、前端、小程序或 AI 模块而扩大当前范围，MQ 仍按实际需要评估。

2026-10-16：已查看 ecommerce-order-system 的公开 README 与根目录结构。本次没有运行或逐项验证其实现；上述内容表示参考方向，不代表已采用其代码或验证其能力。

## 当前工程与工具

- Java：21；本机已验证 JDK 21.0.10。
- Spring Boot：3.5.16。
- MyBatis Spring Boot Starter：3.0.5。
- 参数校验：Spring Boot Starter Validation 3.5.16，Hibernate Validator 8.0.3.Final。
- Spring Security：6.5.11，使用 Boot Starter Security 3.5.16；Security Test 仅用于测试。
- Redis：本机复用 WSL2 Ubuntu 22.04.5 中已有的6.0.16；新增 Starter Data Redis 3.5.16，Spring Data Redis 3.5.13、Lettuce 6.6.0.RELEASE，由 Boot 管理版本。
- RabbitMQ：本机4.3.6，Erlang/OTP 28.5.0.8；Starter AMQP 3.5.16、Spring AMQP 3.2.12、Rabbit Java Client 5.25.0、Spring Retry 2.0.13由Boot管理。
- MySQL 服务端：本机已验证 8.0.45；Connector/J 驱动：8.4.0。
- Maven：3.9.16，放在 `tools/apache-maven-3.9.16/`。
- 项目依赖与构建插件缓存：`tools/maven-repository/`。
- `.mvn/maven.config` 指定项目独立的依赖仓库，请在项目根目录执行 Maven 命令。
- `tools/` 和 `target/` 不提交到 Git；JDK 沿用本机安装，不在项目中复制。

当前 Maven 从旧 SSM 目录复制并核验文件哈希；后续重新获取代码时，需要自行准备 [Apache Maven 3.9.16](https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/)，解压为上述工具目录。构建会从 Maven Central 下载缺失依赖到项目的独立仓库。

### 数据库初始化（首次准备）

将 `config/db.properties.example` 复制为同目录下的 `db.properties`，填写本机数据库用户名与密码。开发库为 `limited_trade_platform`，测试库为 `limited_trade_platform_test`；真实配置被 Git 忽略，并且不放入 JAR。

从项目根目录启动本机 MySQL 客户端，使用有建库权限的本机账号登录，在客户端执行：

```sql
CREATE DATABASE limited_trade_platform CHARACTER SET utf8mb4;
CREATE DATABASE limited_trade_platform_test CHARACTER SET utf8mb4;

USE limited_trade_platform;
SOURCE sql/schema.sql;
SOURCE sql/002_app_user.sql;
SOURCE sql/003_trade_order.sql;
SOURCE sql/004_order_payment_cancel.sql;
SOURCE sql/005_order_timeout.sql;
SOURCE sql/006_activity.sql;
SOURCE sql/007_order_timeout_outbox.sql;
SOURCE sql/008_product_category.sql;
SOURCE sql/009_cart_item.sql;
SOURCE sql/010_user_address.sql;
SOURCE sql/seed.sql;

USE limited_trade_platform_test;
SOURCE sql/schema.sql;
SOURCE sql/002_app_user.sql;
SOURCE sql/003_trade_order.sql;
SOURCE sql/004_order_payment_cancel.sql;
SOURCE sql/005_order_timeout.sql;
SOURCE sql/006_activity.sql;
SOURCE sql/007_order_timeout_outbox.sql;
SOURCE sql/008_product_category.sql;
SOURCE sql/009_cart_item.sql;
SOURCE sql/010_user_address.sql;
```

以上命令仅用于首次初始化，当前本机已执行完毕，请勿重复执行。脚本遇到已有表或重复编号会报错，不覆盖原数据。`seed.sql` 只用于开发库，测试用例自行插入测试库数据，并通过事务回滚。

本机已生成 `learning_admin` 和 `learning_user` 两个学习账号，随机密码只保存在被 Git 忽略的 `config/local-accounts.txt`。该文件不进入 JAR 或 Git。新环境的账号初始化与登录操作见 [登录与权限学习记录](docs/learning/04-security.md)，不能假设其他机器存在这些本机账号。

准备好配置与数据库后，在 PowerShell 中从项目根目录执行：

当前默认开启商品缓存，应用访问 `127.0.0.1:6379`。先在另一个终端运行 `wsl -d Ubuntu-22.04`，进入后用 `redis-cli PING` 确认返回 `PONG`，并保持该终端会话打开。本机观察到 WSL 没有活动 Linux 会话时会停止，届时 Java 无法连接 Redis；无需修改系统配置。新环境需自行准备可用 Redis，本项目未提供或打包 Redis 服务端。

当前还强制读取外部 `config/rabbitmq.properties`。本机已配置独立随机凭据及开发/测试虚拟主机；新环境复制 `config/rabbitmq.properties.example` 后填写自身配置，创建 `limited-trade` 与 `limited-trade-test` 虚拟主机并授予该用户权限。本机RabbitMQ目录为 `tools/rabbitmq_server-4.3.6`，Erlang实际安装在 `tools/Erlang OTP`；运行 `.\scripts\start-rabbitmq.ps1` 启动普通后台进程，日志在 `tools/rabbitmq-runtime/console.log`。管理页为 http://127.0.0.1:15672 ，用户与口令仅在本机配置中查看；AMQP与管理端口均只监听回环地址。节点已运行时不用重复启动，使用完运行 `.\scripts\stop-rabbitmq.ps1` 停止项目节点。

```powershell
# 编译、运行测试并打包
.\tools\apache-maven-3.9.16\bin\mvn.cmd --batch-mode --no-transfer-progress package

# 启动应用，读取项目根目录 config/db.properties 和 config/rabbitmq.properties
java -jar .\target\limited-trade-platform-0.0.1-SNAPSHOT.jar
```

应用启动后，在另一个 PowerShell 窗口访问：

```powershell
Invoke-RestMethod http://localhost:8080/api/ping
Invoke-RestMethod http://localhost:8080/api/products
```

ping 预期 `message` 为 `pong`；商品接口预期返回机械键盘、无线鼠标，不包含下架的游戏耳机。使用后在启动窗口按 Ctrl+C 停止应用。

## 本阶段文件导航

| 文件 | 作用 |
|---|---|
| `pom.xml` | 声明 Java、Boot、Web、MyBatis、MySQL 驱动和测试依赖，以及打包插件 |
| `.mvn/maven.config` | 将 Maven 依赖仓库指定到项目的 `tools` 中 |
| `src/main/java/com/elaina/limitedtrade/LimitedTradeApplication.java` | 应用入口，在根包开启自动配置与组件扫描 |
| `src/main/java/com/elaina/limitedtrade/system/PingController.java` | 将 GET `/api/ping` 映射到方法，返回 JSON |
| `src/main/resources/application.yml` | 设置应用名称、HTTP 端口、外部数据库配置与 Mapper XML 位置 |
| `src/test/java/com/elaina/limitedtrade/system/PingControllerTest.java` | 启动测试上下文，通过 MockMvc 检查接口状态码、内容类型与响应字段 |
| `sql/schema.sql`、`sql/seed.sql` | 商品建表脚本与开发库演示数据 |
| `config/db.properties.example` | 可提交的连接配置模板，真实凭证另存到被忽略的文件 |
| `src/main/java/com/elaina/limitedtrade/product/` | 商品数据载体、Mapper 接口、Service 与 Controller |
| `src/main/resources/mapper/ProductMapper.xml` | 查询上架商品的实际 SQL |
| `src/test/resources/application-test.yml` | 将测试数据源指向独立测试库 |
| `src/test/java/com/elaina/limitedtrade/product/ProductQueryTest.java` | 使用真实 MySQL 验证查询、字段映射、上下架过滤与排序 |
| `sql/002_app_user.sql` | 用户表、唯一登录名、密码摘要与单角色字段 |
| `src/main/java/com/elaina/limitedtrade/user/`、`src/main/resources/mapper/AppUserMapper.xml` | 按登录名读取数据库账号 |
| `src/main/java/com/elaina/limitedtrade/auth/` | Security 规则、数据库认证适配、当前用户与 CSRF 接口 |

MockMvc 不启动真实 HTTP 监听端口。商品查询测试实际连接 MySQL，能够验证本阶段的数据库查询，但不代表尚未实现的交易流程已经可用。

## 本阶段验证记录（2026-10-16）

| 检查 | 实际结果 |
|---|---|
| Maven 工具复制 | 96 个文件的 SHA-256 与旧 SSM 目录中的原文件一致 |
| 先写测试，再补接口 | 没有 Controller 时，测试因预期 200、实际 404 而失败 |
| 添加 Controller 后执行 `package` | 1 个测试通过，0 失败、0 错误；生成可执行 JAR |
| 执行 `--offline -DskipTests package` | 使用项目内依赖缓存离线打包成功；本次明确跳过测试 |
| 实际运行 JAR | 绑定 `127.0.0.1`，GET `/api/ping` 返回 HTTP 200、`application/json` 和 `{"message":"pong"}` |
| 验证进程清理 | HTTP 检查后通过 Ctrl+C 停止，应用完成优雅关闭 |

构建日志位于本机 `tools/bootstrap-red.log`、`tools/bootstrap-green.log` 和 `tools/bootstrap-offline.log`，不提交到 Git。测试启动时存在 Mockito/Byte Buddy 动态加载 Java 代理的提示；当前 JDK 21 下测试通过，本阶段没有为此增加构建配置。

## 商品查询阶段记录（2026-10-16）

- 独立创建开发库与测试库，不修改旧 SSM 数据库。
- 开发库包含两条上架商品、一条下架商品；测试库只保存表结构。
- 查询阶段的 `Product` 最初采用 record；在商品新增阶段改为 JavaBean，以支持 MyBatis 回填自增主键，不引入 Lombok。
- `ProductMapper` 由 MyBatis 创建代理实现；XML 的 `namespace` 对应接口全限定名，`select` 的 `id` 对应方法名。
- `ProductService` 当前只调用 Mapper；保留这一层用于后续组织业务规则，不增加额外接口和实现类。
- 商品接口当前直接返回 `List<Product>`，不引入分页或统一响应包装。
- 测试先在商品接口缺失时因 404 失败；实现后执行 `package`，两个测试均通过。
- 实际启动 JAR 后，GET `/api/products` 返回 HTTP 200 和两条上架商品；验证后停止应用。
- 测试结束后核对测试库商品数为 0，开发库仍保留三条演示数据；JAR 中包含 Mapper XML 与驱动，不包含本机凭证和测试资源。
- 本机过程日志：`tools/product-query-red.log`、`tools/product-query-green.log`；实际 HTTP 响应：`tools/product-query-http.json`。这些本机证据文件不进入 Git。

## 商品维护阶段记录（2026-10-16）

完整代码阅读顺序与说明见 [商品维护学习记录](docs/learning/03-product-maintenance.md)。

| 方法与路径 | 请求字段 | 结果 |
|---|---|---|
| POST `/api/admin/products` | `name`、`price`、`stock` | 201，新增商品默认下架，返回含自增编号的商品 |
| PUT `/api/admin/products/{id}` | `name`、`price` | 200，只修改名称和价格，不覆盖库存与状态 |
| PATCH `/api/admin/products/{id}/status` | `status` | 200，状态只允许 `ON_SALE` 或 `OFF_SALE`，重复设置也成功 |

参数不合法返回 400，操作的商品不存在返回 404。名称不能为空且最多100个 Java 字符单元；价格大于0，最多8位整数和2位小数；初始库存是非负 INT 整数。禁止将 JSON 小数库存截断为整数。

第三阶段时尚未接入认证授权，因此曾使用默认关闭的 `app.admin-api.enabled` 临时开关。第四阶段已移除该开关，改由 Spring Security 验证真实登录身份与 ADMIN 角色；不再通过启动参数开放匿名管理操作。

验证：新增接口缺失时先观察到 404；实现后执行 `package`，全部26个测试通过，0失败、0错误。测试连接独立 MySQL 测试库，并在事务中回滚。本阶段没有执行开发库管理写入，也没有将 MockMvc 测试称为真实 HTTP 管理接口验证。

本机日志：`tools/product-maintenance-red.log`、`tools/product-maintenance-green.log`；不提交到 Git。

## 登录与权限阶段记录（2026-10-16）

完整代码说明、账号准备和登录步骤见 [登录与权限学习记录](docs/learning/04-security.md)。

| 接口 | 规则 |
|---|---|
| GET `/api/auth/csrf` | 允许匿名访问，取得当前 Session 的 CSRF Token |
| POST `/api/auth/login` | 表单参数 `username`、`password`，需要 CSRF Token；成功204、凭证错误401 |
| GET `/api/auth/me` | 已登录用户访问，返回 `id`、`username`、`role` |
| POST `/api/auth/logout` | 需要 CSRF Token，成功204并使 Session 失效 |
| `/api/admin/**` | 仅 ADMIN 角色可访问，写请求还需要有效 CSRF Token |

验证结果：全量38个测试通过。13个认证工作流测试使用数据库账号和 BCrypt；23个商品维护测试以模拟管理员身份验证原业务，商品查询与 ping 各1个测试。

实际启动 JAR 后，使用两个随机密码账号完成 CSRF 获取、登录、身份查询、角色限制与退出验证，确认登录时 Session ID 更换，退出后查询身份为401。实际 HTTP 权限检查对管理接口发送空对象：管理员得到参数错误400，普通用户得到权限错误403；成功管理写入由真实 MySQL 的事务回滚测试验证。

排查记录：PowerShell 7.6.5 的 WebRequestSession 会保留通过 `-Headers` 设置的 CSRF 头，省略该参数不等于不带 Token。显式移除会话中该请求头后，实际写请求返回403。首次误测生成的一条临时商品已单独删除，开发库原三条商品保持原值；自增编号出现空隙是正常现象。

测试库用户表与商品表最终均为0行；开发库有2个学习账号、3条商品。本机凭证和测试依赖未进入 JAR。验证进程已停止。

本机证据：`tools/security-red.log`、`tools/security-green.log`、`tools/security-http.json`，均不进入 Git。

## 订单创建与归属阶段记录（2026-10-16）

代码变化和阅读顺序见 [订单创建学习记录](docs/learning/05-order-creation.md)。

| 接口 | 请求或结果 |
|---|---|
| POST `/api/orders` | `productId`、`quantity`、`requestId`；首次201，同请求重试200 |
| GET `/api/orders` | 返回当前登录用户的订单，按编号降序 |
| GET `/api/orders/{id}` | 返回当前用户的指定订单；不存在或属于别人均404 |

用户编号从认证身份取得，价格与商品名称从数据库取得；客户端传入的用户编号和金额不作为下单依据。创建订单时扣减可售库存，并保存成交快照；当前状态为 `PENDING_PAYMENT`。

同一用户的下单事务首先通过 `SELECT ... FOR UPDATE` 锁定用户行，再判断该用户的请求标识是否已存在；相同内容返回原订单，不同商品或数量复用同一标识返回409。不同用户仍可并发竞争库存，通过 `stock >= quantity` 的条件更新防止超卖。扣库存与保存订单在同一事务内。

这是正确性优先的初版取舍：同一用户下单串行，不宣称高吞吐。数据库另有 `(user_id, request_id)` 唯一约束。`request_id` 区分大小写，允许1至64位字母、数字、下划线或连字符。

第五阶段先记录下单后15分钟的支付截止时间；当时尚未实现支付、取消与自动关闭。这些后续能力分别在第六、七阶段完成。

验证：离线执行 `package`，全量57个测试通过，其中订单接口17个、并发正确性2个。并发测试使用10个线程提交20个不同用户请求，5件库存仅成功5单；相同请求并发重试20次，只生成1单并扣1次库存。测试连接真实 MySQL，经回滚或定向清理后测试库三张表均为0行。

本阶段没有执行实际 HTTP 订单写入，开发库订单仍为0，原有商品库存与学习账号保持原值。上述并发结果是本机正确性检查，不是吞吐或公网性能数据。本机日志为 `tools/order-red.log`、`tools/order-green.log`。

## 模拟支付与主动取消阶段记录（2026-10-16）

逐段代码解析见 [支付与取消学习记录](docs/learning/06-order-payment-cancel.md)。

| 接口 | 行为 |
|---|---|
| POST `/api/orders/{id}/pay` | 本人待支付且未到截止时间的订单转为 PAID，记录 paidAt，不再扣库存 |
| POST `/api/orders/{id}/cancel` | 本人待支付订单转为 CANCELED，记录 endedAt，并归还购买数量 |

两者不需要请求正文，需要登录与有效 CSRF Token，成功均返回200及 OrderResponse。重复支付已支付订单、重复取消已取消订单均返回原结果；相反终态或过期支付返回409，订单不存在或不属于本人返回404。

新增 `sql/004_order_payment_cancel.sql`，已对开发库和测试库执行，保留已有数据，增加 nullable 的 paid_at、ended_at。TradeOrder 与 OrderResponse 同步增加 paidAt、endedAt，已有查询 SQL 同步读取新列。

Service 在同一事务中锁定本人订单行，再执行带原状态条件的更新。支付先取得行锁，再取得当前时间判断截止时间。取消只有在成功转换状态后才调用库存归还 SQL，状态与库存同事务提交或回滚。

这只是模拟支付的状态记录，没有调用真实支付服务。第六阶段会拒绝过期支付，当时尚无自动关闭；第七阶段补上定时超时关闭。待支付订单仍可主动取消。

全量77个测试通过，新增17个支付取消接口用例和3个并发状态用例。覆盖重复操作不改原时间、取消不抹掉金额、库存只归还一次、越权与过期拒绝，以及支付取消竞争只能进入一种合法终态。

验证使用 MockMvc 和真实 MySQL，没有执行开发库 HTTP 支付或取消；开发库商品与订单行与增量脚本前的基线一致，测试库订单、商品、用户最终均0行。日志位于 `tools/order-actions-red.log`、`tools/order-actions-green.log`，不进入 Git。

## 超时关闭阶段记录（2026-10-16）

原始代码与逐句说明见 [超时关闭学习记录](docs/learning/07-order-timeout.md)。

新增 OrderTimeoutScheduler 和 OrderSchedulingConfiguration；修改 OrderService、OrderMapper 及 XML。使用 Spring 自带调度，不新增依赖；默认开启，每轮完成后等待30秒再扫描，单轮最多100单。

扫描以本轮时间为截止点，按到期时间、编号查询待支付候选订单。每个候选由另一个 Spring Bean 的 @Transactional 方法处理；只有条件更新成功，把状态从 PENDING_PAYMENT 改为 CLOSED 后，才归还库存。订单金额和成交快照保持原值，ended_at 记录本轮扫描时间。已支付、已取消、已关闭或尚未到期的订单不会被改为 CLOSED。

sql/005_order_timeout.sql 已对开发库和测试库执行，新增 (status, expires_at) 扫描索引并更新状态注释。已有数据库只执行尚未执行的增量脚本。

离线 package：88个测试通过，0失败、0错误。新增11个测试覆盖截止时间相等、重复关闭、扫描批次、陈旧候选、并发扫描、过期支付与关闭竞争、取消与关闭竞争，以及真实库存 SQL 溢出时关闭状态回滚、修正测试数据后重试成功。普通测试关闭后台调度，手动调用扫描入口；这验证扫描逻辑和真实 MySQL 事务，不是实际 HTTP 或生产调度时效验证。

开发库商品、订单和账号公开字段与增量执行前基线一致；测试库订单、商品、用户最终均0行。本机证据：tools/order-timeout-red.log、tools/order-timeout-green.log、tools/order-timeout-baseline.tsv，不进入 Git。

轮询存在延迟；每轮有批量上限，积压时不能保证到期30秒内关闭。业务异常不在代码中吞掉：当前调用失败，本轮后续订单暂不处理，由日志暴露问题；此前已提交订单不受影响。尚未接入 MQ、分布式调度或告警系统。

## 管理员订单查询阶段记录（2026-10-16）

原始代码、请求示例与逐句说明见 [管理员订单查询学习记录](docs/learning/08-admin-order-query.md)。

新增 GET /api/admin/orders，仅 ADMIN 可用，沿用既有 /api/admin/** 权限规则。page 默认1且必须为正整数，size 默认20、范围1到100；可选 status 必须是 PENDING_PAYMENT、PAID、CANCELED、CLOSED 之一，可选 userId 必须为正整数。结果按订单编号降序，返回 page、size、total、items；total 为全部匹配条数，items 为当前页。末页不足 size 条正常，超出末页返回空 items 并保留 total。

新增 AdminOrderController、AdminOrderResponse、AdminOrderPageResponse；修改 OrderService、OrderMapper 与 XML。管理员响应额外包含购买用户编号 userId；本人订单接口保持原有归属规则。计数和分页查询共用动态筛选片段，在同一只读 REPEATABLE_READ 事务内读取；偏移量使用 long 计算。没有新增数据库表、迁移脚本或依赖。

先运行管理员列表用例，接口缺失时预期200、实际404。实现后离线 package 全量117个测试通过，其中新增29个管理员订单用例，覆盖默认页、分页排序、末页和空页、四种状态、用户及组合筛选、超大合法页码、非法参数、匿名401、普通用户403。验证为 MockMvc + 真实 MySQL，测试夹具事务回滚；本阶段没有执行开发库写入或实际 HTTP 验证。日志为 tools/admin-orders-red.log、tools/admin-orders-green.log，不进入 Git。

当前采用 OFFSET 分页，没有进行大数据量压测。不同请求翻页时数据仍可能变化，不承诺跨请求固定快照。

## 限时限量活动阶段记录（2026-10-16）

原始代码、设计取舍和逐段解析见 [限时限量活动学习记录](docs/learning/09-limited-activity.md)，实施记录见 [阶段计划](docs/plans/2026-10-16-limited-activity.md)。

- POST /api/admin/activities：ADMIN 和 CSRF 创建活动，输入 productId、name、totalQuantity、startsAt、endsAt；返回201及活动详情。
- GET /api/activities/{id}：公开读取一个活动，返回配置和剩余名额。
- POST /api/orders：新增可选 activityId。不传保持普通下单；传入时活动必须对应商品，满足 startsAt <= 下单检查时间 < endsAt，且商品库存、活动名额都充足。

活动名额是销售数量上限，创建时不预留商品库存。活动订单在同一事务中扣商品库存、活动名额并保存订单；时间在活动和商品锁等待结束后取得。失败整体回滚。同一幂等请求比较商品、数量、活动；活动结束后重试原请求仍返回原订单。

活动订单支付仍按订单创建后15分钟截止，不随活动结束立即失效。取消或超时关闭成功后按活动→商品顺序归还两份数量；已结束活动的名额也恢复，但时间条件仍阻止新下单。普通订单继续只处理商品库存。

006_activity.sql 已在两个数据库增量执行，新增 activity 表和 trade_order.activity_id 可空外键，历史订单为NULL；所有订单查询和两类响应同步映射 activityId。没有新增依赖、折扣、活动编辑、删除、列表或每人限购。

先观察创建接口201/404红测试，以及活动订单缺少activityId的红测试；实现后离线 package：166个测试通过，0失败、0错误。新增49个活动用例，其中活动配置21、活动订单20、并发与故障8。20个不同用户竞争5个名额只创建5单；商品仅3件时仅创建3单；20次同键并发只创建1单。另验证取消/关闭竞争、真实库存溢出时状态与名额回滚，以及等待商品锁跨活动结束后拒绝下单并恢复商品扣减。

验证为 MockMvc/Service + 真实 MySQL，不是实际 HTTP 或性能压测。活动订单和并发测试使用已提交夹具，并在服务事务结束后检查真正提交或回滚的状态，再定向清理。开发库原数据行与006之前基线一致，活动和订单均0行；测试库四张表均0行。

证据位于 tools/activity-red.log、tools/activity-config-green.log、tools/activity-order-red.log、tools/activity-green.log、tools/activity-baseline.tsv，均不进入Git。本阶段未提交或推送代码。

## Redis 商品缓存阶段记录（2026-10-17）

完整原始代码说明、调用链、启动方式和一致性边界见 [Redis 商品缓存学习记录](docs/learning/10-product-cache.md)。

GET /api/products 沿用原接口和响应，采用先读 Redis、未命中查 MySQL 并缓存 JSON 的方式。键为 limited-trade:product:on-sale:v1，有效期30秒，空列表也缓存。商品改名、改价、上下架，以及普通/活动订单扣库存、取消和关闭归还库存，都通过事务 afterCommit 删除缓存；支付和幂等重试不改变库存，不触发删除。

新增 ProductCache、ProductCacheConfiguration 和18项真实 Redis/MySQL 缓存集成测试；修改 ProductService、OrderService、应用配置与 pom.xml。没有修改 SQL 或数据库结构。经用户确认下载缺失 Maven 依赖到本项目 tools/maven-repository，没有安装或升级 Redis 服务端。

先观察缺少缓存时3项失败，再观察补查询缓存但尚无失效时10项失败；实现后专项18项通过，离线 package 全量184项通过，0失败、0错误、0跳过。既有166项交易、权限和并发测试保留。验证为 MockMvc/Service + 真实 MySQL + 真实 Redis，不是吞吐压测或生产验证；额外真实 HTTP 检查的临时进程启动被自动审批拒绝，因此本轮没有真实 HTTP 缓存结果。

缓存允许短暂展示旧数据，并发回填旧值可能在该次写入后的30秒内保留；交易价格与扣库存仍直接使用 MySQL。Redis 异常不吞掉；提交后删除失败时数据库已经提交，不能因返回错误就认定数据库回滚。

本轮前后开发库公开字段快照一致：3条商品、2个学习账号、0订单、0活动；测试库四张表均0行。缓存测试只使用并清理自己的随机键，不执行 FLUSHDB。既有测试默认关闭缓存，避免将外层测试事务中的夹具写入 Redis；缓存专项单独开启并使用真正提交的隔离夹具。

本机证据：tools/product-cache-red.log、product-cache-all-red.log、product-cache-invalidation-red.log、product-cache-green.log、product-cache-package.log、product-cache-before.tsv、product-cache-after.tsv，均不进入 Git。本阶段未提交或推送；既有参考项目说明保留。

## 可靠消息与订单超时阶段记录（2026-10-18）

新增代码、逐段语法与完整调用链见 [可靠消息与订单超时学习记录](docs/learning/11-reliable-order-timeout.md)，方案及实施记录见 [设计](docs/plans/2026-10-18-reliable-order-timeout-design.md) 和 [计划](docs/plans/2026-10-18-reliable-order-timeout-implementation.md)。

首次创建普通/活动订单后，在同一MySQL事务登记唯一的 `order_timeout_outbox` 事件；幂等提前返回不登记。独立任务每轮结束后等待3秒，每批20条，按事件行 `FOR UPDATE SKIP LOCKED` 取得执行权后投递RabbitMQ。每次发送独立CorrelationData，等待确认2秒，只有ACK且没有mandatory退回才记SENT；AMQP失败保持PENDING，按5/10/20/40/60秒、之后60秒重试。数据库异常及线程中断向外传播。

消息通过持久化等待队列TTL900000毫秒转到ready队列，消费者校验正文后调用原 `closeExpiredOrder`；AUTO确认，消费总共3次尝试，间隔1秒、2秒，仍失败则拒绝回队并进入failed队列。原30秒/100条数据库扫描继续兜底。共享调度开关与各任务开关分离，关闭消息功能仍登记Outbox。

已对两个项目数据库各执行一次007迁移；不要重复执行。真实MySQL验证订单/事件原子性、幂等、SQL失败回滚、事件锁竞争、确认后本地更新失败重投及退避；真实RabbitMQ专项14项通过，覆盖TTL关单、重复/提前/终态消息、归还SQL溢出后的失败归档与修复重投、非法JSON/字段/类型头、关闭监听后的扫描兜底、提交后缓存故障、不可路由退回与真实连接拒绝后恢复投递。连接拒绝使用独立客户端指向关闭的本地端口；没有将其称为服务端宕机演练。NACK、确认丢失和中断使用发送边界注入验证。隔离消息专项使用随机前缀与200毫秒TTL，并显式设置测试库订单截止时间；生产默认仍15分钟，没有实际等待15分钟演示。

离线 `package` 全量218项测试通过，0失败、0错误、0跳过，包含原184项和新增34项；JAR打包成功且不含本机凭据、测试配置或tools。开发库原四表公开字段快照一致，新Outbox表0行；测试库五表均0行，自有触发器和随机前缀队列/交换机均无残留。独立只读复核未发现明确重要缺陷。

SENT不表示消费完成；TTL从实际入队算起，投递重试及积压可能延迟消息，数据库支付截止时间保持原值。持久化与确认不保证端到端不丢消息，重复消费仍靠原条件状态转换保证库存与名额只归还一次。本阶段证据为本机Service/MockMvc、真实MySQL/Redis/RabbitMQ集成与故障注入，不是吞吐、生产集群或公网验收。

本机证据在 `tools/order-message-*.log`、`tools/order-message-before.tsv`、`tools/order-message-after.tsv`、`tools/order-timeout-progress.md`，均被忽略。本阶段未提交、推送或合并；上一阶段Redis与参考项目说明保留。

## 商品一级分类与分页筛选阶段记录（2026-10-18）

新增源码与逐段流程见 [第十二阶段学习记录](docs/learning/12-product-category-search.md)。本机已对开发库、测试库各执行一次008；不要重复执行。新增 `product_category`，名称最多50字符且唯一；商品增加可空 `category_id` 外键，旧商品保留未分类，不回填或修改原业务数据。

| 接口 | 行为与权限 |
|---|---|
| GET `/api/categories` | 公开，按id升序返回分类id/name |
| POST `/api/admin/categories` | ADMIN及CSRF，name创建分类，成功201 |
| PUT `/api/admin/categories/{id}` | ADMIN及CSRF，name改名，成功200，重名409、不存在404 |
| POST/PUT 原商品管理接口 | 增加可选categoryId；不存在分类404，非法编号400；完整PUT省略或null表示清除分类 |
| GET `/api/products/search` | 公开，categoryId与keyword可选；page默认1、size默认20且最多100 |

搜索响应为 `page/size/total/items`，每项包含原商品字段及categoryId/categoryName。只查询在售商品，名称关键词去首尾空白、按字面包含匹配，`%`、`_`、`!` 显式转义，使用预编译参数。无匹配返回空页；无分类条件时LEFT JOIN保留未分类商品。计数和分页共用动态SQL条件，在同一只读REPEATABLE_READ事务读取；offset使用long，按商品id升序。不同分页请求仍是不同快照，不承诺并发变动下跨请求的固定结果。

原GET `/api/products`继续走原Redis列表缓存，通过 `ProductListItem` 固定五字段响应。内部Product增加可选categoryId，旧缓存JSON没有此字段时仍可反序列化；新分页查询直接查库，不复用该缓存，分类名称也不写入旧缓存。原列表key与30秒有效期保持。

分类15项、商品关联9项、搜索28项以及既有商品用例合计76项专项通过；真实Redis集成另新增3项兼容性检查。离线package全量273项通过，0失败、0错误、0跳过，BUILD SUCCESS，包含原218项与新增55项。独立只读复核无重要缺陷。开发库原五表公开字段基线一致、新分类表0行，原商品保持未分类；测试库六表均0行，JAR不含本机凭据、测试配置、工具或测试类。

临时启动JAR做真实HTTP验证被自动审批拒绝，工具仅返回blocked by policy；本阶段没有该项HTTP运行证据，接口结果来自MockMvc与真实MySQL，缓存兼容使用真实Redis。本轮未新增依赖、提交、推送或合并。证据在 `tools/catalog-*.log`、`tools/catalog-before.tsv`、`tools/catalog-after.tsv` 和 `tools/catalog-progress.md`，不进入Git。

## 用户购物车阶段记录（2026-10-18）

新增源码及调用链见 [第十三阶段学习记录](docs/learning/13-cart.md)，[设计](docs/plans/2026-10-18-cart-design.md) 和 [实施计划](docs/plans/2026-10-18-cart-implementation.md) 已完成。新增009在两个项目数据库各执行一次，创建空 `cart_item`，不要重复执行。组合主键 `(user_id,product_id)` 保证一用户一商品一行；两个外键不级联删除，数量为正整数。

| 接口 | 行为 |
|---|---|
| GET `/api/cart/items` | 登录后返回本人购物车数组，按商品编号升序，包含productId/name/price/stock/status/quantity |
| PUT `/api/cart/items/{productId}` | 登录且有CSRF；正文quantity为正整数；新增或覆盖最终数量，成功204 |
| DELETE `/api/cart/items/{productId}` | 登录且有CSRF；删除本人记录，不存在也204 |

用户编号始终取登录身份，不接受客户端指定归属。PUT检查商品存在与当前ON_SALE状态，不存在404、下架409；quantity缺失/null/非正数/小数/超Integer范围及非法路径编号400。单条UPSERT设置绝对数量，同值重试不累加；查询与删除均限定本人。ADMIN也只操作自身购物车。

购物车不扣库存、不创建订单/Outbox、不清理商品缓存，允许保存超过当前库存或在售零库存商品的意向数量；GET直接连接MySQL读取当前商品信息，保留后来下架的条目供查看、删除。正式下单仍按原交易规则执行；当前直接下单不会自动移除购物车。没有接多商品结算、活动购物车、地址或前端。

API24项先红（22失败、0错误），实现后24项全绿；真实MySQL并发3项和提交型持久化/回滚/旧交易边界3项，专项合计30项通过。覆盖10线程20次同键设置、不同绝对数量、设置/删除竞争不影响另一用户、退出并以新Session重新登录后保留、事务回滚及旧下单不清空购物车。登录与接口使用MockMvc+真实数据库，不能称为真实HTTP。

离线package全量303项通过，0失败、0错误、0跳过，BUILD SUCCESS，包含原273项与新增30项。独立只读复核未发现重要问题。开发原六表公开字段基线一致，新购物车表0行；测试库七表0，原随机Redis测试键和MQ随机前缀队列/交换机清理，JAR不含本机凭据、测试配置、工具或测试类。

本阶段没有执行临时JAR真实HTTP检查，按已批准计划未重试此前被自动审批拒绝的进程启动。没有新增下载、提交、推送或合并。证据在 `tools/cart-api-red.log`、`cart-api-green.log`、`cart-integration.log`、`cart-package.log`、`cart-before.tsv`、`cart-after.tsv` 和 `cart-progress.md`，不进入Git。

## 用户收货地址阶段记录（2026-10-18）

新增源码及完整调用链见 [第十四阶段学习记录](docs/learning/14-address.md)，[设计](docs/plans/2026-10-18-address-design.md) 和 [实施计划](docs/plans/2026-10-18-address-implementation.md) 已完成。010已对两个数据库各执行一次，创建空 `user_address`；不要重复执行。地址表有自增主键、用户外键、六个内容列、0/1默认标记、时间及 `(user_id,is_default,id)` 索引。

| 接口 | 行为 |
|---|---|
| GET `/api/addresses` | 200，本人列表，默认优先、id升序，空时[] |
| POST `/api/addresses` | 201，当前本人地址数为0时自动默认，其余非默认 |
| PUT `/api/addresses/{id}` | 200，完整修改六字段，保留默认状态 |
| DELETE `/api/addresses/{id}` | 204，删除本人目标，不递补默认 |
| PUT `/api/addresses/{id}/default` | 204，无正文，切换本人默认项 |

全部需要登录，四种写入需要CSRF；ADMIN也只能操作自己的地址。归属取认证用户编号，响应不暴露userId，客户端额外传userId/defaultAddress不改变归属或默认状态。不存在/非本人写目标404，非法字段/手机号/路径400。收货人及省市区最长50、详情最长200，文本保存前strip；手机号严格11位大陆格式，不验证真实归属或配送能力。

所有写入在事务内先复用用户行锁，再操作地址，首次并发创建只有一个默认。设置默认先确认目标归属，再同事务清旧设新；失败回滚恢复原默认。删除默认后可无默认，普通地址不提升；有其他地址时新建仍普通，删光后下一次新建自动默认。地址修改不影响商品、库存、购物车、订单、Outbox或缓存；尚未接订单地址快照及结算。

API先28项失败（缺路由403），实现后28项通过；并发3项、真实事务/SQL失败回滚/新Session登录持久化/旧业务边界4项，专项共35项通过。完整离线package338项通过，0失败、0错误、0跳过，BUILD SUCCESS。独立只读复核未发现重要问题。

开发原七表公开字段快照SHA256前后一致，地址0行；测试八表0行，自有失败触发器清理；随机Redis测试键与RabbitMQ测试队列/交换机无残留。JAR不含实际凭据、测试配置、工具和测试类。本阶段接口证据来自MockMvc+真实MySQL，完整回归含原Redis/RabbitMQ集成；未新增真实HTTP检查，未重试此前被拒绝的临时Java启动。没有新增依赖、下载、提交、推送或合并。

本机证据：`tools/address-api-red.log`、`address-api-green.log`、`address-integration.log`、`address-package.log`、`address-before.tsv`、`address-after.tsv`、`address-progress.md`，不进入Git。

## 计划范围

- 初期技术：Spring Boot、MyBatis、MySQL；后续按阶段接入 Spring Security、Redis。
- 第一版主线：商品查询与维护、单商品下单、本人订单查询、库存扣减、模拟支付、取消、超时关闭与库存归还。
- 基础稳定后：最小限量活动和商品缓存；MQ 按实际进度评估。
- 不将真实支付、复杂营销、微服务或完整前端列入第一版必做范围。

以上为计划，不能作为已实现能力或简历成果。
