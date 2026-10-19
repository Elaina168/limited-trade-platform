package com.elaina.limitedtrade.activity;

import java.time.LocalDateTime;
import java.util.List;

import com.elaina.limitedtrade.product.ProductMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final ProductMapper productMapper;

    public ActivityService(ActivityMapper activityMapper, ProductMapper productMapper) {
        this.activityMapper = activityMapper;
        this.productMapper = productMapper;
    }

    @Transactional
    public Activity createActivity(CreateActivityRequest request) {
        LocalDateTime startsAt = request.startsAt().withNano(0);
        LocalDateTime endsAt = request.endsAt().withNano(0);
        if (!endsAt.isAfter(startsAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "活动结束时间必须晚于开始时间");
        }
        if (productMapper.findById(request.productId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        Activity activity = new Activity();
        activity.setProductId(request.productId());
        activity.setName(request.name());
        activity.setTotalQuantity(request.totalQuantity());
        activity.setRemainingQuantity(request.totalQuantity());
        activity.setStartsAt(startsAt);
        activity.setEndsAt(endsAt);
        activityMapper.insertActivity(activity);
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
