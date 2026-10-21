package com.elaina.limitedtrade.coupon;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "优惠券管理")
@RestController
@RequestMapping("/api/admin/coupons")
public class AdminCouponController {
    private final CouponService coupons;
    public AdminCouponController(CouponService coupons){this.coupons=coupons;}

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建活动满减券", description = "绑定已有活动，校验金额和领取、使用时间；成功返回201。")
    public ActivityCoupon create(@Valid @RequestBody CreateCouponRequest request){return coupons.createCoupon(request);}
}
