package com.elaina.limitedtrade.user;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AppUserMapper {

    AppUser findByUsername(@Param("username") String username);

    Long lockById(long id);
}
