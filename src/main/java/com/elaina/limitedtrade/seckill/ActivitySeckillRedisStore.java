package com.elaina.limitedtrade.seckill;

import com.elaina.limitedtrade.activity.Activity;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ActivitySeckillRedisStore {

    private final StringRedisTemplate redis;
    private final SeckillProperties properties;
    private final DefaultRedisScript<Long> initScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<List> reserveScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> completeScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> releaseScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<List> snapshotScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> repairScript = new DefaultRedisScript<>();

    public ActivitySeckillRedisStore(StringRedisTemplate redis, SeckillProperties properties) {
        this.redis = redis;
        this.properties = properties;
        initScript.setLocation(new ClassPathResource("redis/activity-seckill-init.lua"));
        initScript.setResultType(Long.class);
        reserveScript.setLocation(new ClassPathResource("redis/activity-seckill.lua"));
        reserveScript.setResultType(List.class);
        completeScript.setLocation(new ClassPathResource("redis/activity-seckill-complete.lua"));
        completeScript.setResultType(Long.class);
        releaseScript.setLocation(new ClassPathResource("redis/activity-seckill-release.lua"));
        releaseScript.setResultType(Long.class);
        snapshotScript.setLocation(new ClassPathResource("redis/activity-seckill-snapshot.lua"));
        snapshotScript.setResultType(List.class);
        repairScript.setLocation(new ClassPathResource("redis/activity-seckill-repair.lua"));
        repairScript.setResultType(Long.class);
    }

    public String metaKey(long activityId) {
        return properties.keyPrefix() + ":act:" + activityId + ":meta";
    }

    public String quotaKey(long activityId) {
        return properties.keyPrefix() + ":act:" + activityId + ":quota";
    }

    public String buyersKey(long activityId) {
        return properties.keyPrefix() + ":act:" + activityId + ":buyers";
    }

    public String inflightKey(long activityId) {
        return properties.keyPrefix() + ":act:" + activityId + ":inflight";
    }

    public String requestKey(long userId, String requestId) {
        return properties.keyPrefix() + ":req:" + userId + ":" + requestId;
    }

    public String releaseGuardKey(long userId, String requestId) {
        return properties.keyPrefix() + ":released:" + userId + ":" + requestId;
    }

    public void prewarm(Activity activity, List<ActivitySeckillMapper.UserActivityQuantity> activeUsers, boolean force) {
        if (!properties.enabled() || activity == null || activity.getId() == null) {
            return;
        }
        ZoneId zone = ZoneId.systemDefault();
        long startsAtMs = activity.getStartsAt().atZone(zone).toInstant().toEpochMilli();
        long endsAtMs = activity.getEndsAt().atZone(zone).toInstant().toEpochMilli();
        long nowMs = System.currentTimeMillis();
        long ttlMs = Math.max(Duration.ofHours(24).toMillis(), endsAtMs - nowMs + Duration.ofHours(2).toMillis());
        int perUserLimit = activity.getPerUserLimit() == null ? 0 : activity.getPerUserLimit();
        List<String> args = new ArrayList<>();
        args.add(force ? "1" : "0");
        args.add(Long.toString(activity.getProductId()));
        args.add(Long.toString(startsAtMs));
        args.add(Long.toString(endsAtMs));
        args.add(Integer.toString(perUserLimit));
        args.add(Integer.toString(Math.max(0, activity.getRemainingQuantity())));
        args.add(Integer.toString(activity.getTotalQuantity()));
        args.add(Long.toString(ttlMs));
        if (activeUsers != null) {
            for (var entry : activeUsers) {
                if (entry.quantity() > 0) {
                    args.add(Long.toString(entry.userId()));
                    args.add(Integer.toString(entry.quantity()));
                }
            }
        }
        executeRedis(() -> redis.execute(initScript,
                List.of(metaKey(activity.getId()), quotaKey(activity.getId()), buyersKey(activity.getId())),
                args.toArray()));
    }

    public ReserveResult reserve(long userId, long activityId, SeckillRequest request, String fingerprint, String inflightJson) {
        long ttlMs = Duration.ofSeconds(properties.requestTtlSeconds()).toMillis();
        List<?> raw = executeRedis(() -> redis.execute(reserveScript,
                List.of(metaKey(activityId), quotaKey(activityId), buyersKey(activityId),
                        requestKey(userId, request.requestId()), inflightKey(activityId)),
                Long.toString(userId),
                Long.toString(request.productId()),
                Integer.toString(request.quantity()),
                fingerprint,
                inflightJson,
                Long.toString(ttlMs),
                request.requestId()));
        if (raw == null || raw.size() < 2) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "秒杀预扣服务返回异常");
        }
        return new ReserveResult(String.valueOf(raw.get(0)), String.valueOf(raw.get(1)));
    }

    public void markCreated(long userId, long activityId, String requestId, long orderId) {
        if (!properties.enabled()) {
            return;
        }
        long ttlMs = Duration.ofSeconds(properties.requestTtlSeconds()).toMillis();
        redis.execute(completeScript,
                List.of(requestKey(userId, requestId), inflightKey(activityId)),
                Long.toString(userId), requestId, Long.toString(orderId), Long.toString(ttlMs));
    }

    public boolean release(long userId, long activityId, String requestId, int quantity, int totalCap, String newState) {
        if (!properties.enabled()) {
            return false;
        }
        long ttlMs = Duration.ofSeconds(properties.requestTtlSeconds()).toMillis();
        Long released = redis.execute(releaseScript,
                List.of(quotaKey(activityId), buyersKey(activityId), requestKey(userId, requestId),
                        inflightKey(activityId), releaseGuardKey(userId, requestId)),
                Long.toString(userId),
                requestId,
                Integer.toString(quantity),
                Integer.toString(totalCap),
                newState,
                Long.toString(ttlMs));
        return released != null && released > 0;
    }

    public String readRequestState(long userId, String requestId) {
        if (!properties.enabled()) {
            return null;
        }
        String raw = executeRedis(() -> redis.opsForValue().get(requestKey(userId, requestId)));
        if (raw == null) {
            return null;
        }
        int idx = raw.indexOf('#');
        return idx < 0 ? null : raw.substring(idx + 1);
    }

    public RedisActivitySnapshot readSnapshot(long activityId) {
        List<?> raw = executeRedis(() -> redis.execute(snapshotScript, reconciliationKeys(activityId)));
        boolean initialized = ((Number) raw.get(0)).intValue() == 1;
        String quotaStr = (String) raw.get(1);
        Integer quota = quotaStr.isEmpty() ? null : Integer.valueOf(quotaStr);
        List<?> rawInflight = (List<?>) raw.get(2);
        Map<String, String> inflight = new HashMap<>();
        for (int i = 0; i < rawInflight.size(); i += 2) {
            inflight.put((String) rawInflight.get(i), (String) rawInflight.get(i + 1));
        }
        List<?> rawBuyers = (List<?>) raw.get(3);
        Map<Long, Integer> buyers = new HashMap<>();
        for (int i = 0; i < rawBuyers.size(); i += 2) {
            buyers.put(Long.valueOf((String) rawBuyers.get(i)), Integer.valueOf((String) rawBuyers.get(i + 1)));
        }
        return new RedisActivitySnapshot(initialized, quota, inflight, buyers);
    }

    public boolean repairQuotas(long activityId, RedisActivitySnapshot snapshot, int remaining, Map<Long, Integer> buyers) {
        List<String> args = new ArrayList<>();
        args.add(snapshot.remainingQuota() == null ? "" : snapshot.remainingQuota().toString());
        args.add(Integer.toString(snapshot.buyerQuantities().size()));
        snapshot.buyerQuantities().forEach((id, quantity) -> {
            args.add(id.toString());
            args.add(quantity.toString());
        });
        args.add(Integer.toString(remaining));
        buyers.forEach((id, quantity) -> {
            args.add(id.toString());
            args.add(quantity.toString());
        });
        return Long.valueOf(1).equals(executeRedis(() ->
                redis.execute(repairScript, reconciliationKeys(activityId), args.toArray())));
    }

    private List<String> reconciliationKeys(long activityId) {
        return List.of(metaKey(activityId), quotaKey(activityId), inflightKey(activityId), buyersKey(activityId));
    }

    public void removeInflight(long activityId, String memberKey) {
        if (properties.enabled()) {
            redis.opsForHash().delete(inflightKey(activityId), memberKey);
        }
    }

    private <T> T executeRedis(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RedisConnectionFailureException | QueryTimeoutException | RedisSystemException exception) {
            if (exception instanceof RedisSystemException sys
                    && !(sys.getMostSpecificCause() instanceof IOException)) {
                throw sys;
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "秒杀缓存服务暂不可用", exception);
        }
    }

    public record ReserveResult(String code, String detail) {
    }

    public record RedisActivitySnapshot(
            boolean initialized,
            Integer remainingQuota,
            Map<String, String> inflightEntries,
            Map<Long, Integer> buyerQuantities) {
    }
}
