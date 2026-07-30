package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiProvider;
import org.apache.ibatis.annotations.Mapper;

/** AI 模型供应商 Mapper */
@Mapper
public interface AiProviderMapper extends BaseMapper<AiProvider> {
}
