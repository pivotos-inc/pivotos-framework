package com.pivotos.migration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.migration.domain.entity.MigrationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 迁移执行日志 Mapper。
 */
@Mapper
public interface MigrationLogMapper extends BaseMapper<MigrationLog> {
}
