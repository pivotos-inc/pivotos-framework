package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiTool;
import org.apache.ibatis.annotations.Mapper;

/** AI 工具注册表 Mapper（S98 A2） */
@Mapper
public interface AiToolMapper extends BaseMapper<AiTool> {
}
