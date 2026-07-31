package com.pivotos.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.domain.entity.AiChatMessage;
import org.apache.ibatis.annotations.Mapper;

/** AI 对话消息 Mapper */
@Mapper
public interface AiChatMessageMapper extends BaseMapper<AiChatMessage> {
}
