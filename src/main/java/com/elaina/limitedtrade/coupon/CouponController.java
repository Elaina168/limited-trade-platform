package com.elaina.limitedtrade.coupon;

import java.util.List;
import com.elaina.limitedtrade.auth.PlatformUserDetails;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class CouponController {
    private final CouponService coupons;
    public CouponController(CouponService coupons){this.coupons=coupons;}

    @GetMapping("/api/activities/{activityId}/coupons")
    public List<ActivityCoupon> list(@PathVariable @Positive long activityId){return coupons.listActivityCoupons(activityId);}

    @PostMapping("/api/coupons/{id}/claim")
    public UserCouponResponse claim(@AuthenticationPrincipal PlatformUserDetails user,@PathVariable @Positive long id){return coupons.claim(user.getId(),id);}

    @GetMapping("/api/coupons/mine")
    public List<UserCouponResponse> mine(@AuthenticationPrincipal PlatformUserDetails user){return coupons.listMine(user.getId());}
}
