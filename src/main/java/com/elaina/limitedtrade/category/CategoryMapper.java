package com.elaina.limitedtrade.category;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CategoryMapper {
    List<Category> findAll();
    Category findById(long id);
    int insertCategory(Category category);
    int updateName(@Param("id") long id, @Param("name") String name);
}
