package com.elaina.limitedtrade.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateProductStatusRequest(
        @NotBlank(message = "商品状态不能为空")
        @Pattern(regexp = "ON_SALE|OFF_SALE", message = "商品状态只能为ON_SALE或OFF_SALE")
        @Schema(allowableValues = {"ON_SALE", "OFF_SALE"})
        String status) {
}
