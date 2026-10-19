package com.elaina.limitedtrade.order;

import java.time.LocalDateTime;

public record OrderShipment(Long orderId, String carrier, String trackingNo,
                            LocalDateTime shippedAt, LocalDateTime receivedAt) {
}
