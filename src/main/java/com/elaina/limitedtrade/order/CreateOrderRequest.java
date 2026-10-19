package com.elaina.limitedtrade.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(
        @NotNull(message = "商品编号不能为空") @Positive(message = "商品编号必须大于0")
        Long productId,
        @NotNull(message = "购买数量不能为空") @Positive(message = "购买数量必须大于0")
        Integer quantity,
        @NotBlank(message = "请求标识不能为空")
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "请求标识须为1至64位字母、数字、下划线或连字符")
        String requestId,
        @Positive(message = "活动编号必须大于0") Long activityId,
        @Positive(message = "用户优惠券编号必须大于0") Long userCouponId,
        @Positive(message = "地址编号必须大于0") Long addressId) {
    public CreateOrderRequest(Long productId, Integer quantity, String requestId, Long activityId) {
        this(productId, quantity, requestId, activityId, null, null);
    }

    public CreateOrderRequest(Long productId, Integer quantity, String requestId, Long activityId, Long userCouponId) {
        this(productId, quantity, requestId, activityId, userCouponId, null);
    }
}
