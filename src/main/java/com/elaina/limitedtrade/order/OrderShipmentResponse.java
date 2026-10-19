package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;

public record OrderShipmentResponse(String carrier, String trackingNo,
                                    LocalDateTime shippedAt, LocalDateTime receivedAt) {
    public static OrderShipmentResponse from(OrderShipment shipment) {
        return new OrderShipmentResponse(shipment.carrier(), shipment.trackingNo(),
                shipment.shippedAt(), shipment.receivedAt());
    }
}
