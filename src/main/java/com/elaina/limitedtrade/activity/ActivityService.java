package com.elaina.limitedtrade.activity;

import java.time.LocalDateTime;
import java.util.List;

import com.elaina.limitedtrade.product.ProductMapper;
import com.elaina.limitedtrade.seckill.ActivitySeckillMapper;
import com.elaina.limitedtrade.seckill.ActivitySeckillRedisStore;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final ProductMapper productMapper;
    private final Optional<ActivitySeckillRedisStore> seckillRedisStore;
    private final Optional<ActivitySeckillMapper> seckillMapper;

    public ActivityService(ActivityMapper activityMapper, ProductMapper productMapper,
                           Optional<ActivitySeckillRedisStore> seckillRedisStore,
                           Optional<ActivitySeckillMapper> seckillMapper) {
        this.activityMapper = activityMapper;
        this.productMapper = productMapper;
        this.seckillRedisStore = seckillRedisStore;
        this.seckillMapper = seckillMapper;
    }

    @Transactional
    public Activity createActivity(CreateActivityRequest request) {
        LocalDateTime startsAt = request.startsAt().withNano(0);
        LocalDateTime endsAt = request.endsAt().withNano(0);
        if (!endsAt.isAfter(startsAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "活动结束时间必须晚于开始时间");
        }
        if (request.perUserLimit() != null && request.perUserLimit() > request.totalQuantity()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每人限购数量不能大于活动总名额");
        }
        if (productMapper.findById(request.productId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        Activity activity = new Activity();
        activity.setProductId(request.productId());
        activity.setName(request.name());
        activity.setTotalQuantity(request.totalQuantity());
        activity.setRemainingQuantity(request.totalQuantity());
        activity.setPerUserLimit(request.perUserLimit());
        activity.setStartsAt(startsAt);
        activity.setEndsAt(endsAt);
        activityMapper.insertActivity(activity);
        if (seckillRedisStore.isPresent() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        var active = seckillMapper.map(m -> m.listActiveUserQuantities(activity.getId())).orElse(List.of());
                        seckillRedisStore.get().prewarm(activity, active, true);
                    } catch (RuntimeException ignored) {
                    }
                }
            });
        }
        return activity;
    }

    public List<Activity> listActivities() {
        return activityMapper.findAll();
    }

    public Activity getActivity(long id) {
        Activity activity = activityMapper.findById(id);
        if (activity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "活动不存在");
        }
        return activity;
    }
}
