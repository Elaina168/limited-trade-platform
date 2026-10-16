# 第四阶段：登录、Session 与角色权限

## 本阶段目标

数据库保存账号与 BCrypt 密码摘要；Spring Security 完成密码验证、Session 身份保持、退出和角色授权。保留 CSRF 防护，不引入 JWT、Redis 会话或注册功能。

## 文件作用与阅读顺序

1. `sql/002_app_user.sql`：用户表。`username` 唯一，`password_hash` 保存摘要，`role` 保存 USER 或 ADMIN。
2. `user/AppUser.java`、`user/AppUserMapper.java` 与 `mapper/AppUserMapper.xml`：通过绑定参数按登录名读取账号。Java 文件位于 `src/main/java/com/elaina/limitedtrade/` 下，XML 位于 `src/main/resources/` 下。
3. `auth/DatabaseUserDetailsService.java`：实现框架的 UserDetailsService，把数据库账号适配为认证所需对象。
4. `auth/PlatformUserDetails.java`：继承框架 User，额外保存用户编号和单角色；将数据库 ADMIN 转为 ROLE_ADMIN 权限。
5. `auth/SecurityConfig.java`：PasswordEncoder 与过滤器链，声明公开路径、登录路径、角色限制和退出行为。
6. `auth/AuthController.java`、`auth/CurrentUserResponse.java`：只提供 CSRF Token 和当前身份查询。响应不包含密码或摘要。
7. `src/test/java/com/elaina/limitedtrade/auth/SecurityWorkflowTest.java`：完整数据库登录工作流与拒绝路径。

## 认证与授权分别做什么

认证回答“你是谁”：框架根据表单参数 username 查库，用 PasswordEncoder 验证提交的密码是否匹配摘要。BCrypt 不是可解密的密码存储；不自行比较明文，也不自行解密摘要。

授权回答“你能做什么”：数据库的 ADMIN 转换成 ROLE_ADMIN，配置的 hasRole("ADMIN") 自动使用 ROLE_ 前缀进行判断。普通 USER 登录成功，也没有商品管理权限。

## 登录请求与普通 Controller 请求的区别

登录采用 `application/x-www-form-urlencoded`，字段是 username 和 password。POST `/api/auth/login` 由框架的认证过滤器处理，所以没有手写 LoginController 或 LoginRequest。

请求先经过 Security 过滤器，再到 DispatcherServlet。登录过滤器读取凭证，经认证管理器调用数据库 UserDetailsService 和 BCrypt 验证器。成功后保存 SecurityContext，返回204；失败返回401。

服务器的 Session 保存登录身份，客户端的 JSESSIONID Cookie 用来关联该 Session。后续请求携带 Cookie，不需要每次重传密码。登录时框架更换 Session ID，退出时使 Session 失效并删除会话 Cookie。

## 为什么保留 CSRF

浏览器会自动携带 Session Cookie。服务端要求写请求额外携带 CSRF Token，防止其他站点借用浏览器已有登录态发起写操作。Token 不是登录凭证，也不赋予管理员权限。

先 GET `/api/auth/csrf`，保存 Cookie，并用响应中的 headerName 和 token 构造后续请求头。登录、商品维护和退出都需要 Token。登录成功后需要重新取 Token，再进行后续写操作。

未登录访问当前身份返回401；登录但角色不足返回403；缺少或无效 CSRF Token 同样返回403。因此排查403时，要分别核对身份、角色和 Token，不能只凭状态码断定原因。

## 本机演示

本机随机账号保存在 `config/local-accounts.txt`，结构是 admin、user 两组 username/password。文件被 Git 忽略，不展示到日志，不提交。数据库仅有 BCrypt 摘要。

先从项目根目录启动：

```powershell
java -jar .\target\limited-trade-platform-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1
```

另开 PowerShell，在项目根目录执行：

