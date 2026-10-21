package com.elaina.limitedtrade.activity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "活动")
@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    @Operation(summary = "查询活动列表", description = "匿名可读。")
    public List<Activity> listActivities() {
        return activityService.listActivities();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询活动详情", description = "不存在的活动返回404。")
    public Activity getActivity(@PathVariable @Positive long id) {
        return activityService.getActivity(id);
    }
}
