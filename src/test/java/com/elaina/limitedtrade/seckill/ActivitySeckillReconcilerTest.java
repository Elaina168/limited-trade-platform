package com.elaina.limitedtrade.seckill;

import com.elaina.limitedtrade.activity.Activity;
import com.elaina.limitedtrade.activity.ActivityMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ActivitySeckillReconcilerTest {

    private final ActivitySeckillRedisStore redis = mock(ActivitySeckillRedisStore.class);
    private final ActivitySeckillMapper reservations = mock(ActivitySeckillMapper.class);
    private final ActivityMapper activities = mock(ActivityMapper.class);
    private final ActivitySeckillService service = mock(ActivitySeckillService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ActivitySeckillReconciler reconciler = new ActivitySeckillReconciler(
            new SeckillProperties(true, "test:seckill", 3600, 60_000, 5000),
            redis, reservations, activities, service, json, transactions);

    @BeforeEach
    void useOneActiveOrder() {
        var activity = new Activity();
        activity.setId(3L);
        activity.setTotalQuantity(8);
        activity.setRemainingQuantity(7);
        activity.setPerUserLimit(1);
        when(activities.findById(3L)).thenReturn(activity);
        when(reservations.listActiveUserQuantities(3L))
                .thenReturn(List.of(new ActivitySeckillMapper.UserActivityQuantity(51L, 1)));
    }

    @Test
    void correctQuotaStillRequiresRepairWhenBuyersDiffer() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var activity = activities.findById(3L);
        when(activities.findByIdForUpdate(3L)).thenReturn(activity);
        var drift = new ActivitySeckillRedisStore.RedisActivitySnapshot(true, 7, Map.of(), Map.of(52L, 1));
        when(redis.readSnapshot(3L)).thenReturn(drift);
        when(redis.repairQuotas(3L, drift, 7, Map.of(51L, 1))).thenReturn(true);

        var report = reconciler.reconcileActivity(3L);

        assertTrue(report.redisConservationHolds());
        assertTrue(report.redisBuyerQuantitiesMatch());
        assertTrue(report.recalibrated());
    }

    @Test
    void checksBuyerAndQuantityForInFlightReservationWithoutOverwritingIt() throws Exception {
        var pending = new ActivitySeckillMessage(52L, 3L, 900L, 1, 5L, null, "req-inflight", LocalDateTime.now());
        var inflight = Map.of("52:req-inflight", System.currentTimeMillis() + "|" + json.writeValueAsString(pending));
        when(redis.readSnapshot(3L)).thenReturn(
                new ActivitySeckillRedisStore.RedisActivitySnapshot(true, 6, inflight, Map.of(51L, 1, 53L, 1)));

        var report = reconciler.reconcileActivity(3L);

        assertEquals(1, report.redisInflightQuantity());
        assertTrue(report.redisConservationHolds());
        assertFalse(report.redisBuyerQuantitiesMatch());
        assertFalse(report.recalibrated());
        verifyNoInteractions(transactions, service);
    }

    @Test
    void reportsUnrepairedDriftWhenCacheChangesAfterSnapshot() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var activity = activities.findById(3L);
        when(activities.findByIdForUpdate(3L)).thenReturn(activity);
        var drift = new ActivitySeckillRedisStore.RedisActivitySnapshot(true, 7, Map.of(), Map.of(52L, 1));
        when(redis.readSnapshot(3L)).thenReturn(drift);

        var report = reconciler.reconcileActivity(3L);

        assertTrue(report.redisConservationHolds());
        assertFalse(report.redisBuyerQuantitiesMatch());
        assertFalse(report.recalibrated());
    }
}
