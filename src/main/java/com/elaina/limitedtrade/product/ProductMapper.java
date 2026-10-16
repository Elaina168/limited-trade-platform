package com.elaina.limitedtrade.product;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProductMapper {

    List<Product> findOnSaleProducts();

    Product findById(long id);

    int insertProduct(Product product);

    int updateDetails(@Param("id") long id, @Param("request") UpdateProductRequest request);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    int deductStock(@Param("id") long id, @Param("quantity") int quantity);

    int restoreStock(@Param("id") long id, @Param("quantity") int quantity);
}
