package com.elaina.limitedtrade.order.message;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderTimeoutOutboxMapper {

    int insertEvent(OrderTimeoutOutbox event);

    List<Long> findPendingIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    OrderTimeoutOutbox lockPending(@Param("id") long id, @Param("now") LocalDateTime now);

    int markSent(@Param("id") long id, @Param("attemptedAt") LocalDateTime attemptedAt);

    int markFailed(@Param("id") long id, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                   @Param("error") String error);
}
