package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderShipmentMapper {
    int insertShipment(OrderShipment shipment);

    int markReceived(@Param("orderId") long orderId, @Param("now") LocalDateTime now);

    List<OrderShipment> findByOrderIds(@Param("orderIds") List<Long> orderIds);
}
