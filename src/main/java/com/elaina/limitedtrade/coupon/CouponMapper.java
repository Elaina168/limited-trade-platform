package com.elaina.limitedtrade.coupon;

import java.time.LocalDateTime;
import java.util.List;
import com.elaina.limitedtrade.order.OrderCoupon;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CouponMapper {
    void insertCoupon(ActivityCoupon coupon);
    ActivityCoupon findCouponById(long id);
    ActivityCoupon findCouponForUpdate(long id);
    List<ActivityCoupon> findByActivity(long activityId);
    List<ActivityCoupon> findClaimedTemplates(long userId);
    UserCoupon findByCouponAndUser(@Param("couponId") long couponId, @Param("userId") long userId);
    List<UserCoupon> findByUser(long userId);
    int deductClaim(@Param("id") long id, @Param("now") LocalDateTime now);
    void insertUserCoupon(UserCoupon coupon);
    UserCoupon findOwnedForUpdate(@Param("id") long id, @Param("userId") long userId);
    void reserve(@Param("id") long id, @Param("orderId") long orderId);
    void useForOrder(long orderId);
    void releaseForOrder(long orderId);
    void insertSnapshot(@Param("orderId") long orderId, @Param("coupon") OrderCoupon coupon);
    List<OrderCoupon> findByOrderIds(@Param("orderIds") List<Long> orderIds);
}
