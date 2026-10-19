package com.elaina.limitedtrade.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipOrderRequest(
        @NotBlank(message = "承运方不能为空") @Size(max = 50, message = "承运方最多50字符") String carrier,
        @NotBlank(message = "运单号不能为空") @Size(max = 64, message = "运单号最多64字符") String trackingNo) {
}
