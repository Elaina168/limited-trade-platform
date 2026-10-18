# 第十一阶段：可靠消息与订单超时

## 这次新增了什么

下单仍同步写 MySQL、扣库存，仍以数据库订单的15分钟支付期限为准。这次给订单关闭增加一条可重试的消息链路：先把“需要发送超时检查消息”与订单一起存进数据库，再由独立任务发给 RabbitMQ，等待队列到期后消费者复用原关单方法。原数据库扫描继续兜底。

阅读顺序：先看下单新增点和本地消息表，再看投递任务、发送确认、队列配置、消费者。旧业务只交代走到新增点之前的过程；本文不展示测试源码。

```text
下单请求
  → 原用户锁、幂等检查、活动与商品扣减
  → 插入订单
  → 新增：插入 PENDING 的 order_timeout_outbox
  → 同一 MySQL 事务提交

投递任务（每轮结束后等待3秒，最多20条）
  → 查到期 PENDING 候选编号
  → 独立事务锁定一条事件，其他投递者跳过该锁
  → 发送到等待队列，等待 Broker 确认
  → ACK且未退回：事件记 SENT
  → AMQP失败：仍 PENDING，记录错误和下次尝试时间

等待队列（900000毫秒）
  → 到期死信转发到 ready 队列
  → 消费者校验消息，调用原 closeExpiredOrder
  → 原SQL判断待支付且已到期，状态转换成功才归还库存/名额
  → 方法正常返回，容器确认消费
  → 消费失败最多3次，仍失败则进入 failed 队列

原扫描（每轮结束后等待30秒，100条）
  → 继续调用同一个 closeExpiredOrder
```

## 环境和依赖

本机实际运行 RabbitMQ 4.3.6、Erlang/OTP 28.5.0.8。RabbitMQ位于 tools/rabbitmq_server-4.3.6，用户实际安装的 Erlang 位于 tools/Erlang OTP。启动脚本按实际路径设置当前进程环境，不注册服务、不永久修改PATH。运行数据和日志位于 tools/rabbitmq-runtime。

pom.xml 新增 spring-boot-starter-amqp，版本由 Spring Boot 3.5.16 管理：Spring AMQP 3.2.12、Rabbit Java Client 5.25.0、Spring Retry 2.0.13。前者提供 RabbitTemplate、监听容器和配置集成，客户端负责 AMQP 连接，Retry 实现消费端的有限重试。

本机 config/rabbitmq.properties 保存地址、端口、用户、口令、开发/测试虚拟主机；它被Git忽略，不进入JAR。可公开模板为 config/rabbitmq.properties.example。开发虚拟主机 limited-trade，测试 limited-trade-test；专项测试还使用随机队列前缀，避免操作开发队列。

## 1. 下单事务中新增的登记点

前面的流程沿用旧代码：锁用户、按 userId/requestId 检查原订单、锁定活动和商品、扣库存与名额、创建 TradeOrder。幂等重试仍提前返回，不走下面的新增登记。


```java
        orderMapper.insertOrder(order);
        // 订单与发送意图同事务保存；幂等提前返回不会再登记事件。
        OrderTimeoutOutbox event = new OrderTimeoutOutbox();
        event.setOrderId(order.getId());
        event.setExpiresAt(order.getExpiresAt());
        event.setStatus("PENDING");
        event.setAttemptCount(0L);
        event.setNextAttemptAt(now);
        event.setCreatedAt(now);
        outboxMapper.insertEvent(event);
        productCache.evictAfterCommit();
```


insertOrder 的自增主键回填到 order.id 后，事件保存同一个订单编号和 expiresAt。这里的 now 是原下单流程取到的时间，不重新生成另一套支付期限。

OrderService 注入新增 OrderTimeoutOutboxMapper。它与原 OrderMapper 使用同一数据源和 Spring 事务；任何一个 INSERT 失败，都回滚订单、库存、活动名额。下单线程不连接 RabbitMQ。即使消息功能关闭，仍登记发送意图，方便恢复后继续投递。

