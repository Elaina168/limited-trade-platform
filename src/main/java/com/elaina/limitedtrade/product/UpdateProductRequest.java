package com.elaina.limitedtrade.product;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;

public record UpdateProductRequest(
        @NotBlank(message = "商品名称不能为空")
        @Size(max = 100, message = "商品名称不能超过100个字符")
        String name,

        @NotNull(message = "商品价格不能为空")
        @DecimalMin(value = "0.00", inclusive = false, message = "商品价格必须大于0")
        @Digits(integer = 8, fraction = 2, message = "价格最多8位整数和2位小数")
        BigDecimal price,

        @Positive(message = "分类编号必须大于0")
        Long categoryId) {
}
