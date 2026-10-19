package com.elaina.limitedtrade.activity;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ActivityMapper {

    int insertActivity(Activity activity);

    Activity findById(long id);

    List<Activity> findAll();

    Activity findByIdForUpdate(long id);

    int deductQuantity(@Param("id") long id, @Param("quantity") int quantity, @Param("now") LocalDateTime now);

    int restoreQuantity(@Param("id") long id, @Param("quantity") int quantity);
}