productCache.evictAfterCommit() 是上一阶段已有代码：它登记提交后的缓存失效。缓存删除若在提交后失败，订单和事件可能都已提交；客户端使用原 requestId 重试会得到原订单，也不会再次登记事件。

## 2. 本地消息表：保存发送意图

本地消息表就是 Outbox，位于 MySQL。它不是 RabbitMQ 的等待队列。PENDING 表示还需要尝试投递；SENT 仅表示 Broker 已确认接收且本次没有路由退回，不能据此认定订单已关闭。


`sql/007_order_timeout_outbox.sql`：

```sql
-- 在006之后，对开发库和测试库各执行一次；不回填旧订单、不修改原数据。
CREATE TABLE order_timeout_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '超时事件编号',
    order_id BIGINT NOT NULL COMMENT '关联订单，一单只登记一个超时事件',
    expires_at DATETIME NOT NULL COMMENT '原订单支付截止时间快照',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING待投递、SENT已获Broker接收确认',
    attempt_count BIGINT NOT NULL DEFAULT 0 COMMENT '已提交保存的发送尝试次数',
    next_attempt_at DATETIME NOT NULL COMMENT '下一次允许发送的时间',
    last_error VARCHAR(500) NULL COMMENT '最近一次发送错误摘要',
    created_at DATETIME NOT NULL COMMENT '登记时间',
    sent_at DATETIME NULL COMMENT '标记发送成功的时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_timeout_outbox_order (order_id),
    KEY idx_order_timeout_outbox_pending (status, next_attempt_at, id),
    CONSTRAINT fk_order_timeout_outbox_order FOREIGN KEY (order_id) REFERENCES trade_order (id),
    CONSTRAINT chk_order_timeout_outbox_status CHECK (status IN ('PENDING', 'SENT')),
    CONSTRAINT chk_order_timeout_outbox_attempt CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单超时消息发送意图';
```


- order_id 唯一：一张订单只登记一个超时事件，与应用层幂等共同约束。
- expires_at：原订单截止时间的快照，用于消息追踪；消费者关单仍查询数据库订单条件。
- attempt_count：已随事务提交保存的尝试次数，不是完整物理发送审计；发送后本地回滚，该次可能不计入。
- next_attempt_at：失败退避后允许再次发送的时间；创建时设为 now，即首次可立即投递。
- last_error：最近一次 AMQP 错误的前500字符；成功时清空。
- (status,next_attempt_at,id)：服务查询待发送候选所使用的索引。
- 外键：事件必须关联真实订单，删除隔离测试订单前需要先删除其事件。

本机已对开发库、测试库各执行一次007；不要再次执行。旧订单不回填事件，由原扫描处理。


`src/main/java/com/elaina/limitedtrade/order/message/OrderTimeoutOutbox.java`：

```java
package com.elaina.limitedtrade.order.message;

import java.time.LocalDateTime;

public class OrderTimeoutOutbox {

    private Long id;
    private Long orderId;
    private LocalDateTime expiresAt;
    private String status;
    private Long attemptCount;
    private LocalDateTime nextAttemptAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Long attemptCount) { this.attemptCount = attemptCount; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
}
```


这是可变 JavaBean：MyBatis 用 setter 填充查询结果，也用 setId 回填自增编号。Long 对应 BIGINT，LocalDateTime 对应本项目不含时区的 DATETIME，仍沿用现有业务时间约定。


`src/main/java/com/elaina/limitedtrade/order/message/OrderTimeoutOutboxMapper.java`：

```java
package com.elaina.limitedtrade.order.message;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderTimeoutOutboxMapper {

    int insertEvent(OrderTimeoutOutbox event);

    List<Long> findPendingIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    OrderTimeoutOutbox lockPending(@Param("id") long id, @Param("now") LocalDateTime now);

    int markSent(@Param("id") long id, @Param("attemptedAt") LocalDateTime attemptedAt);

    int markFailed(@Param("id") long id, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                   @Param("error") String error);
}
```


