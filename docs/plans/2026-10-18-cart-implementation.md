# 用户购物车 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task in the current session. Steps use checkbox (`- [x]`) syntax for tracking. 沿用主助手直接实现、完成后一次独立只读复核的方式，不派实现子代理。

**Goal:** 登录用户可以持久化保存本人商品购买意向，查看、设置绝对数量和移除，保证用户隔离及同键并发原子性。

**Architecture:** 在现有单体应用中增加cart模块和MySQL cart_item表。组合主键(user_id,product_id)与单条UPSERT保证设置数量不累加、不重复；查询连接商品表获取当前展示信息，不扣库存、不创建订单。

**Tech Stack:** Java21、Spring Boot3.5.16、MyBatis3.0.5、MySQL8.0.45、现有Spring Security Session/CSRF；不新增依赖、服务或缓存。

**Spec:** [用户已确认的设计](2026-10-18-cart-design.md)。

状态：设计及本计划获用户确认后已于2026-10-18执行完成。两库009各一次，专项30项、完整package303项通过，0失败/错误/跳过，独立复核无重要问题；未提交、推送。

## Global Constraints

- 工作目录D:/codex/Java/limited-trade-platform；沿用codex/bootstrap-boot当前工作区，保留既有Redis、MQ和分类改动，不创建工作树、不提交、不推送、不合并。
- 新文本UTF-8，代码注释中文；PowerShell设置UTF-8输出，文件显式按UTF-8读取；真实凭据留在config，不打印或打包。
- 仅GET /api/cart/items、PUT/DELETE /api/cart/items/{productId}；所有接口需登录，写入需CSRF，用户编号取PlatformUserDetails.getId()。
- quantity为@NotNull @Positive Integer，路径编号为正；PUT成功统一204，DELETE不存在本人记录仍204，GET空列表[]。
- PUT检查商品存在与ON_SALE，不存在404、下架409；不锁定库存，允许意向数量超过库存及在售零库存商品。
- GET保留下架商品，返回productId/name/price/stock/status/quantity；单条JOIN查询，按product_id升序，当前不分页。
- 不存商品价格/库存副本，不调用商品列表缓存失效，不发送MQ、不自动结算或清空购物车。
- 既有273项为历史基线，不能当作本次验收；展示新增业务代码和调用链，不讲测试源码。
- 执行记录放tools/cart-progress.md；必要日志tools/cart-*.log；不增加一次性生产工具类或泛化接口。

## Review Focus

1. 请求正文或查询参数伪造userId、两个用户保存同商品：不能改变归属、覆盖或删除他人；Task1测试。
2. 重复PUT与并发首次插入：绝对数量语义，组合主键保证单条记录；Task1及Task2测试。
3. 在售零库存、意向数量超库存、加入后下架：允许保存意向并展示当前状态，下架PUT409但DELETE可用；Task1测试。
4. 退出与再次登录、商品调价/改名：购物车保存到数据库，GET读取当前商品字段；Task1及Task2测试。
5. SET/DELETE竞争、事务回滚与旧下单接口：允许合法最终状态，不影响他人、不扣库存、不误清空；Task2测试。

## 文件与接口清单

新增Java根目录src/main/java/com/elaina/limitedtrade/cart/：

| 文件 | 精确接口/职责 |
|---|---|
| SetCartItemRequest.java | record SetCartItemRequest(@NotNull @Positive Integer quantity) |
| CartItemResponse.java | record CartItemResponse(Long productId,String name,BigDecimal price,Integer stock,String status,Integer quantity) |
| CartItemMapper.java | List<CartItemResponse> findUserItems(long userId)；int setQuantity(@Param("userId") long userId,@Param("productId") long productId,@Param("quantity") int quantity)；int deleteItem(@Param("userId") long userId,@Param("productId") long productId) |
| CartService.java | List<CartItemResponse> listItems(long userId)；@Transactional void setQuantity(long userId,long productId,int quantity)；@Transactional void removeItem(long userId,long productId) |
| CartController.java | /api/cart/items；GET返回List<CartItemResponse>，PUT/DELETE返回ResponseEntity<Void>的204 |

其余新增：sql/009_cart_item.sql、src/main/resources/mapper/CartItemMapper.xml、src/test/resources/sql/cart-test-data.sql、src/test/java/com/elaina/limitedtrade/cart/CartApiTest.java、CartConcurrencyIntegrationTest.java、CartPersistenceIntegrationTest.java、docs/learning/13-cart.md。

修改：src/main/java/com/elaina/limitedtrade/auth/SecurityConfig.java、README.md。原商品与交易代码不增加购物车逻辑；既有夹具只有确有本阶段依赖时才调整必要清理顺序。

## Task 1：表结构与完整购物车接口

**Consumes:** 已核对的ProductMapper.findById(long)、Product的id/name/price/stock/status；PlatformUserDetails.getId()；原Spring Security登录与CSRF。

**Produces:** 表及上述cart包全部接口；可调用的GET/PUT/DELETE与真实MySQL的API验证。

