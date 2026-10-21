package com.elaina.limitedtrade.order;

import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "客户端生成的下单幂等标识；同一笔购买重试须复用，修改购买参数须换新标识")
        String requestId,
        @Schema(description = "可选；省略时普通购买，填写时参加对应商品活动")
        @Positive(message = "活动编号必须大于0") Long activityId,
        @Schema(description = "可选；本人领取记录编号，不是优惠券模板编号，须适用于本次活动")
        @Positive(message = "用户优惠券编号必须大于0") Long userCouponId,
        @Schema(description = "可选；本人地址编号，创建订单时保存快照")
        @Positive(message = "地址编号必须大于0") Long addressId) {
    public CreateOrderRequest(Long productId, Integer quantity, String requestId, Long activityId) {
        this(productId, quantity, requestId, activityId, null, null);
    }

    public CreateOrderRequest(Long productId, Integer quantity, String requestId, Long activityId, Long userCouponId) {
        this(productId, quantity, requestId, activityId, userCouponId, null);
    }
}