`src/main/resources/mapper/OrderTimeoutOutboxMapper.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.elaina.limitedtrade.order.message.OrderTimeoutOutboxMapper">
    <resultMap id="event" type="com.elaina.limitedtrade.order.message.OrderTimeoutOutbox">
        <id property="id" column="id"/>
        <result property="orderId" column="order_id"/>
        <result property="expiresAt" column="expires_at"/>
        <result property="status" column="status"/>
        <result property="attemptCount" column="attempt_count"/>
        <result property="nextAttemptAt" column="next_attempt_at"/>
        <result property="lastError" column="last_error"/>
        <result property="createdAt" column="created_at"/>
        <result property="sentAt" column="sent_at"/>
    </resultMap>

    <insert id="insertEvent" useGeneratedKeys="true" keyProperty="id">
        INSERT INTO order_timeout_outbox (order_id, expires_at, status, attempt_count,
                                          next_attempt_at, created_at)
        VALUES (#{orderId}, #{expiresAt}, #{status}, #{attemptCount},
                #{nextAttemptAt}, #{createdAt})
    </insert>

    <select id="findPendingIds" resultType="long">
        SELECT id FROM order_timeout_outbox
        WHERE status='PENDING' AND next_attempt_at &lt;= #{now}
        ORDER BY next_attempt_at, id LIMIT #{limit}
    </select>

    <select id="lockPending" resultMap="event">
        SELECT id, order_id, expires_at, status, attempt_count, next_attempt_at,
               last_error, created_at, sent_at
        FROM order_timeout_outbox
        WHERE id=#{id} AND status='PENDING' AND next_attempt_at &lt;= #{now}
        FOR UPDATE SKIP LOCKED
    </select>

    <update id="markSent">
        UPDATE order_timeout_outbox
        SET status='SENT', attempt_count=attempt_count+1, sent_at=#{attemptedAt}, last_error=NULL
        WHERE id=#{id}
    </update>

    <update id="markFailed">
        UPDATE order_timeout_outbox
        SET attempt_count=attempt_count+1, next_attempt_at=#{nextAttemptAt}, last_error=#{error}
        WHERE id=#{id}
    </update>
</mapper>
```


@Param 将 Java 参数名明确绑定为 XML 中的 #{id} 等参数。resultMap 显式列出数据库列与 Java 属性的对应关系。#{...} 使用预编译参数。

findPendingIds 只选候选，不保证获得执行权；它按 next_attempt_at、id 排序，最多一批。lockPending 在真正发送之前再次判断 PENDING 和时间，并执行 FOR UPDATE SKIP LOCKED：锁住这一行；若它已被另一事务锁住，就跳过并返回 null，而非等待同一事件重复发送。已发送、未到重试时间或不存在的记录也不取得执行权。

markSent 与 markFailed 均在持有事件行锁的同一事务中执行，SQL异常直接向外传播。行锁保证本事务内目标存在，不额外为不可能的行消失建立回退逻辑。这里不锁用户、活动、商品，网络等待只占用事件行。

## 3. 消息正文：只携带必要字段


`src/main/java/com/elaina/limitedtrade/order/message/OrderTimeoutMessage.java`：

```java
package com.elaina.limitedtrade.order.message;

import java.time.LocalDateTime;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderTimeoutMessage(@NotNull @Positive Long eventId,
                                  @NotNull @Positive Long orderId,
                                  @NotNull LocalDateTime expiresAt) {
}
```


record 是 Java 的简洁数据载体，自动提供构造器、equals/hashCode/toString 和 eventId()、orderId()、expiresAt() 访问方法。字段不可通过 setter 修改。

