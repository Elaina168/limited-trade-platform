package com.elaina.limitedtrade.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AppUserMapper {

    AppUser findByUsername(@Param("username") String username);

    void insertUser(@Param("username") String username, @Param("passwordHash") String passwordHash);

    Long lockById(long id);
}
