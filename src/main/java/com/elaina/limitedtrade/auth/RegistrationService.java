package com.elaina.limitedtrade.auth;

import java.nio.charset.StandardCharsets;
import com.elaina.limitedtrade.user.AppUserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RegistrationService {
    private final AppUserMapper users;
    private final PasswordEncoder passwords;

    public RegistrationService(AppUserMapper users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public CurrentUserResponse register(RegisterRequest request) {
        String password = request.password();
        // 使用通用错误提示校验密码，不把原文放入字段校验异常或日志。
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码须至少8个字符且UTF-8编码不超过72字节");
        }
        String hash = passwords.encode(password);
        try {
            users.insertUser(request.username(), hash);
        } catch (DuplicateKeyException duplicate) {
            // 唯一键负责并发重名裁决，不能用先查询再插入替代。
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
        }
        var user = users.findByUsername(request.username());
        return new CurrentUserResponse(user.id(), user.username(), user.role());
    }
}
