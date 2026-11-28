package com.elaina.limitedtrade.system;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SchemaMigrationMapper {

    List<String> findPresentTables();

    List<String> findPresentColumns();

    List<String> findPresentIndexes();
}
