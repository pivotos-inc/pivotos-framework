package com.pivotos.migration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.migration.domain.entity.MigrationFile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 迁移源文件索引 Mapper。
 */
@Mapper
public interface MigrationFileMapper extends BaseMapper<MigrationFile> {
}
