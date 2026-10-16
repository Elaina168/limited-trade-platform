package com.elaina.limitedtrade.auth;

import java.util.List;

import com.elaina.limitedtrade.user.AppUser;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public class PlatformUserDetails extends User {

    private final Long id;
    private final String role;

    public PlatformUserDetails(AppUser appUser) {
        super(appUser.username(), appUser.passwordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + appUser.role())));
        this.id = appUser.id();
        this.role = appUser.role();
    }

    public Long getId() {
        return id;
    }

    public String getRole() {
        return role;
    }
}
