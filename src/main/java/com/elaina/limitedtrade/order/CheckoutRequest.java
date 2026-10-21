package com.elaina.limitedtrade.order;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

public record CheckoutRequest(
        @NotEmpty(message = "请选择购物车商品") @Size(max = 50, message = "一次最多结算50种商品")
        @Schema(description = "本人购物车中选中的商品编号，不得重复；数量取自购物车")
        List<@NotNull @Positive Long> productIds,
        @Schema(description = "本人地址编号，必填；创建订单时保存快照")
        @NotNull @Positive Long addressId,
        @Schema(description = "本次结算的幂等标识，相同购买的重试须复用")
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestId) {
}
