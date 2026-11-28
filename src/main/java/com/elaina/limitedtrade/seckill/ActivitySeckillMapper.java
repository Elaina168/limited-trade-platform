package com.elaina.limitedtrade.seckill;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ActivitySeckillMapper {

    int insertIgnore(ActivityPurchaseReservation reservation);

    boolean hasSubmittedRequest(@Param("userId") long userId, @Param("requestId") String requestId);

    boolean hasOrderRequest(@Param("userId") long userId, @Param("requestId") String requestId);

    ActivityPurchaseReservation findByUserAndRequest(@Param("userId") long userId,
                                                     @Param("requestId") String requestId);

    ActivityPurchaseReservation findByOrderId(@Param("orderId") long orderId);

    int markCreated(@Param("id") long id, @Param("orderId") long orderId);

    int markFailed(@Param("id") long id, @Param("failReason") String failReason);

    int markCanceledByOrderId(@Param("orderId") long orderId, @Param("failReason") String failReason);

    int sumUserActiveActivityQuantity(@Param("userId") long userId, @Param("activityId") long activityId);

    int sumActiveOrderQuantityByActivity(@Param("activityId") long activityId);

    List<UserActivityQuantity> listActiveUserQuantities(@Param("activityId") long activityId);

    List<ActivityPurchaseReservation> findStaleQueued(@Param("limit") int limit);

    record UserActivityQuantity(long userId, int quantity) {
    }
}
