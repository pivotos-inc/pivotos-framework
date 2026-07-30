package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiApiKey;
import org.apache.ibatis.annotations.Mapper;

/** AI API Key Mapper */
@Mapper
public interface AiApiKeyMapper extends BaseMapper<AiApiKey> {
}
