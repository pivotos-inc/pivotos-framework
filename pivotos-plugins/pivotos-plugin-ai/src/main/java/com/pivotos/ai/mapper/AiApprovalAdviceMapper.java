package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiApprovalAdvice;
import org.apache.ibatis.annotations.Mapper;

/** AI 审批建议 Mapper（S101 A3） */
@Mapper
public interface AiApprovalAdviceMapper extends BaseMapper<AiApprovalAdvice> {
}
