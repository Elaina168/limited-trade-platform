package com.elaina.limitedtrade.seckill;

import com.elaina.limitedtrade.activity.Activity;
import com.elaina.limitedtrade.activity.ActivityMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ActivitySeckillReconciler {

    private static final Logger log = LoggerFactory.getLogger(ActivitySeckillReconciler.class);

    private final SeckillProperties properties;
    private final ActivitySeckillRedisStore redisStore;
    private final ActivitySeckillMapper seckillMapper;
    private final ActivityMapper activityMapper;
    private final ActivitySeckillService seckillService;
    private final ObjectMapper objectMapper;

    public ActivitySeckillReconciler(SeckillProperties properties,
                                     ActivitySeckillRedisStore redisStore,
                                     ActivitySeckillMapper seckillMapper,
                                     ActivityMapper activityMapper,
                                     ActivitySeckillService seckillService,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.redisStore = redisStore;
        this.seckillMapper = seckillMapper;
        this.activityMapper = activityMapper;
        this.seckillService = seckillService;
        this.objectMapper = objectMapper;
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
        int activeOrderQty = seckillMapper.sumActiveOrderQuantityByActivity(activityId);
        List<ActivitySeckillMapper.UserActivityQuantity> activeUsers = seckillMapper.listActiveUserQuantities(activityId);
        Integer perUserLimit = activity.getPerUserLimit();
        boolean dbConserved = activity.getTotalQuantity() == activity.getRemainingQuantity() + activeOrderQty;
        boolean perUserRespected = perUserLimit == null
                || activeUsers.stream().allMatch(u -> u.quantity() <= perUserLimit);

        if (!properties.enabled()) {
            return new ReconciliationReport(activityId, activity.getTotalQuantity(), activity.getRemainingQuantity(),
                    activeOrderQty, null, 0, dbConserved, true, perUserRespected, false);
        }

        ActivitySeckillRedisStore.RedisActivitySnapshot snap = redisStore.readSnapshot(activityId);
        long nowMs = System.currentTimeMillis();
        int recoveredCount = 0;
        for (Map.Entry<String, String> entry : snap.inflightEntries().entrySet()) {
            String raw = entry.getValue();
            int bar = raw.indexOf('|');
            if (bar <= 0) {
                continue;
            }
            long reservedMs = Long.parseLong(raw.substring(0, bar));
            if (nowMs - reservedMs < properties.inflightStaleMillis()) {
                continue;
            }
            try {
                ActivitySeckillMessage msg = objectMapper.readValue(raw.substring(bar + 1), ActivitySeckillMessage.class);
                seckillService.consumeMessage(msg);
                recoveredCount++;
            } catch (Exception error) {
                log.atWarn().addKeyValue("event", "seckill_inflight_recover_error")
                        .addKeyValue("activityId", activityId)
                        .addKeyValue("member", entry.getKey())
                        .setCause(error)
                        .log("恢复超时的在途秒杀请求失败");
            }
        }
        if (recoveredCount > 0) {
            activity = activityMapper.findById(activityId);
            activeOrderQty = seckillMapper.sumActiveOrderQuantityByActivity(activityId);
            activeUsers = seckillMapper.listActiveUserQuantities(activityId);
            snap = redisStore.readSnapshot(activityId);
            dbConserved = activity.getTotalQuantity() == activity.getRemainingQuantity() + activeOrderQty;
            Integer refreshedLimit = activity.getPerUserLimit();
            perUserRespected = refreshedLimit == null
                    || activeUsers.stream().allMatch(u -> u.quantity() <= refreshedLimit);
        }

        int inflightQty = countInflightQuantity(snap.inflightEntries());
        boolean redisMatched = !snap.initialized()
                || (snap.remainingQuota() != null
                && snap.remainingQuota() + inflightQty + activeOrderQty == activity.getTotalQuantity());
        boolean recalibrated = false;
        if (snap.initialized() && !redisMatched && snap.inflightEntries().isEmpty()) {
            log.atWarn().addKeyValue("event", "seckill_quota_drift_recalibrated")
                    .addKeyValue("activityId", activityId)
                    .addKeyValue("redisQuota", snap.remainingQuota())
                    .addKeyValue("dbRemaining", activity.getRemainingQuantity())
                    .log("检测到无在途请求下Redis与数据库名额偏差，已按数据库事实重校准");
            redisStore.prewarm(activity, activeUsers, true);
            snap = redisStore.readSnapshot(activityId);
            redisMatched = snap.remainingQuota() != null
                    && snap.remainingQuota() + activeOrderQty == activity.getTotalQuantity();
            recalibrated = true;
        }
        return new ReconciliationReport(
                activityId,
                activity.getTotalQuantity(),
                activity.getRemainingQuantity(),
                activeOrderQty,
                snap.remainingQuota(),
                inflightQty,
                dbConserved,
                redisMatched,
                perUserRespected,
                recalibrated);
    }

    private int countInflightQuantity(Map<String, String> inflight) {
        int sum = 0;
        for (String raw : inflight.values()) {
            int bar = raw.indexOf('|');
            if (bar > 0) {
                try {
                    ActivitySeckillMessage msg = objectMapper.readValue(raw.substring(bar + 1), ActivitySeckillMessage.class);
                    sum += msg.quantity();
                } catch (Exception ignored) {
                }
            }
        }
        return sum;
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
            boolean perUserLimitHolds,
            boolean recalibrated) {
    }
}
