package com.pivotos.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.message.domain.entity.MsgUserMessage;
import org.apache.ibatis.annotations.Mapper;

/** 用户消息 Mapper */
@Mapper
public interface MsgUserMessageMapper extends BaseMapper<MsgUserMessage> {
}
