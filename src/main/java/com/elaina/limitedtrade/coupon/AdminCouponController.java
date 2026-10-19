package com.elaina.limitedtrade.coupon;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/coupons")
public class AdminCouponController {
    private final CouponService coupons;
    public AdminCouponController(CouponService coupons){this.coupons=coupons;}

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ActivityCoupon create(@Valid @RequestBody CreateCouponRequest request){return coupons.createCoupon(request);}
}
