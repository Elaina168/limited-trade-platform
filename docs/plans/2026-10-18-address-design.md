# 第十四阶段设计：用户收货地址

日期：2026-10-18。

状态：用户已确认设计和实施计划；2026-10-18实现及验收完成。专项35项、全量338项通过，独立只读复核无重要问题；两个数据库010各执行一次，无新增依赖。

## 1. 目标与已有依据

为登录用户提供地址簿，支持新增、查看列表、完整修改、删除及设置默认地址。衔接原高级路线中的“购物车与多商品结算”，为后续订单地址快照准备数据。

现有PlatformUserDetails提供getId()，OrderController及CartController从认证身份取用户编号。AppUserMapper.lockById(long)对应SELECT id FROM app_user WHERE id=#{id} FOR UPDATE，可作为同一用户地址写入的串行入口。

现有购物车及单商品订单不包含地址关联。本阶段只实现地址簿，不修改原订单请求或历史订单。后续结算在创建订单时复制地址内容；地址簿修改不应改变订单快照。

新增address模块属于新子系统，先审阅本设计，再制定实施计划。保留当前单Boot应用、MyBatis/MySQL、Session与CSRF，无新增依赖、安装或缓存；不提交、推送、合并或新建工作树。

## 2. 默认地址方案与取舍

| 方案 | 取舍 |
|---|---|
| 地址表保存默认标记，同一用户写入先锁用户行（采用） | 复用已核对的用户锁；设置默认、删除、首次新增可在本地事务内串行，便于后续稳定读取地址快照 |
| 用户表增加默认地址指针 | 默认项天然只有一个，但需要修改既有用户表，并处理用户与地址间的引用生命周期 |
| 默认地址独立表或生成列唯一约束 | 可在数据库表达更强的默认约束，但增加当前阶段的数据结构和讲解成本；当前不引入 |

采用方案的保证来自所有地址写入共同遵守“用户行锁→地址操作”的顺序，而非只在设置默认时加锁。数据库仍约束外键与默认标记值。不要建立UNIQUE(user_id,is_default)，否则每个用户连普通非默认地址也只能有一条。

用户锁使同一用户地址写入彼此串行，也可能等待该用户正在创建订单的原用户锁；其他用户不使用同一用户行锁，但数据库操作仍可能正常发生短暂锁等待。地址操作不访问库存锁，保持现有交易锁顺序。

## 3. 业务规则

1. 地址属于登录用户；userId只来自认证身份，不接受客户端指定归属。ADMIN也只能操作自己的地址，无跨用户管理入口。
2. 用户首次新增地址时，该地址自动设为默认。更准确地说：当该用户当前地址数量为0时，新地址为默认；后续新增地址均非默认。
3. 每个用户最多一个默认地址，允许暂时没有默认地址。用户显式设置其他地址为默认时，原默认项取消，新目标生效；重复设置同一地址正常成功。
4. 删除默认地址后，保留的其他地址不自动升为默认，由用户选择；若所有地址都已删除，下一次新增重新按首次地址规则处理。
5. 修改地址内容保留其默认标记，普通修改请求不能通过额外defaultAddress或userId改变默认状态/归属。
6. 查询、修改、删除、设置默认均限定本人。路径地址编号不存在或属于他人时，写操作统一404，不暴露他人的地址内容。
7. 创建/修改只接收收货人、手机号、省市区和详细地址，不接受id、userId、默认标记。内容不去重，允许同用户保存内容相同的两条地址。
8. 不设置未经需求支持的地址数量上限。列表暂不分页，默认地址在前，其余按id升序，空列表返回[]。
9. 地址写入不影响商品/库存/购物车/订单/Outbox，也不调用Redis失效或发送MQ。退出登录不删除地址，重新登录仍可读取。
10. 本阶段按中国大陆普通收货地址录入；省市区保存文本，不接行政区编码、地图或外部地址API，不保证这些文本构成真实可配送地址。

## 4. 输入与响应约定

以下是拟新增标识，不是已有字段。

创建及完整修改请求SaveAddressRequest：

| 字段 | 约束 |
|---|---|
| recipientName | 非空白，最长50字符，保存前strip() |
| phone | 非空白，严格匹配1[3-9][0-9]{9}；11位大陆手机号，不接受+86或空格，不代表已验证号码所有权 |
| province | 非空白，最长50字符，strip() |
| city | 非空白，最长50字符，strip() |
| district | 非空白，最长50字符，strip() |
| detailAddress | 非空白，最长200字符，strip() |

沿用@Valid、@NotBlank、@Size、@Pattern，在HTTP边界校验。完整PUT要求全部六字段，不实现局部PATCH。

AddressResponse返回id、recipientName、phone、province、city、district、detailAddress、defaultAddress。defaultAddress为boolean，Java映射与数据库is_default通过显式resultMap对应。不返回userId、密码或其他用户数据。

## 5. 拟新增表

迁移sql/010_user_address.sql，只对开发库和测试库各执行一次。创建空user_address表，不回填学习账号地址、不改原七表。

| 字段 | 类型/规则 |
|---|---|
| id | BIGINT AUTO_INCREMENT，主键 |
| user_id | BIGINT NOT NULL，外键app_user(id)，不级联删除 |
| recipient_name | VARCHAR(50) NOT NULL |
| phone | VARCHAR(11) NOT NULL |
| province/city/district | 各VARCHAR(50) NOT NULL |
| detail_address | VARCHAR(200) NOT NULL |
| is_default | TINYINT NOT NULL DEFAULT 0，CHECK值为0或1 |
| created_at | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP |
| updated_at | DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP |

建立(user_id,is_default,id)索引，支持按本人筛选及默认项更新。不保存订单字段、不增加用户表指针、不使用地址软删除；后续订单地址快照将独立保存具体内容，不依赖地址簿记录始终存在。

## 6. 接口

所有接口需登录，写入需CSRF。用户编号从@AuthenticationPrincipal PlatformUserDetails.getId()取得。

| 接口 | 行为与成功响应 |
|---|---|
| GET /api/addresses | 本人完整地址列表，200；默认优先，再id升序 |
| POST /api/addresses | 创建，201及AddressResponse；首次自动默认 |
| PUT /api/addresses/{id} | 完整修改内容，200及AddressResponse；保留默认状态 |
| DELETE /api/addresses/{id} | 删除本人地址，204；不存在/非本人404 |
| PUT /api/addresses/{id}/default | 无正文，设置为默认，204；不存在/非本人404 |

地址路径编号必须为正，非法输入400。写入发生SQL异常时继续传播，由本地事务回滚；不吞异常，不把失败默认为成功。未登录与缺CSRF处理沿用原安全过滤器，匿名写请求可能先因CSRF被拒绝。

## 7. 事务与并发调用链

```text
创建地址
  → Controller取用户编号并校验请求
  → AddressService开启事务
  → AppUserMapper.lockById(userId)
  → 查询当前用户地址数量
  → 首次标默认，其余非默认
  → INSERT回填id，返回响应

设置默认
  → 开启事务，先锁同一用户行
  → 按id与user_id确认目标归属
  → 不存在/非本人则404，尚未清除旧默认
  → 清除本人当前默认标记
  → 将目标本人地址标为默认
  → 提交，返回204

修改/删除
  → 开启事务，先锁同一用户行
  → UPDATE/DELETE始终带id与user_id
  → 没有匹配记录则404
  → 提交；修改不改默认字段，删除不自动指定替代项

查看列表
  → 单条SELECT限定user_id
  → ORDER BY is_default DESC,id ASC
  → 返回响应列表
```

锁用户的查询使用既有FOR UPDATE。所有地址写操作在取得用户锁后才读取或改变地址，因此并发首次创建只有一条成为默认；两个目标竞争默认时按事务执行顺序覆盖，最终最多一个，不承诺按网络发送时间决定胜者。

设置默认的“清旧→设新”处于同一事务，普通查询看不到尚未提交的中间状态。设新失败或事务回滚，旧默认状态一并恢复。默认设置与删除竞争：删除先提交则设置返回404；设置先提交再删除则最后允许无默认项，均不能使其他用户的默认项变化。

GET不取得用户写锁，一次查询读取当时可见的已提交结果；页面不预先获知未来结算时的地址快照，后续创建订单时需要重新校验归属并取快照。

## 8. 拟新增文件与现有改动

Java新增包com.elaina.limitedtrade.address：UserAddress.java、SaveAddressRequest.java、AddressResponse.java、AddressController.java、AddressService.java、AddressMapper.java；XML为mapper/AddressMapper.xml。

UserAddress是支持id回填的内部JavaBean，含userId和defaultAddress；AddressResponse用于公开响应，不直接序列化内部归属字段。Mapper明确提供按用户列表、计数、按id+用户查询、插入、按id+用户更新内容/删除、清除本人默认、设置本人目标默认。

现有仅需修改SecurityConfig，对/api/addresses及其子路径要求认证，保留CSRF；README补010与阶段记录。AppUserMapper及原订单代码复用，不改变原接口。新增学习稿docs/learning/14-address.md，仅讲新增代码和接入流程，不展示测试源码。

## 9. 验收标准

1. 创建回填编号，首次自动默认、后续非默认；输入去首尾空白，非法字段/手机号/路径拒绝且不写入。
2. 同用户可保存多个非默认地址；列表默认优先、按编号稳定排序，其他用户看不到这些地址。
3. 正常修改保持默认状态；伪造userId/defaultAddress不能改变归属与默认；非本人或不存在的修改/删除/设默认统一404。
4. 默认切换和同值重复设置正常；删除默认后无默认，保留的普通地址不自动升级；删除全部再新增会成为默认。
5. 多线程首次新增仅一默认；不同地址并发设置最终最多一默认；与删除竞争仅产生设计允许的结果，另一用户不受影响。
6. 设置默认确认目标归属发生在清旧之前；用真实事务回滚及受控SQL失败验证清旧后失败能恢复原默认，不只验证注解存在。
7. 使用真实认证对象及隔离用户验证登录/CSRF，ADMIN也只操作自己；退出再登录保留已提交地址。
8. 开发库原七表公开字段快照保持，新地址表0；测试八表清理。没有新增账户地址或修改旧业务数据。
9. 完整离线package包含既有回归；303项是上一阶段历史记录，本轮以实际结果为准。另做一次独立只读复核，重点看归属、锁顺序、默认转换与失败窗口。
10. 分开记录MockMvc、真实MySQL、完整集成回归和实际HTTP证据；不重试此前被拒绝的临时Java启动。不新增依赖/下载/部署，不提交推送。

## 10. 自检与下一步

默认项允许0或1、首次自动规则、删除默认的结果、手机号适用范围、归属条件、事务失败恢复及地址快照边界均已明确。本阶段不增加结算、订单状态、物流或前端。

已按“主助手直接实现、完成后一次只读复核”连续执行。当前停在新增源码教学，不进入多商品结算。