eventId 是稳定业务事件编号。同一事件重投保持相同 eventId；orderId 指向要检查的订单；expiresAt 用于追踪。@NotNull 和 @Positive 描述外部消息的合法性，消费者显式调用 Validator 时才执行校验。

## 4. 发布者：为什么发出后还要等待确认


`src/main/java/com/elaina/limitedtrade/order/message/OrderTimeoutMessageSender.java`：

```java
package com.elaina.limitedtrade.order.message;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OrderTimeoutMessageSender {
    private final RabbitTemplate template;
    private final String exchange;

    public OrderTimeoutMessageSender(RabbitTemplate template,
            @Value("${app.order-message.name-prefix:limited-trade}") String prefix) {
        this.template = template;
        this.exchange = prefix + ".order-timeout.exchange";
    }

    public void send(OrderTimeoutMessage message) {
        // 每次物理发送独立关联；业务事件编号留在正文中。
        CorrelationData correlation = new CorrelationData();
        template.convertAndSend(exchange, "order-timeout.wait", message, converted -> {
            converted.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return converted;
        }, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(2, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new AmqpException("订单超时消息未获确认：" + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new AmqpException("订单超时消息不可路由：" + correlation.getReturned().getReplyText());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待订单超时消息确认时被中断", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AmqpException("等待订单超时消息确认失败", exception);
        }
    }
}
```


RabbitTemplate 是 Spring 提供的发送入口。convertAndSend 先用配置的JSON转换器编码 record，再向指定交换机和路由键发送。MessagePostProcessor 这个 lambda 在转换后设置 PERSISTENT，让消息使用持久化投递模式。

new CorrelationData() 每次产生不同 UUID，关联本次物理发送的 Broker 回执。不能直接重用 eventId 作为所有重试的关联编号，否则不同发送尝试的回执可能混淆。正文 eventId 稳定，确认关联编号每次不同，两者职责不同。

getFuture().get(2, TimeUnit.SECONDS) 最多等待2秒获取确认。必须同时满足 confirm.isAck() 和 correlation.getReturned()==null。ACK可能只表明交换机收到，但没有匹配队列时，mandatory会让消息退回；因此只看ACK会把不可路由消息误标成功。当前Spring AMQP保证 returned 在确认future完成前写入，可在等待完成后检查。

NACK、退回、确认超时和确认future失败统一表现为 AMQP 异常。同步连接/发送失败也会向外抛 AMQP 异常。线程中断单独恢复中断标记并抛出 IllegalStateException，不把停止任务的信号伪装成普通网络重试。

持久化队列、持久化消息和入口确认增强可靠性，但不等于端到端永不丢失；仍保留数据库扫描。

## 5. 单条投递事务与重试


`src/main/java/com/elaina/limitedtrade/order/message/OrderTimeoutOutboxService.java`：

```java
package com.elaina.limitedtrade.order.message;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.amqp.AmqpException;
import java.time.LocalDateTime;

@Service
public class OrderTimeoutOutboxService {
    private final OrderTimeoutOutboxMapper mapper;
    private final OrderTimeoutMessageSender sender;

    public OrderTimeoutOutboxService(OrderTimeoutOutboxMapper mapper, OrderTimeoutMessageSender sender) {
        this.mapper = mapper;
        this.sender = sender;
    }

    @Transactional
    public boolean publishOne(long id) {
        OrderTimeoutOutbox event = mapper.lockPending(id, LocalDateTime.now().withNano(0));
        if (event == null) {
            return false;
        }
        try {
            sender.send(new OrderTimeoutMessage(event.getId(), event.getOrderId(), event.getExpiresAt()));
        } catch (AmqpException exception) {
            long delay = Math.min(60, 5L << Math.min(event.getAttemptCount(), 4));
            String error = exception.toString();
            mapper.markFailed(id, LocalDateTime.now().withNano(0).plusSeconds(delay),
                    error.substring(0, Math.min(500, error.length())));
            return false;
        }
        mapper.markSent(id, LocalDateTime.now().withNano(0));
        return true;
    }
}
```


