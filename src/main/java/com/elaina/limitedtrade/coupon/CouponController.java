package com.elaina.limitedtrade.coupon;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "优惠券")
@RestController
public class CouponController {
    private final CouponService coupons;
    public CouponController(CouponService coupons){this.coupons=coupons;}

    @GetMapping("/api/activities/{activityId}/coupons")
    @Operation(summary = "查询活动优惠券规则", description = "匿名可读；活动不存在返回404。")
    public List<ActivityCoupon> list(@PathVariable @Positive long activityId){return coupons.listActivityCoupons(activityId);}

    @PostMapping("/api/coupons/{id}/claim")
    @Operation(summary = "领取活动优惠券", description = "重复领取返回本人已有记录；不在领取时间或已领完返回409。")
    public UserCouponResponse claim(@AuthenticationPrincipal PlatformUserDetails user,@PathVariable @Positive long id){return coupons.claim(user.getId(),id);}

    @GetMapping("/api/coupons/mine")
    @Operation(summary = "查询本人优惠券", description = "只返回本人领取记录及对应券规则。")
    public List<UserCouponResponse> mine(@AuthenticationPrincipal PlatformUserDetails user){return coupons.listMine(user.getId());}
}
