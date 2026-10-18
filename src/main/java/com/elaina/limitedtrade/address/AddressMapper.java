package com.elaina.limitedtrade.address;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AddressMapper {
    List<UserAddress> findUserAddresses(long userId);
    long countUserAddresses(long userId);
    UserAddress findOwnedAddress(@Param("id") long id, @Param("userId") long userId);
    int insertAddress(UserAddress address);
    int updateDetails(UserAddress address);
    int deleteOwnedAddress(@Param("id") long id, @Param("userId") long userId);
    int clearDefault(long userId);
    int markDefault(@Param("id") long id, @Param("userId") long userId);
}