- [x] 检查Git状态和开发/测试库实际表结构；确认cart_item不存在。保存开发原六表公开字段基线到tools/cart-before.tsv，用户表排除密码摘要；确认测试六表无残留。工具私下读取db.properties，不输出凭据。
- [x] 新增009：cart_item的user_id/product_id为非空BIGINT，quantity为非空INT且CHECK(quantity>0)；组合主键(user_id,product_id)，product_id索引，两个不级联外键；created_at/updated_at沿用已有DATETIME默认值及ON UPDATE规则。
- [x] 对开发/测试库各执行一次009；切库后再创建JDBC Statement，避免复用旧Statement的catalog。核对两个库均有表与约束；不修改已有数据，不把缺表异常算业务红测试。
- [x] 写CartApiTest，@SpringBootTest+@AutoConfigureMockMvc+test profile+外层回滚事务。cart-test-data.sql使用已检索无冲突的用户7901/7902、商品10101/10102/10103；分别为在售有库存、下架、在售零库存。身份使用真实PlatformUserDetails测试认证对象，不用默认User代替自定义principal。
- [x] API红测试名称与精确预期：firstPutCreatesAndReplaySetsAbsoluteQuantity（PUT3两次均204，数据库一行quantity=3，再PUT5变5）；listUsesAuthenticatedOwner（另一用户同商品数量独立）；forgedUserIdCannotChangeOwner（正文和查询参数传7902，实际归属仍7901）；deleteOnlyOwnItemAndIsIdempotent（两次204，另一用户记录保留）；emptyCartReturnsArray（200及[]）。
- [x] 补边界红测试：quantity缺失/null/0/-1/1.5/2147483648和路径0/-1/非法字符串均400；未知商品404、下架商品409；失败请求不写入记录。Integer.MAX_VALUE可保存，不能另设99上限。
- [x] 补展示与权限红测试：超过库存及在售零库存PUT204且库存不变；GET按productId升序并保留后来下架项；改价/改名/库存后GET读取当前值。匿名GET401，匿名写携带CSRF时401，已登录写缺CSRF或CSRF无效403；USER与ADMIN均仅操作自己的记录。
- [x] 运行API测试，核对因路由/购物车行为缺失而失败；需要新类引用时只增加可编译骨架，再得到行为失败，不把编译失败计作红测试证据。
- [x] 实现请求/响应record、Controller、Service、Mapper/XML。GET使用WHERE c.user_id=#{userId}的单次JOIN，不过滤ON_SALE；映射六个结果字段。PUT先查Product并检查状态，然后单条INSERT ... ON DUPLICATE KEY UPDATE quantity=#{quantity}，不先查购物车、不累加；DELETE按user_id/product_id限定，不额外查商品。
- [x] SecurityConfig对/api/cart/items及/api/cart/items/**声明authenticated，保持现有规则和CSRF；Controller只把认证用户编号传给Service。SQL使用绑定参数，Service不重复校验已由HTTP边界保证的类型及范围。
- [x] 重新运行CartApiTest，要求0失败/错误/跳过；更新tools/cart-progress.md，不提交。

Run：项目根目录 `.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress "-Dtest=CartApiTest" test`，输出写tools/cart-api-red.log及cart-api-green.log。

Expected：三接口与用户归属、状态边界符合设计，数据库库存/订单/Outbox未因购物车发生变化。

## Task 2：真实事务、持久化与并发正确性

**Consumes:** Task1 CartService、CartItemMapper及购物车接口，现有OrderService.createOrder(long,CreateOrderRequest)与登录/退出接口。

**Produces:** 真正提交的隔离夹具下的并发、事务回滚、登录持久化及旧交易边界证据。

- [x] 写CartConcurrencyIntegrationTest与CartPersistenceIntegrationTest，不加外层回滚事务，分别使用用户7911/7912与7921/7922、商品10111与10121等各类自有编号；实施前再次检索冲突。创建前断言不存在，记录所有权标志，避免先清空已有数据。
- [x] 并发测试concurrentSameValueCreatesOneRow：10线程、20次设置quantity=3，每次调用真实代理Service事务；断言恰好一行、数量3、无额外订单/Outbox、原库存不变。
- [x] concurrentDifferentValuesDoNotAccumulate：20次设置1至20，断言仅一行、最终数量属于1至20中的成功设置值，不是累加总数；所有Future结果均读取，不吞掉线程异常。
- [x] settingAndDeletingSameItemDoesNotAffectOtherBuyer：本人同键SET/DELETE竞争，最终无记录或quantity=3；另一用户同商品预存quantity=7，最终仍7。工作线程必须等结束后才清理夹具。
- [x] outerRollbackLeavesCartUnchanged：TransactionTemplate内新增/修改购物车再显式回滚，分别验证无新增或原数量保留；不增加生产测试入口。
- [x] logoutAndFreshLoginKeepCommittedCart：隔离账号使用现有PasswordEncoder生成测试哈希，经MockMvc登录、设置购物车、退出，再用新Session登录同账号；GET仍返回数量。此为MockMvc+真实数据库验证，不称真实HTTP。
- [x] cartChangesDoNotCreateOrdersAndDirectOrderDoesNotClearCart：先保存购物车，核对库存/订单/Outbox未变化；调用旧单商品下单方法后，原订单/Outbox及库存规则生效，购物车记录仍保留。仅操作自有用户/商品，夹具含订单时先删自有Outbox再删订单。
- [x] 若这些测试暴露遗漏，先定位根因并保留失败日志，再仅修复当前购物车范围；数据库异常向外传播，不引入宽泛catch、默认成功或无依据自动重试。
- [x] 夹具清理：自有cart_item优先；再自有Outbox→订单→商品→用户。退出所建测试Session，清理不触碰其他用户。旧测试不写购物车时，不机械修改所有清理文件。
- [x] 运行三个购物车测试类，要求0失败/错误/跳过；核对测试七表无残留并记录结果，不提交。

Run：项目Maven offline/batch/no-transfer-progress，`"-Dtest=CartApiTest,CartConcurrencyIntegrationTest,CartPersistenceIntegrationTest" test`，日志tools/cart-integration.log。

Expected：并发为同键原子设置，GET不依赖Session保存购物车内容，交易与购物车边界保持。

## Task 3：整体复核、全量验收与新增源码教学

**Consumes:** Task1/2实现与专项证据、上一阶段现有全部功能及测试。

**Produces:** 一次独立只读复核、完整构建证据、清理/基线检查和第十三阶段教学稿。

- [x] 执行前读取requesting-code-review与verification-before-completion技能；一次独立只读审查聚焦用户隔离、UPSERT语义、CSRF、并发与清理，不让复核者修改文件或读取实际凭据。只修复重要的本次问题，保留对应失败再通过证据。
- [x] 核对MySQL、RabbitMQ及Redis真实可用；复用已有运行时，不安装或下载。若需保持WSL Redis活动，用本次独立keepalive并记录PID，结束后只停止自己的PID。
- [x] 运行一次完整离线package，记录真实测试数量、0失败/错误/跳过及BUILD SUCCESS。上一阶段273只作回归基线，不能预先宣称本轮通过；通过后除新失败/新变更外不重复扩大检查。
- [x] 比较开发原六表公开字段快照前后一致，cart_item开发表0；测试七表0。核对自有随机Redis键及测试MQ拓扑清理，JAR不含db.properties、rabbitmq.properties、local-accounts.txt、application-test.yml、tools或测试类。
- [x] 不重复尝试已被自动审批拒绝的临时Java进程启动来获取真实HTTP证据；如无允许且必要的新验证条件，本轮如实报告MockMvc+真实MySQL及完整集成回归边界。
- [x] 新建docs/learning/13-cart.md：展示实际新增SQL、请求/响应、Controller→Service→Mapper/XML的完整新增源码；解释认证用户取号、绝对数量、数据库组合主键、UPSERT、当前商品JOIN与事务；旧下单只交代边界，不展示测试源码。
- [x] README补009初始化顺序、三个接口、库存/状态规则、真实结果和未验证项；设计与本计划标记实际完成，tools/cart-progress.md记录裁定与证据。不要更新历史阶段的旧测试数量。
- [x] git diff --check；保留当前工作区，停止本次自有辅助进程，不提交、不推送、不开始结算或地址模块。

Run：`.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress package`，日志tools/cart-package.log。

Expected：完整旧功能与新购物车均通过，基线和清理正确；向学习者交付结果并开始按新增代码分段讲解，等待后续提问或继续。

## 计划自检与执行交接

- 设计规则1～10均对应Task1/2；Review Focus五项有明确用例；不新增购物车结算、活动购物车、前端、缓存或无依据数量上限。
- 类型统一：quantity为Integer输入/int内部参数，userId/productId为long、响应productId为Long；Mapper返回写入行数但Service不依赖UPSERT的1/2/0差异决定HTTP状态，统一204。
- 执行方式已确定为本会话主助手直接实现，不再询问子代理实现方式。现有不提交、不下载、不新建工作树的约定优先于技能默认步骤。
- 用户审阅本计划后，使用executing-plans按Task1→Task2→Task3连续执行，中途不因完成一项而再次索取确认；只有实际阻塞或新范围决策才询问。

## 完成记录

- API24项先红后绿，随后并发/持久化6项共30项通过；完整package303项通过。开发原六表基线一致，开发cart_item0，测试七表0，JAR与自有随机键/队列清理检查通过。
- 独立只读复核无重要问题；没有因无关问题扩大范围。旧商品/订单代码不增加购物车副作用。
- 迁移只创建空表；不改变原开发业务数据。仅文档收尾，不重复运行已通过的全量测试。
- 真实HTTP未执行，按计划未重试此前被拒绝的临时Java启动；验证界限已记入README和教学稿。
- tools/cart-progress.md保留执行证据。当前分支及全部工作区内容保留，没有提交或推送。