```powershell
$accounts = Get-Content -Raw -Encoding UTF8 .\config\local-accounts.txt | ConvertFrom-Json
$baseUrl = 'http://127.0.0.1:8080'

# 获取登录前的 Token，并保留 Cookie。
$token = Invoke-RestMethod "$baseUrl/api/auth/csrf" -SessionVariable session
$headers = @{}
$headers[$token.headerName] = $token.token

# 表单登录；只显示状态码，不输出密码。
Invoke-WebRequest "$baseUrl/api/auth/login" -Method Post -WebSession $session `
    -Headers $headers -ContentType 'application/x-www-form-urlencoded' `
    -Body @{username=$accounts.admin.username; password=$accounts.admin.password} |
    Select-Object StatusCode

# 读取当前登录身份。
Invoke-RestMethod "$baseUrl/api/auth/me" -WebSession $session

# 登录后重新获取 Token。
$token = Invoke-RestMethod "$baseUrl/api/auth/csrf" -WebSession $session
$headers = @{}
$headers[$token.headerName] = $token.token

# 退出；成功返回204。
Invoke-WebRequest "$baseUrl/api/auth/logout" -Method Post -WebSession $session `
    -Headers $headers | Select-Object StatusCode
```

最后在启动窗口按 Ctrl+C 停止应用。普通用户演示可把 `$accounts.admin` 换成 `$accounts.user`。

## 新环境准备账号

新环境先按 README 建库和执行用户表脚本。当前本机的随机密码文件不随仓库分发。

可以使用已有 JDK 的 JShell 和项目内已下载的库生成自己的摘要，无需另外安装工具。在 PowerShell 中从项目根目录进入 JShell：

```powershell
$crypto = '.\tools\maven-repository\org\springframework\security\spring-security-crypto\6.5.11\spring-security-crypto-6.5.11.jar'
$logging = '.\tools\maven-repository\org\springframework\spring-jcl\6.2.19\spring-jcl-6.2.19.jar'
& 'C:\Program Files\Java\jdk-21.0.10\bin\jshell.exe' --class-path "$crypto;$logging"
```

JShell 内输入下列代码，将占位内容替换成自己设置的密码；使用本机实际 JDK 路径：

```java
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
new BCryptPasswordEncoder().encode("替换为自己设置的密码");
/exit
```

在开发库中插入自己的用户名和刚生成的摘要，角色仅使用 ADMIN 或 USER。不要把明文密码放进 password_hash：

```sql
INSERT INTO app_user (username, password_hash, role)
VALUES ('自行设置的用户名', '替换为生成的完整BCrypt摘要', 'ADMIN');
```

## 本阶段验证与一次排查

全部38个测试通过：13个数据库认证工作流测试、23个模拟管理员身份的商品维护测试、商品查询和 ping 各1个。模拟角色测试验证商品业务，数据库登录测试验证真实认证，两者证据范围不同。

真实 HTTP 检查验证了两个账号登录204、身份查询200、Session ID 更换、普通用户管理访问403、缺少 Token 的管理员写入403、退出204及退出后身份查询401。管理员管理路由使用空请求对象得到400，表示通过权限检查进入了参数校验；这里没有将400称为写入成功。

最初“省略 Token”检查意外成功，原因在验证客户端：PowerShell 7.6.5 的 WebRequestSession 保留了之前设置的请求头。用同一空请求对象复现：带 Token 为400，省略 -Headers 仍为400，显式移除会话中的 Token 头后为403。应用代码无需修改。

如需刻意验证缺少 Token，先执行：

```powershell
$session.Headers.Remove($token.headerName)
```

首次误测产生的一条临时商品已单独删除，最终开发库保留原三条商品和两个学习账号。测试库两张表均为0行，确认测试数据回滚。凭证与测试依赖未进入 JAR，验证进程已停止。

## 集中复述

1. 为什么普通用户登录成功后，仍然不能维护商品？认证和授权分别检查了什么？
2. 登录之后，客户端靠什么让服务器识别同一个用户？CSRF Token 与这个机制有什么不同？

本阶段不推进订单。理解验收后等待“继续”。

## 官方参考

- [表单认证](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passwords/form.html)
- [CSRF 与登录后 Token 更新](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)
- [UserDetailsService](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/passwords/user-details-service.html)
