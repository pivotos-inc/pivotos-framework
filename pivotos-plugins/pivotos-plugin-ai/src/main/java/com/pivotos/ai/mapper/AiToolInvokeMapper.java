package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiToolInvoke;
import org.apache.ibatis.annotations.Mapper;

/** AI 工具调用审计 Mapper（S98 A2） */
@Mapper
public interface AiToolInvokeMapper extends BaseMapper<AiToolInvoke> {
}
