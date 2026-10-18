# 用户收货地址 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task in the current session. Steps use checkbox (`- [x]`) syntax for tracking. 主助手直接实现，结束前一次独立只读复核，不派实现子代理。

**Goal:** 登录用户维护本人收货地址，首次自动默认，并在并发与事务失败下保持每用户最多一个默认项。

**Architecture:** 新增address模块及MySQL user_address表。全部地址写操作在Spring事务中先复用AppUserMapper.lockById锁用户行，再执行带用户归属条件的地址操作；默认切换的清旧与设新同事务，响应不暴露内部userId。

**Tech Stack:** Java21、Spring Boot3.5.16、MyBatis3.0.5、MySQL8.0.45、现有Session/CSRF与Validation；不新增依赖、服务或缓存。

**Spec:** [已确认的收货地址设计](2026-10-18-address-design.md)。

状态：设计和计划均获用户确认；2026-10-18三任务执行完成。API28项、专项35项、全量package338项通过；独立只读复核无重要问题。未提交、推送或合并。

## Global Constraints

- D:/codex/Java/limited-trade-platform内执行，沿用codex/bootstrap-boot及当前所有改动，不创建工作树、不提交、不推送、不合并。
- 新文本UTF-8，中文注释；PowerShell设置UTF-8输出，明确按UTF-8读文件；不输出真实凭据，不新增记录完整地址正文的业务日志。
- 仅本人的地址簿：用户编号来自PlatformUserDetails.getId()，ADMIN也无跨用户能力；写接口保持CSRF。
- 首次指当前本人地址数量为0，新建自动默认；后续新增非默认。删除默认后不自动选择替代项，允许0或1默认。
- 内容PUT不改默认；设置默认先确认本人目标，再清本人旧默认、设本人新默认；不存在/非本人写操作404。
- recipientName/province/city/district非空白且最长50，detailAddress非空白且最长200，保存前strip；phone非空白并严格匹配1[3-9][0-9]{9}，不接地区库/号码验证服务。
- 列表默认优先、id升序；不分页、不去重、不增加数量上限，不接订单地址字段或快照、不影响购物车/库存/订单/MQ/缓存。
- 旧303项为历史基线，不当成本次结果。仅讲新增业务源码和调用链，不展示测试源码。
- 执行记录tools/address-progress.md，日志tools/address-*.log；复用已有工具与依赖，验证后停止。

## Review Focus

1. 非本人/不存在目标设置默认：不能先清掉自己的原默认；Task1 API及Task2事务验证。
2. 同时首次新增、不同目标竞争默认：所有写入口必须先锁同一用户行，最终最多一个默认；Task2真实并发验证。
3. 清旧后设新SQL失败或外层回滚：原默认及地址内容要恢复；Task2触发器与TransactionTemplate验证。
4. 伪造userId/defaultAddress、重复修改相同内容：归属与默认状态保持，合法重复PUT200；Task1 API验证。
5. 删除默认但仍有普通地址、删除全部再新增、退出再登录：明确默认状态与持久化结果，不凭UI推断；Task1/2验证。

## 新增文件与精确接口

Java根目录src/main/java/com/elaina/limitedtrade/address/：

| 文件 | 类型/职责 |
|---|---|
| UserAddress.java | JavaBean：Long id/userId，String recipientName/phone/province/city/district/detailAddress，boolean defaultAddress；无参构造及getter/setter支持MyBatis |
| SaveAddressRequest.java | 六个String字段record，按Global Constraints声明HTTP校验 |
| AddressResponse.java | record：Long id，六个内容String字段，boolean defaultAddress；static from(UserAddress)用于创建、修改、列表的响应映射 |
| AddressController.java | /api/addresses，五个端点，@AuthenticationPrincipal取号、@Valid正文、@Positive路径 |
| AddressService.java | List<AddressResponse> listAddresses(long userId)；AddressResponse createAddress(long userId,SaveAddressRequest request)；AddressResponse updateAddress(long userId,long id,SaveAddressRequest request)；void deleteAddress(long userId,long id)；void setDefaultAddress(long userId,long id)；四个写方法@Transactional |
| AddressMapper.java | List<UserAddress> findUserAddresses(long userId)；long countUserAddresses(long userId)；UserAddress findOwnedAddress(@Param("id") long id,@Param("userId") long userId)；int insertAddress(UserAddress address)；int updateDetails(UserAddress address)；int deleteOwnedAddress(id,userId)；int clearDefault(long userId)；int markDefault(id,userId)，多参数均显式@Param |

其他新增：sql/010_user_address.sql、src/main/resources/mapper/AddressMapper.xml、src/test/resources/sql/address-test-data.sql、src/test/java/com/elaina/limitedtrade/address/AddressApiTest.java、AddressConcurrencyIntegrationTest.java、AddressTransactionIntegrationTest.java、docs/learning/14-address.md。

现有修改仅SecurityConfig、README及设计/计划完成状态；AppUserMapper.lockById、订单、商品、购物车业务保持原实现。内部字段映射到响应时排除userId；is_default通过显式resultMap映射defaultAddress。

## Task 1：数据表、完整API及默认规则

