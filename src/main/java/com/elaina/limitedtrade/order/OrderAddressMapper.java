package com.elaina.limitedtrade.order;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderAddressMapper {
    int insertAddress(OrderAddress address);

    List<OrderAddress> findByOrderIds(@Param("orderIds") List<Long> orderIds);
}
