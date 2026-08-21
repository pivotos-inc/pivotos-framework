package com.pivotos.migration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.migration.domain.entity.MigrationStep;
import org.apache.ibatis.annotations.Mapper;

/**
 * 迁移步骤 Mapper。
 */
@Mapper
public interface MigrationStepMapper extends BaseMapper<MigrationStep> {
}
