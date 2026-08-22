package com.pivotos.migration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.migration.domain.entity.MigrationIrNode;
import org.apache.ibatis.annotations.Mapper;

/**
 * 迁移 IR 节点 Mapper。
 */
@Mapper
public interface MigrationIrNodeMapper extends BaseMapper<MigrationIrNode> {
}
