package com.elaina.limitedtrade.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveCategoryRequest(
        @NotBlank(message = "分类名称不能为空")
        @Size(max = 50, message = "分类名称不能超过50个字符") String name) {
}
