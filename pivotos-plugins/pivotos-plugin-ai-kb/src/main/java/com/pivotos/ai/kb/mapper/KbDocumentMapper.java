package com.pivotos.ai.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.kb.domain.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库文档 Mapper。
 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
}
