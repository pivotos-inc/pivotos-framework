package com.pivotos.migration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.migration.domain.entity.MigrationTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 迁移任务 Mapper。
 */
@Mapper
public interface MigrationTaskMapper extends BaseMapper<MigrationTask> {
}