@Transactional 由 Spring 代理开启事务。调度器通过另一个 Bean 调用 publishOne，事务才能生效；不能在同一个类里直接调用自己的事务方法来期望代理拦截。

处理顺序是“锁事件 → 发送并等待确认 → 更新本地状态 → 提交”。未获得执行权返回 false。发送成功后标 SENT；可识别的 AMQP 失败被明确记入数据库，仍 PENDING，并返回 false。这里的 false 包括没有执行和已记录发送失败，不表示订单失败。

5L << Math.min(attemptCount,4) 是左移，相当于 5乘2的对应次方；外层 Math.min(60,...) 把等待时间限制为60秒。使用发送前已记录次数0、1、2、3、4...，得到5、10、20、40、60、60...秒。达到60秒后继续重试，不永久丢弃发送意图。

catch只包围sender.send。MySQL锁、状态更新或提交失败向外抛出，当前单条事务回滚；线程中断也不会被这里的AMQP catch吞掉。即使Broker已收到，本地SENT写入失败仍会在下一轮重发，这是可靠消息必须接受的重复窗口。

## 6. 后台任务：查候选与执行权分开


`src/main/java/com/elaina/limitedtrade/order/message/OrderTimeoutOutboxScheduler.java`：

```java
package com.elaina.limitedtrade.order.message;

import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderTimeoutOutboxScheduler {
    private final OrderTimeoutOutboxMapper mapper;
    private final OrderTimeoutOutboxService publisher;
    private final boolean enabled;

    public OrderTimeoutOutboxScheduler(OrderTimeoutOutboxMapper mapper, OrderTimeoutOutboxService publisher,
            @Value("${app.order-message.enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.publisher = publisher;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.order-message.publish-delay-ms:3000}")
    public void publishIfEnabled() {
        if (enabled) {
            publishPending();
        }
    }

    public void publishPending() {
        for (Long id : mapper.findPendingIds(LocalDateTime.now().withNano(0), 20)) {
            publisher.publishOne(id);
        }
    }
}
```


@Scheduled(fixedDelayString=...) 按上一轮结束后的延迟调度，默认3000毫秒，不是无论上一轮是否结束都严格每3秒启动。一次读取20个候选，逐个跨Bean调用publishOne，每条各自提交；后面某条失败，不会回滚前面已提交条目。数据库故障会在任务日志中暴露，后续轮次仍会再次扫描。

publishIfEnabled 是定时入口，受 app.order-message.enabled 控制；publishPending 保留手动入口，便于隔离验证。应用中多实例可以看到同一批候选，但真正锁定事件的事务才发送。

## 7. RabbitMQ配置：等待、处理、失败三条队列


`src/main/java/com/elaina/limitedtrade/order/message/OrderTimeoutRabbitConfiguration.java`：

```java
package com.elaina.limitedtrade.order.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OrderTimeoutRabbitConfiguration {
    @Bean
    Declarables orderTimeoutTopology(@Value("${app.order-message.name-prefix:limited-trade}") String prefix,
            @Value("${app.order-message.wait-ttl-ms:900000}") int ttl) {
        String waiting = prefix + ".order-timeout.wait";
        String ready = prefix + ".order-timeout.ready";
        String failed = prefix + ".order-timeout.failed";
        var entrance = new DirectExchange(prefix + ".order-timeout.exchange");
        var expired = new DirectExchange(prefix + ".order-timeout.dlx");
        var failures = new DirectExchange(prefix + ".order-timeout.failed.exchange");
        return new Declarables(entrance, expired, failures,
                QueueBuilder.durable(waiting).ttl(ttl).deadLetterExchange(expired.getName())
                        .deadLetterRoutingKey("order-timeout.ready").build(),
                QueueBuilder.durable(ready).deadLetterExchange(failures.getName())
                        .deadLetterRoutingKey("order-timeout.failed").build(),
                QueueBuilder.durable(failed).build(),
                new Binding(waiting, Binding.DestinationType.QUEUE, entrance.getName(), "order-timeout.wait", null),
                new Binding(ready, Binding.DestinationType.QUEUE, expired.getName(), "order-timeout.ready", null),
                new Binding(failed, Binding.DestinationType.QUEUE, failures.getName(), "order-timeout.failed", null));
    }

    @Bean
    Jackson2JsonMessageConverter orderTimeoutMessageConverter(ObjectMapper mapper) {
        var converter = new Jackson2JsonMessageConverter(mapper, "com.elaina.limitedtrade.order.message");
        // 若外部声明类型头，必须经过受信任类型检查，不能被方法参数推断覆盖。
        converter.setTypePrecedence(Jackson2JavaTypeMapper.TypePrecedence.TYPE_ID);
        return converter;
    }
}
```