**Consumes:** 现有PlatformUserDetails.getId()、AppUserMapper.lockById(long)及对应FOR UPDATE；已有Spring事务、HTTP校验、安全过滤链。

**Produces:** 上述address包全部接口与表；可由Task2跨线程调用的真实代理Service。

- [x] 核对当前Git、数据库实际结构与user_address不存在；保存开发原七表公开字段基线至tools/address-before.tsv（用户表排除密码摘要，购物车按组合键排序）；确认测试七表无残留。读取实际数据库配置但不打印凭据。
- [x] 写010，只创建空user_address：id BIGINT自增主键，user_id外键不级联，六个内容列按设计长度，is_default TINYINT非空默认0且CHECK IN(0,1)，时间默认/更新规则沿用已有表，索引(user_id,is_default,id)。不建UNIQUE(user_id,is_default)。
- [x] 两个数据库各执行一次010，切库后创建Statement；核对表与外键/索引已落地，不将缺表异常作为红测试。
- [x] 写AddressApiTest，真实MySQL+MockMvc，test profile，外层回滚事务；address-test-data.sql仅建用户7931/7932，先检索编号/用户名冲突。测试认证对象用PlatformUserDetails；地址编号通过插入回填或响应读取，不猜自增值。
- [x] 红测试firstAddressIsDefaultAndLaterOnesAreNot：首个POST201及defaultAddress=true，后续两个POST201均false；本人三条且一默认，允许多个普通地址；空列表200及[]。
- [x] 红测试switchDefaultAndPreserveItOnContentUpdate：PUT /{id}/default两次均204，原默认false、目标true，列表目标在前其余id升序；内容PUT200，重复相同内容仍200，默认标记不变。
- [x] 红测试deleteDefaultDoesNotPromoteRemainingAddresses：删除默认204，其他地址仍false；再创建仍false；删完全部再创建时true。重复删除已不存在的编号404。
- [x] 红测试ownershipAndForgedFieldsCannotChangeOthers：两个用户地址互不可见，创建/修改额外传userId或defaultAddress不改变归属/默认；修改、删除、设默认的非本人/未知编号均404，原本人默认不丢失；响应无userId/密码字段。ADMIN也只操作自己。
- [x] 红测试inputValidation：六字段分别缺失/null/空白、姓名及地区51字符、详情201字符、手机号长度/字符/+86/首尾空格不符、路径0/负数/非整数/溢出均400且不写入。合法最长50/200边界接受；保存后内容去首尾空白。
- [x] 红测试security：匿名GET401，匿名写携有效CSRF401；登录写缺失/无效CSRF403，覆盖POST、内容PUT、DELETE及默认PUT。有效请求使用真实自定义principal，不能用框架默认User冒充。
- [x] 运行API红测试并确认因缺少路由/行为失败；缺类引用时先加可编译骨架，直到观察行为红，不把编译/依赖错误计为业务失败。
- [x] 实现Controller/Service/Mapper/XML及请求响应对象。四个写方法第一条数据库操作均AppUserMapper.lockById(userId)；创建在锁后countUserAddresses==0时标默认，插入回填id。
- [x] 内容更新按id/user_id更新六字段、不更新is_default，0行404，随后读本人地址返回；删除同样双条件、0行404。默认设置先findOwnedAddress，未找到立即404，再clearDefault(userId)、markDefault(id,userId)，不为持有用户锁后不可能的目标消失增加回退逻辑。
- [x] Service创建和修改共享一处内部请求→UserAddress内容映射，规范化五个文本字段并原样保存已校验phone；该映射仅供这两处复用，不额外建通用转换框架。AddressResponse.from供三个响应入口复用。
- [x] SecurityConfig增加/api/addresses及/api/addresses/**认证规则，保留默认CSRF；XML所有输入参数预编译绑定，列表限定user_id并ORDER BY is_default DESC,id ASC。
- [x] 运行AddressApiTest，要求0失败/错误/跳过；更新tools/address-progress.md，不提交。

Run：项目Maven `--offline --batch-mode --no-transfer-progress "-Dtest=AddressApiTest" test`，日志tools/address-api-red.log、address-api-green.log。

Expected：五接口与输入、归属、默认生命周期符合设计，其他业务数据不变。

## Task 2：真实并发、事务失败恢复与登录持久化

**Consumes:** Task1全部Service公共方法、真实用户行锁及数据库表，既有登录/退出接口。

**Produces:** 默认地址唯一性与失败回滚的证据，不在生产代码中增加测试入口。

- [x] 新增两类提交型测试，不加外层回滚事务。AddressConcurrencyIntegrationTest使用7941/7942，AddressTransactionIntegrationTest使用7951/7952；实施前再次检索，创建前断言专属用户/触发器不存在，取得夹具所有权后才写入。清理先本人user_address再本人app_user，不删其他业务记录。
- [x] concurrentFirstCreatesOnlyOneDefault：10线程、20次新建地址，全部调用真实代理createAddress；收集每个Future，断言20条、一默认、返回id不重复。数据重复内容允许，不能误加地址内容唯一约束。
- [x] concurrentSwitchesKeepOneDefaultPerUser：本人已有A/B及另一用户默认C，20次交替设置A/B；最终本人仅一默认且目标属于A/B，另一用户C保持默认。不要只验证线程方法返回。
- [x] switchingAndDeletingTargetHasOnlyAllowedResults：A默认、B普通；并发设置B与删除B，404作为目标已删的允许结果，其他异常必须使测试失败。最终B不存在，本人默认为A或无默认，另一用户默认不变；所有线程结束后再清理。
- [x] rollbackRestoresContentAndDefaults：TransactionTemplate内创建/修改/切换/删除后显式回滚，核对原内容、条数和默认项都恢复；用独立查询观察已提交结果。
- [x] realSqlFailureAfterClearingDefaultRestoresPreviousDefault：先建本人A默认、B普通；确认测试触发器test_address_default_reject_7951不存在，再建只针对7951且从非默认转默认时SIGNAL的BEFORE UPDATE触发器。调用setDefaultAddress(B)应抛DataAccessException；断言A仍默认、B仍普通、另一用户默认不变；finally仅删除本次创建的触发器。不要mock事务管理器或把异常吞掉。
- [x] logoutAndNewLoginKeepCommittedAddresses：隔离账号用现有PasswordEncoder生成测试哈希，经MockMvc登录、创建、退出、新Session登录；本人仍可读地址，另一用户读不到。结束退出/失效自有Session，不改开发账号。
- [x] 核对地址操作前后原商品、库存、购物车、订单和Outbox不因地址写入发生变动；不为这项检查修改旧业务实现。
- [x] 若出现失败，先查日志和实际SQL/锁顺序定位；写保留失败再通过证据，仅修复本阶段问题。不增加宽泛catch、默认成功、无依据重试或推测性功能。
- [x] 三类地址测试合并运行，0失败/错误/跳过；核对测试八表与自有触发器清理，记录结果，不提交。

Run：项目Maven offline/batch/no-transfer-progress，`"-Dtest=AddressApiTest,AddressConcurrencyIntegrationTest,AddressTransactionIntegrationTest" test`，日志tools/address-integration.log。

Expected：实际MySQL事务中最多一默认，清旧后失败能恢复，登录状态不承担地址数据持久化。

## Task 3：独立复核、全量验收及新增源码教学

**Consumes:** Task1/2实现与专项证据，现有全部回归用例。

**Produces:** 独立只读复核、完整打包、数据清理与教学交付。

- [x] 读取requesting-code-review与verification-before-completion技能，安排一次独立只读复核；只读审查不得改文件、启动服务或读取真实凭据，重点检查所有写入口先锁用户、默认转换、WHERE归属及失败恢复。只修复重要的本次问题。
- [x] 核对现有MySQL/RabbitMQ/Redis；不安装、不下载、不重新配置已可用服务。需要保持WSL Redis时启动独立keepalive并记录PID，结束只停止自己PID。
- [x] 运行一次完整离线package，实际确认0失败/错误/跳过和BUILD SUCCESS；303项是旧基线，按本轮输出报告新数量。通过后仅文档收尾不重复全量测试。
- [x] 比较开发原七表公开字段快照前后一致，新user_address0；测试八表0、自有触发器0、原随机Redis键及MQ测试拓扑清理；JAR不含实际凭据、测试配置、tools或测试类。
- [x] 不再尝试已被自动审批拒绝的临时Java启动；如无实际HTTP结果，明确验证是MockMvc+真实MySQL及既有Redis/RabbitMQ回归，不声称真实HTTP通过。
- [x] 新建docs/learning/14-address.md，展示实际新增SQL、请求/实体/响应、Controller→Service→Mapper/XML完整新链路，重点讲用户行锁、首次默认、默认切换原子性、删除规则；旧业务仅说明接入点，不展示测试源码。
- [x] README补010初始化顺序、五接口、默认/归属约定、结果和边界；设计及计划按真实完成状态更新，执行裁定留tools/address-progress.md；不改历史阶段结果。
- [x] git diff --check，停止本轮自有辅助进程，保留当前分支与全部改动；不提交、不推送、不进入多商品结算，先逐段讲解并等待用户继续。

Run：`.\tools\apache-maven-3.9.16\bin\mvn.cmd --offline --batch-mode --no-transfer-progress package`，日志tools/address-package.log。

Expected：完整旧功能与地址阶段都通过，数据/打包边界清楚，可按新增源码分段教学。

## 自检与交接

- 设计10条业务规则和10项验收标准分别落到Task1/2/3；Review Focus五项均有对应具体用例，默认允许0或1，不误承诺始终必须有默认。
- 类型/命名统一：内部UserAddress.defaultAddress与SQL is_default显式映射；响应AddressResponse.defaultAddress；接口方法参数均先userId再id，Mapper双条件参数明确命名。
- 用户锁只表达同一用户地址写入的统一顺序，不宣称数据库不同用户操作绝无短暂锁等待；不改现有订单锁顺序。
- 主助手直接实现的执行方式已确定，不再询问选择代理；计划审阅确认后使用executing-plans连续完成三任务，沿用不提交/不下载/不新建工作树约定。
