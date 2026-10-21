package com.elaina.limitedtrade.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证")
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final RegistrationService registrationService;

    public AuthController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "注册普通用户", description = "创建USER账号，成功返回201；用户名重复返回409，不自动登录。密码至少8个字符且UTF-8编码不超过72字节。")
    public CurrentUserResponse register(@Valid @RequestBody RegisterRequest request) {
        return registrationService.register(request);
    }

    @GetMapping("/csrf")
    @Operation(summary = "获取当前会话CSRF Token", description = "匿名可调用。返回headerName、parameterName、token；写请求使用返回的headerName作为请求头。登录或退出后需重新获取。")
    public CsrfToken csrf(@io.swagger.v3.oas.annotations.Parameter(hidden = true) CsrfToken csrfToken) {
        return csrfToken;
    }

    @GetMapping("/me")
    @Operation(summary = "查询当前登录身份", description = "未登录返回401；不返回密码或密码哈希。")
    public CurrentUserResponse currentUser(@AuthenticationPrincipal PlatformUserDetails user) {
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getRole());
    }
}
