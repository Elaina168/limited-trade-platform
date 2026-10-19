# 第十七阶段设计：Redis Lua下单入口限流

2026-10-19。实现及验收完成：全量450项通过，当前进入新增源码学习。第十六阶段学习结束，用户明确继续下一阶段。沿用助手自行完成设计/实现/验收后详细讲新增源码，不逐段等待设计确认。

## 目标、范围与取舍

复用现有Redis及Spring Data Redis，不新增下载、依赖或数据库迁移。只保护POST /api/orders和POST /api/orders/checkout，两个入口按认证用户编号共享额度。默认启用，单用户首次合格请求起10秒窗口，最多放行5次；配置化enabled/max-requests/window-seconds/key-prefix。固定窗口可在相邻窗口边界出现短时突发，不称为严格滚动QPS或防DDoS方案；本阶段不添加热点商品新缓存。

候选比较：Java进程计数无法跨实例共享；Redis INCR/EXPIRE分开网络调用存在中间失败窗口；采用单键Lua脚本原子判断计数和TTL。只使用业务用户编号，不信任userId请求字段或IP转发头，不按requestId产生大量新键。

在OrderController两个创建方法中、调用事务Service之前执行限流。Spring Security认证与CSRF、JSON解析和@Valid在方法执行前完成；这些前置拒绝不消耗额度。进入方法的请求都计入尝试，包括后续业务409/404及幂等重试200，不因业务回滚退款限流额度。请求标识幂等约束保持，超限期间重试可能429，窗口恢复后仍返回原订单。

超限429，Retry-After为正整数秒。Redis连接失败/查询超时返回503，失败关闭，不使用本地放行或静默降级；脚本/数据损坏错误继续传播，不伪装成正常超限或放行。Redis调用发生在下单数据库事务之前，不会出现先扣库存才发现超限。支付、取消、收货、管理、查询及内部Service调用不受此入口限流。

测试profile默认关闭新限流，保证原正确性用例不被额度干扰；新专项明确启用并使用随机键前缀。关闭时完全不执行Redis脚本。生产配置仍默认启用；多实例只有连接同一Redis并使用相同前缀/参数时共享预算。

## 脚本协议与配置

资源redis/order-rate-limit.lua。KEYS[1]为key-prefix + ':user:' + userId；ARGV[1]为最大请求数，ARGV[2]为窗口毫秒。

不存在键：SET值1并带PX窗口，一条命令建立计数与过期，返回0。已有计数低于上限：INCR后返回0，不延长TTL。达到上限：不再递增，读取PTTL；缺失TTL属于外部Redis数据损坏，报脚本错误；正常返回floor(剩余毫秒/1000)+1，保证TTL为0毫秒时仍拒绝且Retry-After正数。保守整数等待可能比实际过期多不足或至多约1秒。拒绝请求不能滑动延长窗口。

OrderRateLimitProperties以@ConfigurationProperties与@Validated绑定；maxRequests/windowSeconds至少1，keyPrefix限制1—100位ASCII字母数字及冒号/下划线/连字符，不接受空或Unicode空白。布尔开关和参数启动后固定；不添加热更新或每商品规则。

RedisOrderRateLimiter复用StringRedisTemplate，加载DefaultRedisScript<Long>并声明返回Long；execute自动进行脚本缓存/EVALSHA机制。返回0放行，正数抛OrderRateLimitExceededException（429和Retry-After）。只捕获具体RedisConnectionFailureException、QueryTimeoutException转503；不宽泛catch。

## 文件与接入

新包com.elaina.limitedtrade.ratelimit：OrderRateLimitProperties、OrderRateLimitConfiguration（启用配置绑定）、RedisOrderRateLimiter、OrderRateLimitExceededException；资源Lua一份。OrderController增加构造依赖，在原createOrder/checkout调用Service之前各调用check(user.getId())。原订单/缓存/Outbox/地址/库存代码不改。

application.yml新增app.order-rate-limit；application-test.yml默认enabled=false。无013迁移脚本。保留十五/十六阶段全部改动，当前分支不另建工作树，不提交/推送，不启动被拒绝过的临时HTTP进程。

## 验收

API先失败再实现：两入口额度共享、两个用户独立、同一用户换Session额度不重置、伪造userId不改变限流键、429/Retry-After、拒绝前不改订单/库存/购物车/Outbox，幂等重试消费额度但不重复购买。缺登录/CSRF/非法JSON/校验失败不消费；GET与支付等旧入口不受额度影响；ADMIN购买同样限流。

真实Redis：多线程两个限流器实例共用键仅放行上限次数、计数不因拒绝增加、拒绝不续TTL、实际过期恢复、SCRIPT FLUSH不使用（不干扰共享实例）、无TTL/错误类型键不静默放行；实际关闭端口连接拒绝、可控超时只映射503，不进入数据库事务。禁用完全不访问Redis。测试仅删除本轮随机键，不FLUSHDB。

独立只读复核后全量offline package；开发十一表数据保持、测试十一表空、触发器和随机Redis/MQ资源无残留，JAR包含Lua、不含本机凭据/测试/工具。只结束本轮专用WSL保活进程，保留共享服务。不把本机小并发正确性测试说成吞吐基线或公网防护能力。
