package com.elaina.limitedtrade.order.message;

import java.time.LocalDateTime;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderTimeoutMessage(@NotNull @Positive Long eventId,
                                  @NotNull @Positive Long orderId,
                                  @NotNull LocalDateTime expiresAt) {
}
