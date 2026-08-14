package com.pivotos.ai.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库 Mapper。
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {
}
