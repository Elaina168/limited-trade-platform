package com.elaina.limitedtrade.order;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderItemMapper {
    int insertItems(@Param("orderId") long orderId, @Param("items") List<OrderItem> items);

    List<OrderItem> findByOrderIds(@Param("orderIds") List<Long> orderIds);
}
