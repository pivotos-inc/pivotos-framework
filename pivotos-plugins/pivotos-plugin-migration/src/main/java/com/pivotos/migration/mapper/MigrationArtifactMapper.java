package com.pivotos.migration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.migration.domain.entity.MigrationArtifact;
import org.apache.ibatis.annotations.Mapper;

/**
 * 迁移产物 Mapper。
 */
@Mapper
public interface MigrationArtifactMapper extends BaseMapper<MigrationArtifact> {
}
