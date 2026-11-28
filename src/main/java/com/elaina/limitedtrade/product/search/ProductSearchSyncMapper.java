package com.elaina.limitedtrade.product.search;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProductSearchSyncMapper {
    void enqueue(@Param("ids") List<Long> ids);
    boolean hasPending();
    Long tryLockWorker();
    long lockWorker();
    List<ProductSearchChange> findPending(@Param("limit") int limit);
    void acknowledge(@Param("changes") List<ProductSearchChange> changes);
}
