# 当前项目与mall底座选择比较

日期：2026-11-07。目标：全栈开发＋Java Agent应用开发的实习/校招项目。

## 结论

建议保留当前交易后端和Agent，将后续主要投入转向Vue3/TypeScript前端、知识维护流程、部署和性能证据。mall作为业务模型和前端组织方式的参考；现在不建议整体改用mall作为唯一主项目。

理由不是已投入多少时间，而是可获得的新增收益与必须承担的工作不同：mall主要补齐商城业务广度和现成前端，不能直接补齐我们的Agent、知识管理、可靠消息和已有验证。目标若变为完整SKU商城、运营后台、会员体系和售后系统的二次开发，则mall底座有实际优势，可以重新决策。

## 本次证据范围

只做静态源码比较，没有安装项目依赖，没有运行mall或前端，没有执行数据库脚本。检查后端完整目录、各模块依赖、数据库结构、控制器与关键业务纵向链路；没有逐行审计每个生成Mapper和每个页面，不能声称完成全仓库正确性、安全性或性能认证。

固定源码：

- 当前项目：ba0de983bd2c42cd7c619bac5034e9dad0988e40。
- mall/master：dcaa93b3150352e5044708d7211b5bed0af4509f。
- mall-admin-web/master：81fc17e5a19f452bd854b106a9d59fa1ed5c7eac。
- mall-app-web/master：b1eb3cde1d74fbf62e72195bafd9322c8149f4c8。

参考源码位于被Git忽略的tools/research目录下三个以20261107结尾的目录；未执行下载源码中的脚本。现有业务源码、配置、数据库未修改。

## 覆盖的模块

mall后端有mall-admin、mall-common、mall-demo、mall-mbg、mall-portal、mall-search、mall-security七个Maven模块；这不等于mall-swarm的Spring Cloud微服务版。

数据库初始化文件定义76张表：商品18、订单8、会员/后台权限25、营销13、内容12。后端生产Java文件519个，其中mall-mbg生成模型/Mapper占230个。当前项目生产Java124个、测试Java79个。数量只用来说明理解和适配范围，不能表示代码质量或个人能力。

前端后台83个Vue文件、71个TypeScript文件；移动端30个Vue文件、30个TypeScript文件。后台包含测试示例页面，因此不应把组件文件数当成正式业务页面数。

已具体读取：下单确认、创建、付款、取消、库存锁定、超时消息、订单详情、SQL、商品创建与促销关联、ES搜索、支付宝入口、JWT过滤器、后台动态资源授权、会员身份、测试样例、Compose、前端HTTP拦截器、路由守卫、权限状态、用户状态及订单API。

## 能力比较

| 维度 | 当前项目 | mall源码 | 判断 |
|---|---|---|---|
| 商品 | 单商品库存、分类、上下架、限量活动 | 品牌、SPU/SKU、属性、会员价、阶梯价、满减、图文 | mall业务模型明显更丰富 |
| 交易 | 创建、购物车结算、模拟支付、取消、超时、发货和收货 | 完整商城确认单、运费/积分/优惠分摊、退货申请、操作记录、支付宝代码 | 若要完整商城二次开发，mall更合适；支付代码存在不等于我们已完成真实接入 |
| 并发/幂等 | 请求号复用校验、条件扣库存、状态条件更新、回补和专项验证 | 已读下单路径先检查库存，再读取SKU并更新lock_stock；付款与取消有不同实现 | 不能因mall更大而假定直接继承相同并发保证 |
| 消息 | 订单与Outbox同事务，后续发送/失败重试，扫描补偿 | 已读订单创建路径内直接发送延迟消息 | 若换底座又要保留当前可靠性，需要移植与重新验证 |
| 权限 | 一个用户模型、USER/ADMIN、Session＋CSRF | 后台管理员与商城会员分离，JWT、角色/菜单/资源动态权限 | mall后台授权更灵活，Agent可信身份也需重新接入 |
| 搜索/上传 | SQL商品筛选，未做图片上传管理 | 独立ES商品搜索模块、OSS/MinIO接口、富文本 | 是值得借鉴的真实功能，不必全部复刻 |
| AI | Spring AI、DeepSeek、RAG、本地BGE、MySQL会话、真实SSE、评估 | 当前检查的生产Java未发现Spring AI/LangChain4j导入，也未发现对应Agent模块 | 换底座不会免费得到Agent |
| 前端 | 同源原生JS，覆盖现有业务，已做限定范围浏览器验证 | 后台Vue3/TS/Pinia/Element Plus；移动端uni-app/Vue3/TS | mall前端有明显参考价值，但不能直接对接我们现有API |
| 部署 | 本机Nginx/双JVM等专项，Compose未实际验收 | 有Docker/Compose材料；检查文件中包含/mydata挂载、external_links和较旧镜像配置 | 两边都要按本机目标重新核对，不把文件存在当部署成功 |
| 测试 | 权限、事务、并发、消息、会话、流式、检索等分层测试和运行记录 | 本次快照的src/test下共5个Java文件，父POM默认skipTests=true；有DAO断言也有上下文测试 | 不能将上游现有测试当作我们接入后所有业务的回归证据；不是整体质量排名 |

