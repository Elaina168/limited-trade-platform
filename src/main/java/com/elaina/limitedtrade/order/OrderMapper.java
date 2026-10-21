package com.elaina.limitedtrade.order;

import java.util.List;
import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper {

    TradeOrder findByUserAndRequest(@Param("userId") long userId, @Param("requestId") String requestId);

    TradeOrder findOwnedOrder(@Param("id") long id, @Param("userId") long userId);

    long countUserOrders(@Param("userId") long userId, @Param("status") String status);

    List<TradeOrder> findUserOrders(@Param("userId") long userId, @Param("status") String status,
                                  @Param("offset") long offset, @Param("limit") int limit);

    int insertOrder(TradeOrder order);

    TradeOrder findOwnedOrderForUpdate(@Param("id") long id, @Param("userId") long userId);

    int markPaid(@Param("id") long id, @Param("userId") long userId, @Param("now") LocalDateTime now);

    int markCanceled(@Param("id") long id, @Param("userId") long userId, @Param("now") LocalDateTime now);

    List<Long> findExpiredOrderIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    int markClosed(@Param("id") long id, @Param("cutoff") LocalDateTime cutoff);

    TradeOrder findById(long id);

    TradeOrder findByIdForUpdate(long id);

    int markShipped(@Param("id") long id);

    int markCompleted(@Param("id") long id, @Param("userId") long userId);

    long countAdminOrders(@Param("status") String status, @Param("userId") Long userId);

    List<TradeOrder> findAdminOrders(@Param("status") String status, @Param("userId") Long userId,
                                    @Param("offset") long offset, @Param("limit") int limit);
}
