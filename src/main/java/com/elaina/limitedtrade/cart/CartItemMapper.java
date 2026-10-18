package com.elaina.limitedtrade.cart;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CartItemMapper {
    List<CartItemResponse> findUserItems(long userId);

    int setQuantity(@Param("userId") long userId, @Param("productId") long productId,
                    @Param("quantity") int quantity);

    int deleteItem(@Param("userId") long userId, @Param("productId") long productId);
}
