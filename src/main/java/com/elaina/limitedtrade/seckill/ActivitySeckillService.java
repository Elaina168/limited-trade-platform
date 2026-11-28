package com.elaina.limitedtrade.seckill;

import com.elaina.limitedtrade.activity.Activity;
import com.elaina.limitedtrade.activity.ActivityMapper;
import com.elaina.limitedtrade.order.CreateOrderRequest;
import com.elaina.limitedtrade.order.CreateOrderResult;
import com.elaina.limitedtrade.order.OrderResponse;
import com.elaina.limitedtrade.order.OrderService;
import com.elaina.limitedtrade.order.TradeOrder;
import com.elaina.limitedtrade.ratelimit.RedisOrderRateLimiter;
import com.elaina.limitedtrade.user.AppUserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ActivitySeckillService {

    private static final Logger log = LoggerFactory.getLogger(ActivitySeckillService.class);

    private final SeckillProperties properties;
    private final ActivitySeckillRedisStore redisStore;
    private final ActivitySeckillMapper seckillMapper;
    private final ActivityMapper activityMapper;
    private final AppUserMapper appUserMapper;
    private final OrderService orderService;
    private final RedisOrderRateLimiter rateLimiter;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTx;
    private final String exchangeName;

    public ActivitySeckillService(SeckillProperties properties,
                                  ActivitySeckillRedisStore redisStore,
                                  ActivitySeckillMapper seckillMapper,
                                  ActivityMapper activityMapper,
                                  AppUserMapper appUserMapper,
                                  OrderService orderService,
                                  RedisOrderRateLimiter rateLimiter,
                                  RabbitTemplate rabbitTemplate,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager txManager,
                                  @Value("${app.order-message.name-prefix:limited-trade}") String prefix) {
        this.properties = properties;
        this.redisStore = redisStore;
        this.seckillMapper = seckillMapper;
        this.activityMapper = activityMapper;
        this.appUserMapper = appUserMapper;
        this.orderService = orderService;
        this.rateLimiter = rateLimiter;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.exchangeName = prefix + ".activity-seckill.exchange";
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public SubmitOutcome submit(long userId, long activityId, SeckillRequest request) {
        rateLimiter.check(userId);
        if (!properties.enabled()) {
            CreateOrderResult sync = orderService.createOrder(userId, new CreateOrderRequest(
                    request.productId(), request.quantity(), request.requestId(), activityId,
                    request.userCouponId(), request.addressId()));
            HttpStatus status = sync.created() ? HttpStatus.CREATED : HttpStatus.OK;
            return new SubmitOutcome(status, SeckillResponse.created(request.requestId(), OrderResponse.from(sync.order())));
        }

        LocalDateTime tentativeTime = LocalDateTime.now().withNano(0);
        ActivitySeckillMessage tentativeMsg = new ActivitySeckillMessage(
                userId, activityId, request.productId(), request.quantity(),
                request.addressId(), request.userCouponId(), request.requestId(), tentativeTime);
        // Redis 请求键可能已经过期，预扣前先检查持久预约和同步订单共用的请求标识。
        if (seckillMapper.hasSubmittedRequest(userId, request.requestId())) {
            return replaySaved(tentativeMsg, seckillMapper.findByUserAndRequest(userId, request.requestId()));
        }
        String fp = fingerprint(activityId, request.productId(), request.quantity(), request.addressId(), request.userCouponId());
        String msgJson = serialize(tentativeMsg);

        ActivitySeckillRedisStore.ReserveResult result = redisStore.reserve(userId, activityId, request, fp, msgJson);
        if ("UNINITIALIZED".equals(result.code())) {
            Activity activity = activityMapper.findById(activityId);
            if (activity == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在");
            }
            var activeUsers = seckillMapper.listActiveUserQuantities(activityId);
            redisStore.prewarm(activity, activeUsers, false);
            result = redisStore.reserve(userId, activityId, request, fp, msgJson);
        }

        return switch (result.code()) {
            case "QUEUED" -> {
                long epochMs = Long.parseLong(result.detail());
                LocalDateTime reservedAt = Instant.ofEpochMilli(epochMs)
                        .atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0);
                ActivitySeckillMessage message = new ActivitySeckillMessage(
                        userId, activityId, request.productId(), request.quantity(),
                        request.addressId(), request.userCouponId(), request.requestId(), reservedAt);
                publishOrFallbackPersist(message);
                yield new SubmitOutcome(HttpStatus.ACCEPTED, SeckillResponse.queued(request.requestId()));
            }
            case "REPLAY" -> handleReplay(tentativeMsg, result.detail());
            case "CONFLICT", "PRODUCT_MISMATCH", "NOT_STARTED", "ENDED", "LIMIT_EXCEEDED", "SOLD_OUT" ->
                    throw new ResponseStatusException(HttpStatus.CONFLICT, result.detail());
            default -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "抢购状态未知：" + result.code());
        };
    }

    private void publishOrFallbackPersist(ActivitySeckillMessage message) {
        try {
            rabbitTemplate.convertAndSend(exchangeName, "activity-seckill.create", message);
        } catch (AmqpException amqpError) {
            log.atWarn().addKeyValue("event", "seckill_mq_publish_fallback")
                    .addKeyValue("activityId", message.activityId())
                    .addKeyValue("userId", message.userId())
                    .addKeyValue("requestId", message.requestId())
                    .setCause(amqpError)
                    .log("秒杀消息投递暂不可用，已写入预约表由对账任务补发");
            if (!persistQueuedReservation(message)) {
                String reason = "请求标识已用于另一笔购买";
                recordFailureAndRelease(message, reason);
                throw new ResponseStatusException(HttpStatus.CONFLICT, reason);
            }
        }
    }

    public void sendToQueue(ActivitySeckillMessage message) {
        rabbitTemplate.convertAndSend(exchangeName, "activity-seckill.create", message);
    }

    private SubmitOutcome handleReplay(ActivitySeckillMessage message, String statePart) {
        ActivityPurchaseReservation saved = seckillMapper.findByUserAndRequest(message.userId(), message.requestId());
        if (saved != null) {
            return replaySaved(message, saved);
        }
        if ("QUEUED".equals(statePart)) {
            return new SubmitOutcome(HttpStatus.ACCEPTED, SeckillResponse.queued(message.requestId()));
        }
        if (statePart.startsWith("FAILED:")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, statePart.substring("FAILED:".length()));
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "当前请求无法重复提交");
    }

    private SubmitOutcome replaySaved(ActivitySeckillMessage message, ActivityPurchaseReservation saved) {
        if (saved == null || !message.matchesReservation(saved)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请求标识已用于另一笔购买");
        }
        SeckillResponse result = savedResult(message.userId(), message.requestId(), saved);
        return switch (result.status()) {
            case "CREATED" -> new SubmitOutcome(HttpStatus.OK, result);
            case "QUEUED" -> new SubmitOutcome(HttpStatus.ACCEPTED, result);
            default -> throw new ResponseStatusException(HttpStatus.CONFLICT, result.message());
        };
    }

    private SeckillResponse savedResult(long userId, String requestId, ActivityPurchaseReservation saved) {
        return switch (saved.getStatus()) {
            case "CREATED" -> SeckillResponse.created(requestId, OrderResponse.from(orderService.getSeckillOrder(userId, saved)));
            case "FAILED", "CANCELED" -> SeckillResponse.failed(requestId, saved.getFailReason());
            case "QUEUED" -> SeckillResponse.queued(requestId);
            default -> throw new IllegalStateException("未知的秒杀预约状态：" + saved.getStatus());
        };
    }

    public SeckillResponse getResult(long userId, String requestId) {
        ActivityPurchaseReservation reservation = seckillMapper.findByUserAndRequest(userId, requestId);
        if (reservation != null && requestId.equals(reservation.getRequestId())) {
            return savedResult(userId, requestId, reservation);
        }
        if (properties.enabled()) {
            String state = redisStore.readRequestState(userId, requestId);
            if ("QUEUED".equals(state)) {
                return SeckillResponse.queued(requestId);
            }
            if (state != null && state.startsWith("FAILED:")) {
                return SeckillResponse.failed(requestId, state.substring("FAILED:".length()));
            }
            if (state != null && state.startsWith("CREATED:")) {
                // 第一次查库后订单可能刚提交；仍通过预约读取，不信任缓存里的订单编号。
                var committed = seckillMapper.findByUserAndRequest(userId, requestId);
                if (committed != null && requestId.equals(committed.getRequestId())) {
                    return savedResult(userId, requestId, committed);
                }
            }
        } else {
            // 关闭异步秒杀时仍使用原有的同步活动下单入口。
            TradeOrder directOrder = orderService.findOwnedOrderByRequest(userId, requestId);
            if (directOrder != null && "DIRECT".equals(directOrder.getOrderType())
                    && directOrder.getItems().getFirst().activityId() != null) {
                return SeckillResponse.created(requestId, OrderResponse.from(directOrder));
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到对应的抢购记录");
    }

    public void consumeMessage(ActivitySeckillMessage message) {
        try {
            TradeOrder order = orderService.materializeSeckillOrder(message);
            if (order != null) {
                redisStore.markCreated(message.userId(), message.activityId(), message.requestId(), order.getId());
            }
        } catch (ResponseStatusException businessError) {
            int code = businessError.getStatusCode().value();
            if (code >= 400 && code < 500) {
                String reason = businessError.getReason() == null ? "活动下单条件不满足" : businessError.getReason();
                recordFailureAndRelease(message, reason);
                return;
            }
            throw businessError;
        }
    }

    private boolean persistQueuedReservation(ActivitySeckillMessage msg) {
        return Boolean.TRUE.equals(requiresNewTx.execute(status -> {
            appUserMapper.lockById(msg.userId());
            ActivityPurchaseReservation saved = seckillMapper.findByUserAndRequest(msg.userId(), msg.requestId());
            if (saved != null) return msg.matchesReservation(saved);
            if (seckillMapper.hasOrderRequest(msg.userId(), msg.requestId())) return false;
            seckillMapper.insertIgnore(toReservation(msg, "QUEUED", null));
            return true;
        }));
    }

    private void recordFailureAndRelease(ActivitySeckillMessage msg, String reason) {
        String truncated = reason.length() > 200 ? reason.substring(0, 200) : reason;
        Integer totalCap = requiresNewTx.execute(status -> {
            appUserMapper.lockById(msg.userId());
            ActivityPurchaseReservation saved = seckillMapper.findByUserAndRequest(msg.userId(), msg.requestId());
            if (saved == null) {
                // 落败的异步请求只补偿 Redis，已建同步订单保留原请求标识和重放语义。
                if (!seckillMapper.hasOrderRequest(msg.userId(), msg.requestId())) {
                    seckillMapper.insertIgnore(toReservation(msg, "FAILED", truncated));
                }
            } else if (!msg.matchesReservation(saved)) {
                // 存储键与 Redis 原始键大小写规则不同；同一 Redis 键的旧消息不能归还当前预约的名额。
                if (msg.requestId().equals(saved.getRequestId())) return null;
            } else if ("QUEUED".equals(saved.getStatus())) {
                seckillMapper.markFailed(saved.getId(), truncated);
            } else if (!"FAILED".equals(saved.getStatus())) {
                return null;
            }
            Activity activity = activityMapper.findById(msg.activityId());
            return activity == null ? 0 : activity.getTotalQuantity();
        });
        if (totalCap == null) return;
        redisStore.release(msg.userId(), msg.activityId(), msg.requestId(),
                msg.quantity(), totalCap, "FAILED:" + truncated);
        log.atInfo().addKeyValue("event", "seckill_reservation_rejected")
                .addKeyValue("activityId", msg.activityId())
                .addKeyValue("userId", msg.userId())
                .addKeyValue("requestId", msg.requestId())
                .addKeyValue("reason", truncated)
                .log("异步秒杀建单校验未通过并已归还预扣名额");
    }

    public void prewarmActivity(Activity activity) {
        if (properties.enabled() && activity != null && activity.getId() != null) {
            try {
                redisStore.prewarm(activity, seckillMapper.listActiveUserQuantities(activity.getId()), true);
            } catch (ResponseStatusException ignored) {
                // 创建活动时不因预热瞬时连接失败回滚已提交的主库活动记录。
            }
        }
    }

    public void onOrderCanceledOrClosed(long orderId, String reason) {
        ActivityPurchaseReservation reservation = seckillMapper.findByOrderId(orderId);
        if (reservation == null) {
            return;
        }
        seckillMapper.markCanceledByOrderId(orderId, reason);
    }

    public void releaseRedisAfterOrderClosed(long orderId) {
        if (!properties.enabled()) {
            return;
        }
        ActivityPurchaseReservation reservation = seckillMapper.findByOrderId(orderId);
        if (reservation == null) {
            return;
        }
        Activity activity = activityMapper.findById(reservation.getActivityId());
        int totalCap = activity == null ? 0 : activity.getTotalQuantity();
        try {
            redisStore.release(reservation.getUserId(), reservation.getActivityId(),
                    reservation.getRequestId(), reservation.getQuantity(), totalCap, "CANCELED");
        } catch (RuntimeException error) {
            log.atWarn().addKeyValue("event", "seckill_redis_release_deferred")
                    .addKeyValue("orderId", orderId)
                    .setCause(error)
                    .log("订单关闭后归还Redis秒杀名额失败，将由对账任务校准");
        }
    }

    static ActivityPurchaseReservation toReservation(ActivitySeckillMessage msg, String status, String failReason) {
        ActivityPurchaseReservation row = new ActivityPurchaseReservation();
        row.setRequestId(msg.requestId());
        row.setUserId(msg.userId());
        row.setActivityId(msg.activityId());
        row.setProductId(msg.productId());
        row.setQuantity(msg.quantity());
        row.setAddressId(msg.addressId());
        row.setUserCouponId(msg.userCouponId());
        row.setStatus(status);
        row.setFailReason(failReason);
        row.setReservedAt(msg.reservedAt());
        return row;
    }

    static String fingerprint(long activityId, long productId, int quantity, Long addressId, Long userCouponId) {
        return activityId + "|" + productId + "|" + quantity + "|"
                + Objects.toString(addressId, "") + "|" + Objects.toString(userCouponId, "");
    }

    private String serialize(ActivitySeckillMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化秒杀消息", error);
        }
    }

    public record SubmitOutcome(HttpStatus httpStatus, SeckillResponse body) {
    }
}
