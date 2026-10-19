package com.elaina.limitedtrade.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "[A-Za-z0-9_]{3,50}", message = "用户名须为3至50位字母、数字或下划线")
        String username,
        String password) {
}
