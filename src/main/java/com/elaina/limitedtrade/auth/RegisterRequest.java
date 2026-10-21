package com.elaina.limitedtrade.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "[A-Za-z0-9_]{3,50}", message = "用户名须为3至50位字母、数字或下划线")
        String username,
        @Schema(description = "必填；Java字符串长度至少8，UTF-8编码最多72字节，不允许全空白",
                format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String password) {
}