## 三个影响迁移的实际代码差异

### 本人订单不能直接复用详情查询

mall的OmsPortalOrderServiceImpl.detail(Long orderId)通过orderMapper.selectByPrimaryKey(orderId)读取订单，再读取明细。该方法内未见对当前会员的归属校验。当前OrderService.getUserOrder(long userId, long id)调用findOwnedOrder(id,userId)，在SQL中限制身份。

因此给Agent接入mall时，应建立明确的本人订单查询边界，不能把普通详情方法直接注册成模型工具。这里是静态调用链观察，不是已复现的全站漏洞结论。

源码：[mall订单实现](https://github.com/macrozheng/mall/blob/dcaa93b3150352e5044708d7211b5bed0af4509f/mall-portal/src/main/java/com/macro/mall/portal/service/impl/OmsPortalOrderServiceImpl.java#L396)。

### 库存和消息语义不同

mall先锁定lock_stock，付款扣stock并释放锁定；当前项目创建订单就扣库存，取消/超时回补。这会影响工具返回、订单状态解释和规则资料，不能直接复制知识库。

mall的generateOrder()最后调用sendDelayMessageCancelOrder()，CancelOrderSender直接convertAndSend。当前项目saveOrder()写入Outbox，再由发布器处理。更换需要重新决定事务/消息失败窗口，不是仅改Mapper包名。

### 现代前端不代表可以照搬默认实现

后台src/stores/user.ts把loginParam.password赋给userInfo.password，整个store启用了persist:true。按源码配置意图，接入时应移除密码持久化。尚未通过浏览器实测存储结果。

后台HTTP层按CommonResult.code处理错误，附加JWT Authorization；当前使用Session/CSRF和真实HTTP状态码。新的SSE也需要独立的流读取路径，不能用普通JSON拦截器直接处理。

源码：[后台用户状态](https://github.com/macrozheng/mall-admin-web/blob/81fc17e5a19f452bd854b106a9d59fa1ed5c7eac/src/stores/user.ts)。

## 三种路线

| 路线 | 收益 | 主要成本 | 适用条件 |
|---|---|---|---|
| 保留后端，独立重做Vue3/TS前端 | 直接补当前全栈短板，已有Agent/交易链路可复用 | 学习组件、路由、状态、表单、类型、SSE和前端验收 | 当前目标最匹配，推荐 |
| 全面改为mall二次开发 | 较快获得丰富商城业务和现成前端 | 读懂商城领域、适配身份和schema、移植Agent、重写规则、可靠性修复与重新验收 | 确实需要SKU/完整促销/售后/复杂后台权限，且愿意以二次开发项目表述 |
| 当前项目归档，另开mall＋Agent项目 | 两种工程经验与原始证据都可保留 | 两套部署、前端和回归维护，注意力分散 | 有明确独立产品目标且愿意承担第二项目；当前不默认开展 |

没有足够证据给出“几天完成”的工期或性能对比，先不编造。

## 若换底座，必须重做/适配的清单

1. 后台管理员、商城会员的身份及Agent可信用户ID；禁止将模型参数当身份。
2. 现有会话与历史表的用户关联、创建/清空、并发资格和过期清理。
3. 订单/商品工具适配OmsOrder、SKU库存、状态数字、分页和必要字段脱敏。
4. 规则资料按mall实际库存、超时和售后语义重写，不能继续引用当前OrderService资料。
5. JSON/SSE路由、JWT携带、跨域/代理、断流取消、reset/done语义及页面。
6. 接入后的本人资源隔离、并发库存、重复支付/取消/消息、上游故障与会话回归。
7. 初始化、密钥外置、依赖版本、部署以及真实模型评估重新验收。

Java核心模型循环与本地向量适配器可复用，部分会话/流式逻辑可以迁移，但不代表整包零修改复制。mall使用Boot3.5.14/JDK17，当前为Boot3.5.16/JDK21；版本并非不可兼容，但要明确选定统一构建基线。

## 推荐后续投入

先完善当前项目的可复现部署与演示；按用户前端学习节奏做Vue3＋TypeScript的新前端，覆盖现有商品、订单、后台和助手，而不是移入所有不具备接口的商城页面。再补规则知识维护/版本/索引更新与可复核评估。选一个有业务价值的扩展形成独立贡献，例如商品图片管理，或带政策依据与权限边界的售后咨询。

避免只重命名、换配色或给通用商城接聊天框。若采用mall代码，应保留许可证与相关声明，并明确写“基于mall二次开发”，把个人贡献写成具体新增能力、验证和结果。当前三个参考仓库均有Apache-2.0许可材料；不是说不能商用或不能写简历。

本报告只给决策依据，不修改路线、不启动迁移、不自动提交推送。
