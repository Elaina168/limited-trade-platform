package com.elaina.limitedtrade.auth;

import com.elaina.limitedtrade.user.AppUser;
import com.elaina.limitedtrade.user.AppUserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserMapper appUserMapper;

    public DatabaseUserDetailsService(AppUserMapper appUserMapper) {
        this.appUserMapper = appUserMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser appUser = appUserMapper.findByUsername(username);
        if (appUser == null) {
            throw new UsernameNotFoundException("用户名或密码错误");
        }
        return new PlatformUserDetails(appUser);
    }
}
