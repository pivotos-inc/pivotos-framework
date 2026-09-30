package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiToolPlanStep;
import org.apache.ibatis.annotations.Mapper;

/** AI 工具编排步骤轨迹 Mapper（A5-2 / S117） */
@Mapper
public interface AiToolPlanStepMapper extends BaseMapper<AiToolPlanStep> {
}