@Configuration 声明配置类，@Bean 把返回对象交给Spring。Declarables 将交换机、队列和绑定作为一组交给RabbitAdmin声明；DirectExchange按路由键精确匹配。durable 表示队列持久化，交换机默认持久化。

以默认前缀 limited-trade 为例：

| 交换机 | 路由键 | 目标队列 | 后续行为 |
|---|---|---|---|
| limited-trade.order-timeout.exchange | order-timeout.wait | limited-trade.order-timeout.wait | TTL900000毫秒后死信到dlx |
| limited-trade.order-timeout.dlx | order-timeout.ready | limited-trade.order-timeout.ready | 交给消费者；拒绝回队后死信到failed.exchange |
| limited-trade.order-timeout.failed.exchange | order-timeout.failed | limited-trade.order-timeout.failed | 保留失败消息，等待修复和重投 |

没有消费者监听wait队列。消息在wait内等待，TTL到期后由Broker死信转发；ready才由Java消费者处理。failed也没有自动业务消费者，防止异常消息无限热循环。

TTL从消息实际进入等待队列开始计时。如果订单创建后20秒才成功投递，再等待15分钟，则MQ触发晚于原支付截止时间；SQL支付期限没有因此延长。数据库扫描仍按原expires_at关闭订单，队列积压或死信转发故障也由扫描覆盖。

转换器复用Boot现有ObjectMapper，所以保留LocalDateTime模块等项目配置。受信任包限制JSON类型头的解析范围；TYPE_ID使消息显式携带的类型头先接受信任检查，避免被监听方法参数类型推断直接覆盖。没有类型头时可以使用方法参数推断。消息最终仍需转成 OrderTimeoutMessage 并通过字段校验。

## 8. 应用参数：确认、消费重试和总开关


`src/main/resources/application.yml`：

```yaml
spring:
  config:
    import:
      - file:./config/db.properties
      - file:./config/rabbitmq.properties
  application:
    name: limited-trade-platform
  datasource:
    url: ${db.url}
    username: ${db.username}
    password: ${db.password}
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      connect-timeout: 2s
      timeout: 2s
      repositories:
        enabled: false
  jackson:
    deserialization:
      accept-float-as-int: false
  rabbitmq:
    host: ${mq.host}
    port: ${mq.port}
    username: ${mq.username}
    password: ${mq.password}
    virtual-host: ${mq.virtual-host}
    connection-timeout: 2s
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
    listener:
      simple:
        acknowledge-mode: auto
        prefetch: 1
        default-requeue-rejected: false
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1s
          multiplier: 2

mybatis:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true

server:
  port: 8080

app:
  scheduling:
    enabled: true
  order-message:
    enabled: true
    name-prefix: limited-trade
    wait-ttl-ms: 900000
  product-cache:
    enabled: true
    key: limited-trade:product:on-sale:v1
```


