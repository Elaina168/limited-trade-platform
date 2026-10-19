# 第二十阶段：用户注册

## 1. 请求链及文件

POST /api/auth/register → SecurityConfig允许匿名但验证CSRF → RegisterRequest读取JSON及校验用户名 → AuthController.register → RegistrationService.register → AppUserMapper.insertUser → 查询新账号 → CurrentUserResponse。

新增RegisterRequest、RegistrationService；修改AuthController、SecurityConfig、AppUserMapper及其XML。复用既有app_user、BCryptPasswordEncoder、DatabaseUserDetailsService、CurrentUserResponse和登录流程。不新增表、依赖或登录机制。

## 2. RegisterRequest

```java
public record RegisterRequest(
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "[A-Za-z0-9_]{3,50}", message = "用户名须为3至50位字母、数字或下划线")
    String username,
    String password) {
}
```

请求只绑定username/password，不接受role、id、passwordHash作为业务字段。用户名原样保存，不去空白或转小写；是否重名由数据库现有用户名唯一键及排序规则决定。

password在Service用通用错误提示校验，避免默认字段校验异常把被拒绝密码原文带入日志。要求非空白、至少8个Java字符、UTF-8字节长度不超过72；BCrypt约束的是字节，24个“密”为72字节可用，25个为75字节拒绝。密码保持原样，不strip，不做静默截断。

## 3. Controller和Security

AuthController注入RegistrationService，新方法：

```java
@PostMapping("/register")
@ResponseStatus(HttpStatus.CREATED)
public CurrentUserResponse register(@Valid @RequestBody RegisterRequest request) {
    return registrationService.register(request);
}
```

类上的/api/auth前缀与方法/register组合成完整路径。@RequestBody把JSON转成record，@Valid在进入方法前校验用户名；成功201，返回id/username/role，不返回AppUser以免序列化passwordHash。

SecurityConfig只新增POST /api/auth/register的permitAll，不放开整个/api/auth/**，也不关闭CSRF。匿名用户先GET /api/auth/csrf取得Session及token，再携带相同Session和CSRF头发注册JSON。注册不会向SecurityContext写入认证信息，仍须POST /api/auth/login登录；登录继续使用已有表单参数username/password。

## 4. Service如何保存账号

```java
String hash = passwords.encode(password);
try {
    users.insertUser(request.username(), hash);
} catch (DuplicateKeyException duplicate) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
}
var user = users.findByUsername(request.username());
return new CurrentUserResponse(user.id(), user.username(), user.role());
```

passwords注入现有PasswordEncoder Bean，实际为BCryptPasswordEncoder。encode产生带独立随机盐的摘要，原密码不保存。相同密码注册两次，摘要通常不同；登录时已有认证框架用matches比较，不能重新encode后直接比较字符串。

没有先查用户名再插入，因为两个并发请求可能都查不到。app_user的uk_app_user_username作为唯一裁决，只有一个INSERT成功；另一个抛DuplicateKeyException映射409。只捕获该异常，其他数据库错误继续传播，不伪装为重名或成功。

方法使用@Transactional，插入和查询结果同事务。既有AppUser是不可变record，本次没有为生成主键改动它：插入成功后复用findByUsername读回编号，再构造安全响应。

## 5. Mapper和角色

```java
void insertUser(@Param("username") String username,
                @Param("passwordHash") String passwordHash);
```

对应XML：

```sql
INSERT INTO app_user(username, password_hash, role)
VALUES (#{username}, #{passwordHash}, 'USER')
```

两个@Param名字对应两个#{...}占位符，由MyBatis参数绑定。id由AUTO_INCREMENT产生，created_at使用数据库默认时间。角色直接写SQL常量USER，前端提交role=ADMIN不会影响插入结果。已有登录框架再把USER转成ROLE_USER权限，因此新账号无法调用管理员接口。

## 6. 验证

新增RegistrationApiTest21项、RegistrationIntegrationTest4项均先失败，再实现通过；加原SecurityWorkflowTest13项，专项38项通过。覆盖真实CSRF、固定角色、无敏感响应、密码摘要和盐、用户名/密码边界、8个同名并发仅1成功、注册后登录下单退出重登录、重复注册不覆盖账号、非重名数据库错误传播和恢复。

接口证据来自MockMvc+真实MySQL，复用原Session登录和PasswordEncoder；不作为真实HTTP部署、真实支付或生产压测证明。最终全量验证和清理结果见README与tools/registration-progress.md。

完整离线package530项通过，0失败/错误/跳过，46个本轮测试类XML交叉核对一致。独立只读复核无Critical/Important/Minor。开发十四表保持，测试十四表空且无触发器，Redis随机键和MQ测试拓扑无残留，JAR内容与本轮编译一致且不含本机凭据/配置/工具/测试资源。没有新增迁移、依赖、工作树、提交或推送。
