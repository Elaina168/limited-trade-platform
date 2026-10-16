# 第三阶段：商品维护与参数校验

本文件记录第三阶段的设计。第四阶段已移除管理接口临时开关，替换为真实的 Session 认证与 ADMIN 角色授权，参见 `04-security.md`。

## 目标和业务规则

实现商品新增、资料修改、上下架，拒绝非法 HTTP 输入。

- 新增时设置初始库存，状态由后端确定为 `OFF_SALE`。
- 资料修改只改名称和价格，不改库存与状态。
- 上下架使用独立接口，重复设置相同状态也成功。
- 非法请求返回400，商品不存在返回404。
- 管理接口默认关闭，仅在测试中显式启用；当前没有管理员鉴权。

## 按这个顺序阅读实际代码

1. `src/main/java/com/elaina/limitedtrade/product/CreateProductRequest.java`：新增请求的三个字段和约束。
2. 同目录 `UpdateProductRequest.java`、`UpdateProductStatusRequest.java`：不同操作各自允许提交什么。
3. 同目录 `AdminProductController.java`：HTTP 路径、反序列化、校验与 Service 调用。
4. 同目录 `ProductService.java`：确定新增状态，判断更新是否找到商品，管理事务。
5. 同目录 `ProductMapper.java` 和 `src/main/resources/mapper/ProductMapper.xml`：方法与 SQL 的准确对应关系。
6. `src/test/java/com/elaina/limitedtrade/product/AdminProductApiTest.java`：用 HTTP 请求驱动真实 MySQL 写入与校验。

## 请求对象为什么和实体分开

`Product` 表示数据库商品，包含 `id`、名称、价格、库存和状态。请求对象只暴露当前操作允许输入的字段。

新增请求没有 `id` 和 `status`：编号由数据库生成，状态由业务确定。修改资料的请求没有库存和状态，更新 SQL 同样不包含它们，避免未来下单扣减库存时被资料修改覆盖。

请求对象使用 record，承接反序列化后不需要修改的数据。`Product` 改为普通 JavaBean：MyBatis 查询时可以按属性赋值，新增时也可以通过 setter 回填自增编号。这是新增能力引起的具体取舍，不是所有实体都必须可变。

## HTTP 边界的校验

- `@RequestBody`：把 JSON 反序列化为请求对象。
- `@Valid`：让框架检查请求对象上的约束；不合法时不进入 Service。
- `@NotBlank`：名称不能缺失或全是空白。
- `@Size(max = 100)`：名称长度上限；Java 字符单元与 Unicode 码点并非始终等价。
- `@NotNull`：价格、库存不能缺失；其他数值约束通常允许 null，需要组合使用。
- `@DecimalMin`、`@Digits`：价格必须大于0，满足 `DECIMAL(10,2)` 的精度范围。
- `@Min(0)`：库存允许0，不允许负数。
- `@Pattern`：商品状态只接受两个约定的大写字符串。
- `@Positive`：路径中的商品编号必须大于0。

库存用 `Integer`，使“未填写”表现为 null，而不是像 `int` 那样默认为0。Jackson 配置 `accept-float-as-int: false`，避免把1.5自动截断为1；整数溢出也会在反序列化时被拒绝。

这些校验发生在外部输入边界。Service 不再重复校验已通过框架检查的同一批字段。

## 自增主键如何回到 Java 对象

新增 SQL 的 `useGeneratedKeys="true" keyProperty="id"` 让 MyBatis 经由 JDBC 取得数据库生成的主键，再设置到传入对象的 `id` 属性。

所以 `insertProduct` 返回值是影响行数；真正的商品编号从 `product.getId()` 获得。不能把返回的1当成商品编号，也不能查询 `MAX(id)` 猜测编号。

## Service 的两个关键点

更新方法返回影响行数。本项目使用 Connector/J 默认的匹配行计数行为，商品存在且重复设置相同状态时仍返回成功；测试覆盖了连续下架两次。

更新影响0行时，本次编号没有匹配到商品，抛出404。更新和随后查询放在同一事务中；不是先查存在性再盲目更新。

`@Transactional` 生效的前提是通过 Spring 管理的 Service 代理调用；Controller 通过构造方法注入 Service，满足当前调用方式。

## 验证边界

本阶段全部26个测试通过。测试库中覆盖新增持久化与主键回填、修改不覆盖库存状态、上下架影响查询结果、重复设置状态、非法参数不落库、不存在的商品、管理接口默认关闭。

测试使用 MockMvc 和真实 MySQL，不是启动 HTTP 端口的管理接口测试。测试事务回滚，不往开发库写入临时商品。Mockito 动态代理提示与前一阶段相同，不影响当前 JDK 21 下的测试结果。

## 学习者集中复述

1. 新增 Mapper 返回1，与新增商品编号有什么区别？编号如何回到 Java 对象？
2. 为什么修改商品资料不直接接收 `Product`，而使用只有名称和价格的请求对象？

实现与本地测试已完成；理解验收由学习者复述后确认，不直接进入认证或交易阶段。
