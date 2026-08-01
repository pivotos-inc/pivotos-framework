package com.pivotos.generator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.generator.domain.entity.GenTableColumn;
import org.apache.ibatis.annotations.Mapper;

/**
 * 生成表字段 Mapper
 */
@Mapper
public interface GenTableColumnMapper extends BaseMapper<GenTableColumn> {
}