publisher-confirm-type: correlated 开启每次发送对应的异步回执；publisher-returns 与 mandatory 共同让不可路由返回可见。connection-timeout限制建立连接时的等待，sender中的2秒是等待确认的时间，不能把它解释为整个投递必定2秒内结束。

acknowledge-mode: auto 表示监听调用正常返回后由容器确认，不是网络收到就立刻确认。prefetch: 1 限制消费者预取。消费重试 max-attempts: 3 包含第一次，initial-interval: 1s 与 multiplier: 2 得到1秒、2秒间隔。失败后拒绝且不回原队列，由ready的死信配置送入failed。

这里有两层重试：生产端通过MySQL的next_attempt_at持续重试；消费端对已到ready的消息有限重试。二者控制不同失败阶段。

app.scheduling.enabled 控制定时设施；app.order-message.enabled 控制定时投递及监听器自动启动；app.order-timeout.enabled（默认true）控制原数据库定时扫描。关闭消息功能不停止下单登记Outbox。测试profile统一关闭自动调度与消息自动启动，消息专项再单独开启监听并使用随机前缀。

## 9. 消费者：复用原关单事务


`src/main/java/com/elaina/limitedtrade/order/message/OrderTimeoutConsumer.java`：

```java
package com.elaina.limitedtrade.order.message;

import com.elaina.limitedtrade.order.OrderService;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolationException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class OrderTimeoutConsumer {
    private final OrderService orders;
    private final Validator validator;

    public OrderTimeoutConsumer(OrderService orders, Validator validator) {
        this.orders = orders;
        this.validator = validator;
    }

    @RabbitListener(id = "orderTimeoutConsumer",
            queues = "${app.order-message.name-prefix:limited-trade}.order-timeout.ready",
            autoStartup = "${app.order-message.enabled:true}")
    public void onTimeout(OrderTimeoutMessage message) {
        var violations = validator.validate(message);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        orders.closeExpiredOrder(message.orderId(), LocalDateTime.now().withNano(0));
    }
}
```


@RabbitListener把onTimeout注册为ready队列监听入口，id用于定位这个监听容器。消息先经JSON转换，随后validator.validate执行record字段约束；存在非法字段时抛ConstraintViolationException，不能带着不完整订单编号进入交易操作。

合法消息仅调用原 orders.closeExpiredOrder(orderId, 当前时间)。它不拿正文expiresAt当关单授权，也不复制库存SQL。原方法执行条件更新：只有PENDING_PAYMENT且数据库expires_at不晚于当前时间才转CLOSED，更新成功才归还活动名额和商品库存，仍在同一事务。

因此重复消息、已经支付/取消/关闭的订单以及提前到达的消息，通常是正常返回、正常确认；提前消息不会提前关单，真正到期由原扫描兜底。业务SQL异常向容器传播并重试；提交后缓存失效失败时数据库可能已关闭，下一次重投按终态退出，库存不会重复归还。缓存自身仍受上一阶段30秒有效期限制。

AUTO确认与MySQL提交不是一个分布式事务。数据库已提交、消费确认前进程退出仍可能重复投递；安全性来自原条件状态转换，而不是假设消息只收到一次。

## 10. 原扫描的改动只在调度开关


`src/main/java/com/elaina/limitedtrade/config/TaskSchedulingConfiguration.java`：

```java
package com.elaina.limitedtrade.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TaskSchedulingConfiguration {
}
```


`src/main/java/com/elaina/limitedtrade/order/OrderTimeoutScheduler.java`：

```java
package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class OrderTimeoutScheduler {

    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final boolean enabled;

    public OrderTimeoutScheduler(OrderMapper orderMapper, OrderService orderService,
            @Value("${app.order-timeout.enabled:true}") boolean enabled) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.order-timeout.scan-delay-ms:30000}")
    public void scanIfEnabled() {
        if (enabled) {
            closeExpiredOrders();
        }
    }

    public void closeExpiredOrders() {
        LocalDateTime cutoff = LocalDateTime.now().withNano(0);
        // 每轮只取一批；下一轮继续处理剩余到期订单。
        for (Long id : orderMapper.findExpiredOrderIds(cutoff, 100)) {
            orderService.closeExpiredOrder(id, cutoff);
        }
    }
}
```


