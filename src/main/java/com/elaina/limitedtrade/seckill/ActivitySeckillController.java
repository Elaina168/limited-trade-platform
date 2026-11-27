package com.elaina.limitedtrade.seckill;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "活动秒杀")
@Validated
@RestController
@RequestMapping("/api/activities")
public class ActivitySeckillController {

    private final ActivitySeckillService seckillService;

    public ActivitySeckillController(ActivitySeckillService seckillService) {
        this.seckillService = seckillService;
    }

    @PostMapping("/{id}/seckill")
    @Operation(summary = "提交限量活动秒杀请求", description = "异步模式经Redis+Lua原子预扣并投递MQ返回202 QUEUED；同requestId重试幂等。")
    public ResponseEntity<SeckillResponse> submitSeckill(
            @AuthenticationPrincipal PlatformUserDetails user,
            @PathVariable @Positive long id,
            @Valid @RequestBody SeckillRequest request) {
        ActivitySeckillService.SubmitOutcome outcome = seckillService.submit(user.getId(), id, request);
        return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
    }

    @GetMapping("/seckill/result/{requestId}")
    @Operation(summary = "查询本人秒杀排队或成单结果", description = "返回QUEUED、CREATED（含订单）或FAILED。")
    public SeckillResponse getSeckillResult(
            @AuthenticationPrincipal PlatformUserDetails user,
            @PathVariable @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String requestId) {
        return seckillService.getResult(user.getId(), requestId);
    }
}
