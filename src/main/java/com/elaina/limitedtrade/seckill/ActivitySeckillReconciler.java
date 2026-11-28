package com.elaina.limitedtrade.seckill;

import com.elaina.limitedtrade.activity.Activity;
import com.elaina.limitedtrade.activity.ActivityMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ActivitySeckillReconciler {

    private static final Logger log = LoggerFactory.getLogger(ActivitySeckillReconciler.class);

    private final SeckillProperties properties;
    private final ActivitySeckillRedisStore redisStore;
    private final ActivitySeckillMapper seckillMapper;
    private final ActivityMapper activityMapper;
    private final ActivitySeckillService seckillService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate repairTransaction;

    public ActivitySeckillReconciler(SeckillProperties properties,
                                     ActivitySeckillRedisStore redisStore,
                                     ActivitySeckillMapper seckillMapper,
                                     ActivityMapper activityMapper,
                                     ActivitySeckillService seckillService,
                                     ObjectMapper objectMapper,
                                     PlatformTransactionManager txManager) {
        this.properties = properties;
        this.redisStore = redisStore;
        this.seckillMapper = seckillMapper;
        this.activityMapper = activityMapper;
        this.seckillService = seckillService;
        this.objectMapper = objectMapper;
        this.repairTransaction = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${app.seckill.reconcile-delay-ms:5000}")
    public void scheduledReconcile() {
        if (!properties.enabled()) {
            return;
        }
        try {
            reconcileAll();
        } catch (RuntimeException error) {
            log.atWarn().addKeyValue("event", "seckill_reconcile_failed")
                    .setCause(error)
                    .log("秒杀定时对账任务执行失败");
        }
    }

    public void reconcileAll() {
        if (!properties.enabled()) {
            return;
        }
        for (ActivityPurchaseReservation queued : seckillMapper.findStaleQueued(100)) {
            seckillService.consumeMessage(new ActivitySeckillMessage(
                    queued.getUserId(), queued.getActivityId(), queued.getProductId(),
                    queued.getQuantity(), queued.getAddressId(), queued.getUserCouponId(),
                    queued.getRequestId(), queued.getReservedAt()));
        }
        for (Activity activity : activityMapper.findAll()) {
            reconcileActivity(activity.getId());
        }
    }

    public ReconciliationReport reconcileActivity(long activityId) {
        Activity activity = activityMapper.findById(activityId);
        if (activity == null) {
            throw new IllegalArgumentException("活动不存在: " + activityId);
        }
        if (!properties.enabled()) {
            return compare(activity, new ActivitySeckillRedisStore.RedisActivitySnapshot(false, null, Map.of(), Map.of()),
                    List.of(), false);
        }

        var snapshot = redisStore.readSnapshot(activityId);
        var inflight = snapshot.inflightEntries().values().stream().map(this::decodeInflight).toList();
        long staleBefore = System.currentTimeMillis() - properties.inflightStaleMillis();
        boolean attemptedRecovery = false;
        for (InflightReservation entry : inflight) {
            if (entry.reservedMs() > staleBefore) {
                continue;
            }
            attemptedRecovery = true;
            try {
                seckillService.consumeMessage(entry.message());
            } catch (RuntimeException error) {
                log.atWarn().addKeyValue("event", "seckill_inflight_recover_error")
                        .addKeyValue("activityId", activityId)
                        .addKeyValue("requestId", entry.message().requestId())
                        .setCause(error)
                        .log("恢复超时的在途秒杀请求失败");
            }
        }
        if (attemptedRecovery) {
            activity = activityMapper.findById(activityId);
            snapshot = redisStore.readSnapshot(activityId);
            inflight = snapshot.inflightEntries().values().stream().map(this::decodeInflight).toList();
        }

        var report = compare(activity, snapshot, inflight, false);
        if ((!report.redisConservationHolds() || !report.redisBuyerQuantitiesMatch()) && inflight.isEmpty()) {
            // 只在发现漂移时锁定活动行重读数据库。Lua 同时确认 Redis 快照未变化。
            return repairTransaction.execute(status -> {
                var locked = activityMapper.findByIdForUpdate(activityId);
                var current = redisStore.readSnapshot(activityId);
                var reservations = current.inflightEntries().values().stream().map(this::decodeInflight).toList();
                return compare(locked, current, reservations, true);
            });
        }
        return report;
    }

    private ReconciliationReport compare(Activity activity, ActivitySeckillRedisStore.RedisActivitySnapshot snapshot,
                                         List<InflightReservation> inflight, boolean repair) {
        long activityId = activity.getId();
        List<ActivitySeckillMapper.UserActivityQuantity> activeUsers = seckillMapper.listActiveUserQuantities(activityId);
        int sold = activeUsers.stream().mapToInt(ActivitySeckillMapper.UserActivityQuantity::quantity).sum();
        boolean dbConserved = activity.getTotalQuantity() == activity.getRemainingQuantity() + sold;
        Integer limit = activity.getPerUserLimit();
        boolean perUserRespected = limit == null || activeUsers.stream().allMatch(user -> user.quantity() <= limit);

        Map<Long, Integer> expectedBuyers = new HashMap<>();
        activeUsers.forEach(user -> expectedBuyers.put(user.userId(), user.quantity()));
        int inflightQuantity = 0;
        for (InflightReservation entry : inflight) {
            var message = entry.message();
            inflightQuantity += message.quantity();
            expectedBuyers.merge(message.userId(), message.quantity(), Integer::sum);
        }

        int expectedQuota = Math.max(0, activity.getRemainingQuantity() - inflightQuantity);
        Integer quota = snapshot.remainingQuota();
        boolean quotaMatches = !snapshot.initialized() || Integer.valueOf(expectedQuota).equals(quota);
        boolean buyersMatch = !snapshot.initialized() || expectedBuyers.equals(snapshot.buyerQuantities());
        boolean repaired = repair && dbConserved && inflight.isEmpty() && (!quotaMatches || !buyersMatch)
                && redisStore.repairQuotas(activityId, snapshot, expectedQuota, expectedBuyers);
        if (repaired) {
            log.atWarn().addKeyValue("event", "seckill_redis_drift_recalibrated")
                    .addKeyValue("activityId", activityId)
                    .addKeyValue("quotaMatched", quotaMatches)
                    .addKeyValue("buyersMatched", buyersMatch)
                    .log("已按数据库事实校准秒杀名额和买家计数");
        }
        return new ReconciliationReport(activityId, activity.getTotalQuantity(), activity.getRemainingQuantity(),
                sold, repaired ? Integer.valueOf(expectedQuota) : quota, inflightQuantity, dbConserved,
                quotaMatches || repaired, buyersMatch || repaired, perUserRespected, repaired);
    }

    private InflightReservation decodeInflight(String raw) {
        int separator = raw.indexOf('|');
        try {
            return new InflightReservation(Long.parseLong(raw.substring(0, separator)),
                    objectMapper.readValue(raw.substring(separator + 1), ActivitySeckillMessage.class));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("在途秒杀数据无法解析", error);
        }
    }

    private record InflightReservation(long reservedMs, ActivitySeckillMessage message) {
    }

    public record ReconciliationReport(
            long activityId,
            int totalQuantity,
            int dbRemainingQuantity,
            int activeOrderQuantity,
            Integer redisRemainingQuota,
            int redisInflightQuantity,
            boolean dbConservationHolds,
            boolean redisConservationHolds,
            boolean redisBuyerQuantitiesMatch,
            boolean perUserLimitHolds,
            boolean recalibrated) {
    }
}
