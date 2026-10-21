package com.elaina.limitedtrade.observability;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMessageMetricsMapper {
    OrderMessageDatabaseStats snapshot(@Param("now") LocalDateTime now);
}