旧 OrderSchedulingConfiguration 将@EnableScheduling绑定在扫描开关上，关闭扫描会连带关闭其他定时任务。现在删除该配置类，把共享调度设施放到TaskSchedulingConfiguration，单个任务各自判断开关。

原closeExpiredOrders里的“查到期订单 → 跨Bean调用关单”保持。只把@Scheduled移到scanIfEnabled包装入口，保留手动入口给隔离验证。关单Service和库存SQL没有新增一套实现。

## 11. 本机启动与停止

迁移和本机账号/虚拟主机已经配置完成。新环境需按README先初始化数据库和独立MQ配置；本机不要重跑初始化SQL。RabbitMQ服务端与Erlang安装目录按本机实际路径，不含在Git仓库或JAR中。

在项目根目录运行：

```powershell
.\scripts\start-rabbitmq.ps1
# 日志：tools/rabbitmq-runtime/console.log
# 管理页：http://127.0.0.1:15672
# 用户和口令查看本机config/rabbitmq.properties，不要提交或对外展示

# 保持另一个WSL会话打开，确认redis-cli PING返回PONG
wsl -d Ubuntu-22.04

# 返回Windows的另一个项目终端启动应用
java -jar .\target\limited-trade-platform-0.0.1-SNAPSHOT.jar

# 停止应用后，停止本项目RabbitMQ节点
.\scripts\stop-rabbitmq.ps1
```

脚本只启动/停止 limited_trade@localhost 节点。不要在节点已运行时重复启动；没有根据端口强杀进程。AMQP5672和管理15672监听127.0.0.1。配置/日志/数据在tools/rabbitmq-runtime，实际口令在被忽略的config/rabbitmq.properties。

## 12. 验证结果与边界

本轮离线package：218项，0失败、0错误、0跳过，BUILD SUCCESS。包含既有184项和新增34项。本机日志：tools/order-message-package.log。

新增验证分为：Outbox原子保存5项、发送确认5项、真实MySQL投递事务5项、消息边界2项、真实RabbitMQ专项14项、调度开关3项。测试源码不在本文展开。

隔离验证将随机前缀等待队列TTL缩短为200毫秒，并在测试库夹具中显式设置数据库截止时间；没有把提前消息当作到期，也没有实际等待15分钟冒充全程演示。生产默认仍900000毫秒。

真实Broker专项覆盖普通/活动到期关单、重复与终态消息、提前消息不改变支付期限、归还SQL失败后回滚与失败队列归档、修复重投、非法JSON/缺字段/不可信类型头、停止监听后的扫描兜底、提交后缓存操作故障注入、真实不可路由退回、独立客户端连接拒绝后恢复投递。业务失败实际经过1秒、2秒退避，最终x-death记录reason=rejected；转换阶段异常在当前容器重试链内也最终归档，不能推断所有输入/框架版本都有相同重试次数。

NACK、确认超时及中断通过发送边界注入验证；提交后缓存失败通过注册真实afterCommit故障验证。连接拒绝通过独立客户端连接已关闭本机端口验证，没有关闭正在运行的Broker来冒充服务端宕机演练。

独立只读复核未发现明确重要缺陷。开发库原四表公开字段基线一致、新Outbox为0；测试库五表均0，自有触发器和随机测试队列/交换机无残留。JAR不含本机凭据、测试配置、tools。启动/停止脚本已实际停止并重新启动本项目节点。

这是本机集成与故障注入证据，没有进行吞吐、生产集群或公网验证。未提交、推送或合并。本阶段完成后先理解本文的新链路，再进入下一阶段。
