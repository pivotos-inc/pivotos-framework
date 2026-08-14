package com.pivotos.ai.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.kb.domain.entity.KbEvalRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库检索评测跑分记录 Mapper（S67）。
 */
@Mapper
public interface KbEvalRecordMapper extends BaseMapper<KbEvalRecord> {
}
