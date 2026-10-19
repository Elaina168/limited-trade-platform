package com.elaina.limitedtrade.coupon;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.elaina.limitedtrade.activity.ActivityMapper;
import com.elaina.limitedtrade.user.AppUserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CouponService {
    private final CouponMapper coupons;
    private final ActivityMapper activities;
    private final AppUserMapper users;

    public CouponService(CouponMapper coupons, ActivityMapper activities, AppUserMapper users) {
        this.coupons=coupons;
        this.activities=activities;
        this.users=users;
    }

    @Transactional
    public ActivityCoupon createCoupon(CreateCouponRequest request) {
        var activity=activities.findById(request.activityId());
        if(activity==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"活动不存在");
        String name=request.name().strip();
        var claimStart=request.claimStartsAt().withNano(0);
        var claimEnd=request.claimEndsAt().withNano(0);
        var useStart=request.useStartsAt().withNano(0);
        var useEnd=request.useEndsAt().withNano(0);
        if(name.isEmpty() || request.discountAmount().compareTo(request.thresholdAmount())>=0
                || !claimStart.isBefore(claimEnd) || !useStart.isBefore(useEnd) || claimEnd.isAfter(useEnd)
                || useStart.isBefore(activity.getStartsAt()) || useEnd.isAfter(activity.getEndsAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"券名称、满减金额或时间区间无效");
        }
        var coupon=new ActivityCoupon();
        coupon.setActivityId(request.activityId());coupon.setName(name);
        coupon.setThresholdAmount(request.thresholdAmount());coupon.setDiscountAmount(request.discountAmount());
        coupon.setTotalQuantity(request.totalQuantity());coupon.setRemainingQuantity(request.totalQuantity());
        coupon.setClaimStartsAt(claimStart);coupon.setClaimEndsAt(claimEnd);
        coupon.setUseStartsAt(useStart);coupon.setUseEndsAt(useEnd);
        coupons.insertCoupon(coupon);
        return coupon;
    }

    public List<ActivityCoupon> listActivityCoupons(long activityId) {
        if(activities.findById(activityId)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"活动不存在");
        return coupons.findByActivity(activityId);
    }

    @Transactional
    public UserCouponResponse claim(long userId,long couponId) {
        // 与下单共用用户锁，重复领取直接返回原记录，不重复消耗发放额度。
        users.lockById(userId);
        var existing=coupons.findByCouponAndUser(couponId,userId);
        if(existing!=null)return UserCouponResponse.from(existing,coupons.findCouponById(couponId),LocalDateTime.now());
        var coupon=coupons.findCouponForUpdate(couponId);
        if(coupon==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"优惠券不存在");
        var now=LocalDateTime.now().withNano(0);
        if(coupons.deductClaim(couponId,now)==0)throw new ResponseStatusException(HttpStatus.CONFLICT,"不在领取时间内或券已领完");
        var owned=new UserCoupon();owned.setCouponId(couponId);owned.setUserId(userId);
        owned.setStatus("AVAILABLE");owned.setClaimedAt(now);
        coupons.insertUserCoupon(owned);
        coupon.setRemainingQuantity(coupon.getRemainingQuantity()-1);
        return UserCouponResponse.from(owned,coupon,now);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<UserCouponResponse> listMine(long userId) {
        var owned=coupons.findByUser(userId);
        var templates=coupons.findClaimedTemplates(userId).stream().collect(Collectors.toMap(ActivityCoupon::getId,Function.identity()));
        var now=LocalDateTime.now();
        return owned.stream().map(c->UserCouponResponse.from(c,templates.get(c.getCouponId()),now)).toList();
    }
}
